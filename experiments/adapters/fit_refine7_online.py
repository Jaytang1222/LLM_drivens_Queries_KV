"""Development calibration using reconstructed ONLINE features, not actual scan counters.

Exclude all nine evaluation queries; query-group out-of-fold errors select a
one-sided empirical margin. No claim of formal/generalization confidence.
"""
import argparse, json, hashlib
from collections import defaultdict
from pathlib import Path
import numpy as np
from scipy.optimize import nnls

def features(f, basis='log'):
    keys=[('scan_ranges','seek_ranges'),('fetch_gets','estimated_candidate_chunks'),('estimated_index_rows','decode_rows')]
    vals=[f.get(a,f.get(b)) for a,b in keys]
    if any(v is None or not np.isfinite(v) or v<0 for v in vals):
        return None
    return np.array(vals)/1000 if basis=='linear' else np.log1p(vals)

def main():
    p=argparse.ArgumentParser();p.add_argument('--features',required=True);p.add_argument('--measurements',required=True);p.add_argument('--out',required=True);p.add_argument('--basis',choices=['log','linear'],default='log');a=p.parse_args()
    root=Path(__file__).resolve().parents[2]
    excluded=set()
    for name in ['bound_ir_ais_opportunity_v1.json','bound_ir_cbo_llm_overhead_v1.json']:
        excluded.update(q['query_id'] for q in json.loads((root/'experiments/workloads'/name).read_text())['queries'])
    online={}; fingerprints={}
    for line in Path(a.features).read_text().splitlines():
        if not line.startswith('{'): continue
        r=json.loads(line);online[(r['query_id'],r['plan_id'])]=features(r['features'],a.basis)
        fingerprints[r['query_id']]=r.get('fingerprint')
    times=defaultdict(list); cbo={}
    for line in Path(a.measurements).read_text().splitlines():
        r=json.loads(line)
        if r.get('status')!='OK' or r.get('ok_oracle') is not True or r['query_id'] in excluded: continue
        if not fingerprints.get(r['query_id']) or fingerprints[r['query_id']]!=r.get('fingerprint'):continue
        times[(r['query_id'],r['plan_id'])].append(r['t_exec_ms'])
        if r.get('cbo_selected'):cbo[r['query_id']]=r['plan_id']
    rows=[]
    for (q,plan),tt in sorted(times.items()):
        base=cbo.get(q)
        if not base or base==plan or (q,base) not in times:continue
        if plan not in ['P_T','P_Z','P_TZ'] or base not in ['P_T','P_Z','P_TZ']:continue
        x,y=online.get((q,base)),online.get((q,plan))
        if x is None or y is None:continue
        rows.append((q,plan,x-y,float(np.median(times[q,base])-np.median(tt))))
    X=np.array([r[2] for r in rows]);Y=np.array([r[3] for r in rows]); folds=np.array([int(hashlib.sha256(r[0].encode()).hexdigest()[:8],16)%5 for r in rows])
    residual=[]
    for fold in range(5):
        train=folds!=fold;valid=~train
        if not valid.any(): continue
        coef=nnls(X[train],Y[train])[0]
        residual.extend((X[valid]@coef-Y[valid]).tolist())
    coef=nnls(X,Y)[0];margin=max(0,float(np.quantile(residual,.9,method='higher')))
    model={'version':'online_pair_nnls_v1','coefficients':coef.tolist(),'margin_ms':margin,'training_queries':len(set(r[0] for r in rows)),'pairs':len(rows),'excluded_query_ids':sorted(excluded),'oof_mae_ms':float(np.mean(np.abs(residual))),'margin_definition':'query-group OOF upper residual p90, development empirical only','feature_source':'FastCost execution-before catalog estimates','features_sha256':hashlib.sha256(Path(a.features).read_bytes()).hexdigest()}
    model['basis']=a.basis
    model['training_query_ids']=sorted(set(r[0] for r in rows))
    model['feature_source']='compiled range count + execution-before frozen-statistics estimates'
    Path(a.out).write_text(json.dumps(model,indent=2)+'\n');print(json.dumps(model,indent=2))
    for q in sorted(excluded):
        preds={plan:float(x@coef) for (qid,plan),x in online.items() if qid==q and x is not None and plan in ['P_T','P_Z','P_TZ']}
        print(q,preds)
if __name__=='__main__':main()
