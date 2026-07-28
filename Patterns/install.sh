#!/usr/bin/env bash
#
# install.sh — build the package and copy it into Chromatik's Packages folder
#
# Chromatik loads every .jar it finds in <chromatik>/Packages at startup, so
# after running this you just need to restart Chromatik (or hit the reload button
# in CONTENT > PACKAGES) to see the new patterns.
#
# Usage:
#   ./install.sh [options]
#
# Options:
#   -c, --chromatik PATH   Chromatik home dir (default: $CHROMATIK_HOME or ~/Chromatik)
#       --uninstall        Remove the installed jar instead
#   -h, --help             Show this help
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHROMATIK_HOME="${CHROMATIK_HOME:-$HOME/Chromatik}"
UNINSTALL=0

usage() { sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -c|--chromatik) CHROMATIK_HOME="$2"; shift 2 ;;
    --uninstall)    UNINSTALL=1; shift ;;
    -h|--help)      usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

CHROMATIK_HOME="${CHROMATIK_HOME/#\~/$HOME}"
PACKAGES_DIR="$CHROMATIK_HOME/Packages"

if [[ ! -d "$CHROMATIK_HOME" ]]; then
  echo "error: Chromatik home not found: $CHROMATIK_HOME" >&2
  echo "       Run Chromatik once to create it, or pass -c/--chromatik." >&2
  exit 1
fi

# --- Uninstall ----------------------------------------------------------------
if [[ $UNINSTALL -eq 1 ]]; then
  shopt -s nullglob
  removed=0
  for jar in "$PACKAGES_DIR"/blinky-dome-patterns-*.jar; do
    echo "Removing $jar"
    rm -f "$jar"
    removed=$((removed+1))
  done
  if [[ $removed -eq 0 ]]; then
    echo "Nothing to remove in $PACKAGES_DIR"
  else
    echo "Removed $removed jar(s). Restart Chromatik to unload."
  fi
  exit 0
fi

# --- Build --------------------------------------------------------------------
"$SCRIPT_DIR/build.sh"

shopt -s nullglob
jars=("$SCRIPT_DIR"/target/*.jar)
if [[ ${#jars[@]} -eq 0 ]]; then
  echo "error: build produced no jar" >&2
  exit 1
fi

# --- Install ------------------------------------------------------------------
mkdir -p "$PACKAGES_DIR"

# Clear out older versions of *this* package so Chromatik doesn't load two copies
# of the same classes.
for old in "$PACKAGES_DIR"/blinky-dome-patterns-*.jar; do
  rm -f "$old"
done

for jar in "${jars[@]}"; do
  echo "Installing $(basename "$jar") -> $PACKAGES_DIR"
  cp "$jar" "$PACKAGES_DIR/"
done

echo
echo "Done. Restart Chromatik, then find the pattern under the"
echo "\"Blinky Dome\" category in the pattern browser."
