# T-Drive profile

- Source: `/home/jaytang/projects/llm-kv/datasets/tdrive` (compat: `/home/jaytang/build/LLM_KV/datasets/tdrive`)
- Lines: 47102
- Trajectories (parsed): 47102
- Points: 2925503
- Parse rejected: 0
- Exact duplicate consecutive points: 199874 (6.83%)
- Points outside rough Beijing box [115–118]×[39–41.5]: 0 (0.0%)
- Time range (UTC ms): [1201930244000, 1202463559000]
- Lon/Lat range: lon[115.47173, 117.82745] lat[39.04912, 41.21767]
- UTM50N meters: x[369126.04280156386, 571312.212152737] y[4322415.863787895, 4562988.491498032]
- Points/traj: min=11 p50=35 p95=193 max=994

## Recommended manifest domain (min/max ±1 km)

```
xmin=368126.04280156386 xmax=572312.212152737 ymin=4321415.863787895 ymax=4563988.491498032
```

## OI-9 decision

Out-of-rough-box points < 0.1%. **Default: reject whole trajectory on any domain-out point** (domain = profile min/max + 1 km). Clamp is not used.

## Epoch

`epoch_ms = floor(min_ts / 600000) * 600000` = 1201930200000

## Verified build metrics (READY snapshot)

From `catalog/tdrive_v1_ready.manifest.json` `build_report` (status READY):

| Metric | Value |
|---|---|
| trajectories | 47102 |
| points (after clean) | 2724388 |
| rejected parse / out_of_domain / empty | 0 / 0 / 0 |
| dedup_removed | 199958 |

Smoke (`experiments/results/tdrive_smoke_report.json`): **22/22** pass vs FullScan Oracle (`tdrive_v1_ready`).
