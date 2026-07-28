#!/usr/bin/env bash
#
# install.sh — symlink the built package folder into Chromatik
#
# Mirrors how link-chromatik.sh handles the repo's other content folders: rather
# than copying files into the Chromatik tree, it namespaces this repo's package
# folder in with a single symlink:
#
#     <chromatik>/Packages/<namespace>  ->  <repo>/Patterns/blinky-dome
#
# Chromatik scans Packages/ recursively, so it finds the jar inside. You run this
# once. After that, `./build.sh` rewrites the jar in place and Chromatik picks up
# the new build on its next start — no reinstall step.
#
# Usage:
#   ./install.sh [options]
#
# Options:
#   -c, --chromatik PATH   Chromatik home dir (default: $CHROMATIK_HOME or ~/Chromatik)
#   -n, --namespace NAME   Namespace subfolder name (default: this repo's dir name)
#   -f, --force            Replace a symlink that currently points elsewhere
#       --unlink           Remove the symlink this script would create (uninstall)
#       --dry-run          Print what would happen, change nothing
#   -h, --help             Show this help
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Must match <package.dir> in pom.xml
PACKAGE_DIR="$SCRIPT_DIR/blinky-dome"

CHROMATIK_HOME="${CHROMATIK_HOME:-$HOME/Chromatik}"
NAMESPACE="$(basename "$REPO_ROOT")"
FORCE=0
UNLINK=0
DRY_RUN=0

usage() { sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -c|--chromatik) CHROMATIK_HOME="$2"; shift 2 ;;
    -n|--namespace) NAMESPACE="$2"; shift 2 ;;
    -f|--force)     FORCE=1; shift ;;
    --unlink)       UNLINK=1; shift ;;
    --dry-run)      DRY_RUN=1; shift ;;
    -h|--help)      usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

CHROMATIK_HOME="${CHROMATIK_HOME/#\~/$HOME}"
PACKAGES_DIR="$CHROMATIK_HOME/Packages"
LINK="$PACKAGES_DIR/$NAMESPACE"

if [[ ! -d "$CHROMATIK_HOME" ]]; then
  echo "error: Chromatik home not found: $CHROMATIK_HOME" >&2
  echo "       Run Chromatik once to create it, or pass -c/--chromatik." >&2
  exit 1
fi

run() { # echo + execute unless dry-run
  echo "  + $*"
  [[ $DRY_RUN -eq 1 ]] || "$@"
}

echo "Repo:      $REPO_ROOT"
echo "Chromatik: $CHROMATIK_HOME"
echo "Namespace: $NAMESPACE"
[[ $DRY_RUN -eq 1 ]] && echo "(dry run — no changes will be made)"
echo

# --- Unlink mode --------------------------------------------------------------
if [[ $UNLINK -eq 1 ]]; then
  if [[ -L "$LINK" ]]; then
    target="$(readlink "$LINK")"
    if [[ "$target" == "$PACKAGE_DIR" ]]; then
      echo "Packages: removing symlink"
      run rm "$LINK"
    else
      echo "Packages: symlink points elsewhere ($target) — leaving it alone"
    fi
  else
    echo "Packages: no symlink to remove"
  fi
  echo
  echo "Done. Restart Chromatik to unload the package."
  exit 0
fi

# --- Clean up jars copied by older versions of this script ---------------------
# The previous install.sh copied a versioned jar directly into Packages/. Left in
# place alongside the symlink, Chromatik would load the classes twice.
shopt -s nullglob
for stale in "$PACKAGES_DIR"/blinky-dome-patterns*.jar; do
  echo "Removing jar left by an older install: $(basename "$stale")"
  run rm -f "$stale"
done

# --- Make sure there's something to link --------------------------------------
if [[ ! -d "$PACKAGE_DIR" ]]; then
  run mkdir -p "$PACKAGE_DIR"
fi

jars=("$PACKAGE_DIR"/*.jar)
if [[ ${#jars[@]} -eq 0 ]]; then
  echo "No jar built yet — building now."
  echo
  "$SCRIPT_DIR/build.sh"
  echo
fi

# --- Link ---------------------------------------------------------------------
if [[ -L "$LINK" ]]; then
  target="$(readlink "$LINK")"
  if [[ "$target" == "$PACKAGE_DIR" ]]; then
    echo "Packages: already linked ✓"
  elif [[ $FORCE -eq 1 ]]; then
    echo "Packages: replacing symlink (was -> $target)"
    run ln -sfn "$PACKAGE_DIR" "$LINK"
  else
    echo "Packages: symlink exists but points to $target"
    echo "          re-run with --force to replace it" >&2
    exit 1
  fi
elif [[ -e "$LINK" ]]; then
  # A real file/dir is sitting where the symlink should go — never clobber it.
  echo "Packages: '$LINK' already exists and is NOT a symlink — skipping" >&2
  echo "          move/remove it manually if you want it namespaced" >&2
  exit 1
else
  echo "Packages: linking"
  [[ -d "$PACKAGES_DIR" ]] || run mkdir -p "$PACKAGES_DIR"
  run ln -s "$PACKAGE_DIR" "$LINK"
fi

echo
echo "Done — this is a one-time step. From now on just run ./build.sh and"
echo "restart Chromatik. Look for the \"Blinky Dome\" pattern category."
