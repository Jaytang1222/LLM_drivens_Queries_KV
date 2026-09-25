import json
from pathlib import Path
rows=[json.loads(l) for l in Path('/home/jaytang/projects/llm-kv/experiments/results/gate-plan/plan.jsonl').read_text().splitlines() if l.strip()]
bao=[r for r in rows if r['arm']=='bao']
print('bao_n', len(bao))
if bao:
    r=bao[0]
    print({k:r.get(k) for k in ['plan_start_ms','plan_end_ms','t_plan_ms','bao_second_search','plan_ok','plan_id']})
