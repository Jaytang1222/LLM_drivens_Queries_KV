# Registered regions (OI-10)

KART does not geocode free-text place names. Natural-language queries may use:

1. Explicit lon/lat rectangles, or
2. A `region_name` listed in `config/regions.yaml`.

## Status (2026-09-23)

Default table populated from **public map coordinates** (approximate AABBs around well-known Beijing POIs/districts) for T-Drive NL demos. See comments in `config/regions.yaml` for sources.

| name | Rough meaning | Lon range | Lat range |
|------|---------------|-----------|-----------|
| `beijing_core` | Inner-city demo box (pre-existing) | 116.28–116.48 | 39.82–39.98 |
| `zhongguancun` | 中关村一带 | 116.28–116.35 | 39.95–40.01 |
| `wangjing` | 望京一带 | 116.44–116.51 | 39.97–40.03 |
| `guomao` / `beijing_cbd` | 国贸 / CBD | 116.43–116.48 | 39.89–39.93 |
| `tiananmen` | 天安门附近 | 116.37–116.41 | 39.89–39.92 |
| `capital_airport` | 首都机场 PEK | 116.55–116.65 | 40.04–40.10 |
| `haidian_central` | 海淀中部粗框 | 116.25–116.36 | 39.95–40.05 |
| `chaoyang_central` | 朝阳中部粗框 | 116.42–116.55 | 39.90–40.02 |
| `fixture_box` | Synthetic fixture only (meters) | 4–8 | 4–8 |

These are **not** official 行政区划 boundaries. Tighten/widen boxes in `regions.yaml` if your paper needs a specific study area.

`PromptBuilder` injects the registered name list into the LLM system prompt automatically after config reload.
