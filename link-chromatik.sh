#!/usr/bin/env bash
#
# link-chromatik.sh
#
# Namespace this repo's Chromatik content folders into a real Chromatik
# installation using per-folder symlinks, so you can develop entirely inside
# this git repo without shuffling individual file symlinks in and out.
#
# For every content folder that exists in this repo (e.g. Fixtures/, Projects/)
# a symlink is created inside the matching Chromatik folder:
#
#     <chromatik>/Fixtures/<namespace>  ->  <repo>/Fixtures
#     <chromatik>/Projects/<namespace>  ->  <repo>/Projects
#     ...
#
# In the repo it is just a normal, git-tracked folder. In the Chromatik tree it
# shows up "namespaced" as a single <namespace>/ subfolder (default: the repo's
# directory name, e.g. "blinky-dome"). Chromatik scans subdirectories, so it
# picks the content up automatically.
#
# Usage:
#   ./link-chromatik.sh [options]
#
# Options:
#   -c, --chromatik PATH   Chromatik home dir (default: $CHROMATIK_HOME or ~/Chromatik)
#   -n, --namespace NAME   Namespace subfolder name (default: this repo's dir name)
#   -f, --force            Replace a symlink that currently points elsewhere
#       --unlink           Remove the symlinks this script would create (uninstall)
#       --dry-run          Print what would happen, change nothing
#   -h, --help             Show this help
#
set -euo pipefail

# --- Resolve repo root (directory this script lives in) ----------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR"

# --- Defaults ----------------------------------------------------------------
CHROMATIK_HOME="${CHROMATIK_HOME:-$HOME/Chromatik}"
NAMESPACE="$(basename "$REPO_ROOT")"
FORCE=0
UNLINK=0
DRY_RUN=0

# Chromatik content folders we know how to namespace. Only the ones that
# actually exist in this repo get linked.
CONTENT_DIRS=(
  Autosave
  Colors
  Data
  Fixtures
  Images
  Models
  Packages
  Presets
  Projects
  Scripts
  Views
  "MIDI Mappings"
)

usage() { sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

# --- Parse args --------------------------------------------------------------
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

# Expand a leading ~ in a user-supplied path.
CHROMATIK_HOME="${CHROMATIK_HOME/#\~/$HOME}"

if [[ ! -d "$CHROMATIK_HOME" ]]; then
  echo "error: Chromatik home not found: $CHROMATIK_HOME" >&2
  echo "       pass a custom path with -c/--chromatik or set \$CHROMATIK_HOME" >&2
  exit 1
fi

echo "Repo:      $REPO_ROOT"
echo "Chromatik: $CHROMATIK_HOME"
echo "Namespace: $NAMESPACE"
[[ $DRY_RUN -eq 1 ]] && echo "(dry run — no changes will be made)"
echo

run() { # echo + execute unless dry-run
  echo "  + $*"
  [[ $DRY_RUN -eq 1 ]] || "$@"
}

linked=0 skipped=0

for name in "${CONTENT_DIRS[@]}"; do
  src="$REPO_ROOT/$name"
  [[ -d "$src" ]] || continue          # repo doesn't have this folder — skip

  dest_parent="$CHROMATIK_HOME/$name"
  link="$dest_parent/$NAMESPACE"

  # ---- Unlink mode --------------------------------------------------------
  if [[ $UNLINK -eq 1 ]]; then
    if [[ -L "$link" ]]; then
      target="$(readlink "$link")"
      if [[ "$target" == "$src" ]]; then
        echo "$name: removing symlink"
        run rm "$link"
        linked=$((linked+1))
      else
        echo "$name: symlink points elsewhere ($target) — leaving it alone"
        skipped=$((skipped+1))
      fi
    else
      echo "$name: no symlink to remove"
    fi
    continue
  fi

  # ---- Link mode ----------------------------------------------------------
  if [[ -L "$link" ]]; then
    target="$(readlink "$link")"
    if [[ "$target" == "$src" ]]; then
      echo "$name: already linked ✓"
      skipped=$((skipped+1))
      continue
    fi
    if [[ $FORCE -eq 1 ]]; then
      echo "$name: replacing symlink (was -> $target)"
      run ln -sfn "$src" "$link"
      linked=$((linked+1))
    else
      echo "$name: symlink exists but points to $target"
      echo "        re-run with --force to replace it" >&2
      skipped=$((skipped+1))
    fi
    continue
  fi

  if [[ -e "$link" ]]; then
    # A real file/dir is sitting where the symlink should go — never clobber it.
    echo "$name: '$link' already exists and is NOT a symlink — skipping" >&2
    echo "        move/remove it manually if you want it namespaced" >&2
    skipped=$((skipped+1))
    continue
  fi

  echo "$name: linking"
  [[ -d "$dest_parent" ]] || run mkdir -p "$dest_parent"
  run ln -s "$src" "$link"
  linked=$((linked+1))
done

echo
if [[ $UNLINK -eq 1 ]]; then
  echo "Done. Removed: $linked, left alone: $skipped."
else
  echo "Done. Linked/updated: $linked, skipped: $skipped."
fi
