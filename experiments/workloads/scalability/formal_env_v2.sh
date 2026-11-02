# Scalability GATE_v2 formal env (source from repo root).
export KART_ADVANCED_INDEXES=1
export KART_ADV_FIXED_PRIMARY=1
export KART_DROP_PAGE_CACHE=0
export KART_WARMUP_PASSES=2
export KART_ACTION_G1_MS=50
export KART_ADV_COST_MARGIN_RATIO=0.28
_ROOT="${KART_ROOT:-$PWD}"
export KART_COST_PATCH="${KART_COST_PATCH:-$_ROOT/config/cost_patch_scalability.json}"
unset KART_CLASSIC_FIXED_PREFER_BIPART || true
unset KART_CLASSIC_FIXED_PREFER_Z || true
unset _ROOT
