#!/usr/bin/env bash
#
# generate_all.sh
#
# Regenerate every fixture from its Python generator in Generators/.
# Each generator imports Generators/constants.py and writes into Fixtures/,
# so they must run with Generators/ as the working directory.
#
# Usage:
#   ./generate_all.sh            # run all generators
#   PYTHON=python3.10 ./generate_all.sh   # use a specific interpreter
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GEN_DIR="$SCRIPT_DIR/Generators"
PYTHON="${PYTHON:-python3}"

if ! command -v "$PYTHON" >/dev/null 2>&1; then
  echo "error: '$PYTHON' not found on PATH (set \$PYTHON to override)" >&2
  exit 1
fi

cd "$GEN_DIR"

# List each generator explicitly. Add new ones here as they are created.
# generate_full_model.py assembles the fixtures into the .lxm, so it runs last.
generators=(
  generate_star.py
  generate_cat_ear.py
  generate_waterfall.py
  generate_dome_eye.py
  generate_full_model.py
)

echo "Running ${#generators[@]} generator(s) with $PYTHON"
echo
for gen in "${generators[@]}"; do
  echo "== $gen =="
  "$PYTHON" "$gen"
  echo
done

echo "All fixtures regenerated."
