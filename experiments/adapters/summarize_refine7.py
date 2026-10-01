"""Summarize complete confirmation runs, never select per-query best trials."""
import argparse,csv,json,statistics,hashlib
from pathlib import Path

def load(path):
    return [json.loads(line) for name in ('ais.jsonl','td.jsonl') for line in (Path(path)/name).read_text(encoding='utf8').splitlines()]
def med(rows,key):return statistics.median(r[key] for r in rows)
def main():
    p=argparse.ArgumentParser();p.add_argument('--before',required=True);p.add_argument('--after',required=True,action='append');p.add_argument('--out',required=True);a=p.parse_args()
    before=load(a.before);after_blocks=[load(path) for path in a.after]
    after=[r for block in after_blocks for r in block];out=Path(a.out);out.mkdir(parents=True,exist_ok=True)
    identities=[]
    for path in a.after:
        base=Path(path)
        cfg=dict(line.split('=',1) for line in (base/'config.txt').read_text().splitlines() if '=' in line)
        model_file=base/'benefit-model.json'
        model_hash=hashlib.sha256(model_file.read_bytes()).hexdigest() if model_file.exists() else None
        identities.append((cfg['model'],cfg['protocol'],(base/'build.sha256').read_text().split()[0],model_hash))
    assert len(set(identities))==1,'Do not aggregate different configurations'
    if len(after_blocks)>1: assert identities[0][-1] is not None,'Model snapshot required to merge blocks'
    keys=[(r['run_id'],r['query_id'],r['arm'],r['trial']) for r in after]
    assert len(keys)==len(set(keys)),'Duplicate confirmation blocks'
    for block in [before]+after_blocks:
        keys=[(r['run_id'],r['query_id'],r['arm'],r['trial']) for r in block]
        assert len(keys)==len(set(keys)), 'Duplicate trial rows'
        assert {r['arm'] for r in block}=={'cbo','cbo-llm-proposal'}
        for q in {r['query_id'] for r in block}:
            assert all(sum(r['query_id']==q and r['arm']==arm for r in block)==3 for arm in ('cbo','cbo-llm-proposal'))
    for rows in (before,after):
        assert all(r.get('ok_oracle') is True for r in rows),'Oracle failure'
        assert all(r.get('llm_calls')==1 for r in rows if r['arm']=='cbo-llm-proposal'),'LLM-on violation'
    qids=sorted({r['query_id'] for r in after});result=[];gains=[]
    assert set(qids)=={r['query_id'] for r in before}
    for q in qids:
        groups={'cbo':[r for r in after if r['query_id']==q and r['arm']=='cbo'],
                'before':[r for r in before if r['query_id']==q and r['arm']=='cbo-llm-proposal'],
                'after':[r for r in after if r['query_id']==q and r['arm']=='cbo-llm-proposal'],
                'cbo_before':[r for r in before if r['query_id']==q and r['arm']=='cbo']}
        assert len(groups['before'])==len(groups['cbo_before'])==3
        assert len(groups['after'])==len(groups['cbo'])==3*len(after_blocks)
        row={'query_id':q}
        for label,rr in groups.items():
            for field in ('plan','exec','e2e'):row[label+'_'+field+'_ms']=med(rr,'t_'+field+'_ms')
        paired=[]
        for r in groups['after']:
            c=next(c for c in groups['cbo'] if c['trial']==r['trial'] and c['run_id']==r['run_id']);paired.append(c['t_e2e_ms']-r['t_e2e_ms'])
        row['paired_gains_ms']=paired;row['paired_median_gain_ms']=statistics.median(paired)
        row['after_plans']=sorted({r['plan_id'] for r in groups['after']});result.append(row);gains.extend(paired)
    (out/'comparison.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    with (out/'comparison.csv').open('w',newline='',encoding='utf-8-sig') as f:
        w=csv.DictWriter(f,fieldnames=list(result[0]));w.writeheader();w.writerows(result)
    stats={}
    for label,rows in [('CBO', [r for r in after if r['arm']=='cbo']),('before',[r for r in before if r['arm']=='cbo-llm-proposal']),('after',[r for r in after if r['arm']=='cbo-llm-proposal'])]:
        stats[label]={field:statistics.mean(r['t_'+field+'_ms'] for r in rows) for field in ('plan','exec','e2e')}
    stats['query_wins']=sum(r['paired_median_gain_ms']>0 for r in result)
    stats['paired_trial_wins']=sum(g>0 for g in gains);stats['pairs']=len(gains)
    (out/'aggregate.json').write_text(json.dumps(stats,indent=2)+'\n',encoding='utf8')
    lines=['# 第七轮开发确认结果','',f'每条查询优化前 3 次、优化后 {3*len(after_blocks)} 次，表内为各时间字段的中位数（ms）；配对净收益按相同 run/query/trial 计算。CBO 列来自优化后同跑，优化前使用独立旧 jar 在相邻时间块重跑。未按查询挑选最快 trial。','','投机有重叠，plan+exec 不等于 E2E。仅现有九条 warm 开发查询，不是未知测试或全量结论。','','| 查询 | CBO plan/exec/E2E | 优化前 plan/exec/E2E | 优化后 plan/exec/E2E | 配对净收益 |','|---|---:|---:|---:|---|']
    for r in result:
        triple=lambda label:'/'.join(str(r[label+'_'+k+'_ms']) for k in ('plan','exec','e2e'))
        lines.append('| '+r['query_id']+' | '+triple('cbo')+' | '+triple('before')+' | '+triple('after')+' | '+str(r['paired_gains_ms'])+' |')
    lines+=['','## 来源','',f'- before: `{a.before}`']+[f'- after: `{path}`' for path in a.after]+['- 全部原始行保存到 before/after 子目录。','- `comparison.csv` 包含旧版本同跑 CBO，便于检查基线漂移。']
    (out/'results.md').write_text('\n'.join(lines)+'\n',encoding='utf8')
    for label,path in [('before',a.before)]+[('after' if i==0 else 'after'+str(i+1),path) for i,path in enumerate(a.after)]:
        d=out/label;d.mkdir(exist_ok=True)
        for name in ('ais.jsonl','td.jsonl','config.txt','build.sha256'):
            (d/name).write_bytes((Path(path)/name).read_bytes())
        for name in ('benefit-model.json','warm.json','model-show.json'):
            source=Path(path)/name
            if source.exists():(d/name).write_bytes(source.read_bytes())
    print(json.dumps(stats,indent=2))
if __name__=='__main__':main()
