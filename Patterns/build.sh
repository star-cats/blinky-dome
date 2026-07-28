#!/usr/bin/env bash
#
# build.sh — compile the custom patterns into a Chromatik package (.jar)
#
# Output: Patterns/blinky-dome/blinky-dome-patterns.jar
#
# Once ./install.sh has symlinked that folder into Chromatik, this is the only
# command you need to re-run after a code change. Restart Chromatik to pick it up.
#
# Requires a JDK 21+. It does NOT require Maven to be installed — if `mvn` isn't
# on your PATH this script downloads a pinned copy into Patterns/.tools/ once and
# reuses it from then on.
#
# Usage:
#   ./build.sh [extra maven args...]
#
# Examples:
#   ./build.sh              # normal build
#   ./build.sh -X           # verbose maven output, for debugging
#   ./build.sh clean package
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$SCRIPT_DIR/.tools"
MAVEN_VERSION="3.9.9"

# --- Find a JDK ---------------------------------------------------------------
# Note: the JVM bundled inside Chromatik.app is a stripped runtime with no
# compiler, so it can't be used here. You need a real JDK.
MIN_JDK=21

# Is this a real JDK, or macOS lying to us?
#
# When no JDK is installed, macOS still ships stub binaries at /usr/bin/java,
# /usr/bin/javac, etc. (all hardlinks to one placeholder). They exist and are
# executable, so a naive `command -v javac` check happily concludes JAVA_HOME=/usr
# — and then Maven launches the stub and BLOCKS FOREVER instead of failing. Every
# real JDK image ships a `release` file at its root; the stub directory doesn't.
# That's what we test, and we deliberately test it without executing anything.
is_jdk() {
  local home="${1:-}"
  [[ -n "$home" && -x "$home/bin/javac" && -r "$home/release" ]]
}

# Major version, read out of the release file. Handles both the modern scheme
# (21.0.11 -> 21) and the legacy one (1.8.0_292 -> 8).
jdk_major() {
  local v
  v="$(sed -n 's/^JAVA_VERSION="\(.*\)"/\1/p' "$1/release" 2>/dev/null | head -1)"
  case "$v" in
    "")  return ;;
    1.*) echo "${v#1.}" | cut -d. -f1 ;;
    *)   echo "$v" | cut -d. -f1 ;;
  esac
}

find_jdk() {
  local candidates=() home

  # Explicit override always wins
  candidates+=("${JAVA_HOME:-}")

  # macOS helper that knows where properly-installed JDKs live
  if [[ -x /usr/libexec/java_home ]]; then
    home="$(/usr/libexec/java_home -v "$MIN_JDK+" 2>/dev/null)" && candidates+=("$home")
  fi

  # Homebrew's openjdk is keg-only: it is NOT on PATH and NOT registered with
  # java_home unless you also make the /Library/Java symlink. Look directly.
  local brew_prefix
  for brew_prefix in /opt/homebrew /usr/local; do
    candidates+=("$brew_prefix/opt/openjdk@$MIN_JDK/libexec/openjdk.jdk/Contents/Home")
    candidates+=("$brew_prefix/opt/openjdk/libexec/openjdk.jdk/Contents/Home")
  done

  # Linux distro layout
  for home in /usr/lib/jvm/*; do
    candidates+=("$home")
  done

  # Whatever javac is on PATH (validated like everything else, so the macOS
  # stub at /usr/bin/javac gets rejected here rather than hanging the build)
  if command -v javac >/dev/null 2>&1; then
    candidates+=("$(dirname "$(dirname "$(command -v javac)")")")
  fi

  local too_old=""
  for home in "${candidates[@]}"; do
    is_jdk "$home" || continue
    local major
    major="$(jdk_major "$home")"
    if [[ -n "$major" && "$major" -ge "$MIN_JDK" ]]; then
      echo "$home"
      return 0
    fi
    too_old="$home (Java ${major:-?})"
  done

  [[ -n "$too_old" ]] && echo "TOO_OLD:$too_old"
  return 1
}

JDK_RESULT="$(find_jdk)" || {
  if [[ "$JDK_RESULT" == TOO_OLD:* ]]; then
    echo "error: found a JDK but it's too old: ${JDK_RESULT#TOO_OLD:}" >&2
    echo "       this package needs Java $MIN_JDK or newer." >&2
  else
    echo "error: no JDK found (need Java $MIN_JDK or newer, with javac)." >&2
    if command -v javac >/dev/null 2>&1; then
      echo "       (/usr/bin/javac exists, but it's the macOS placeholder stub," >&2
      echo "        not a real compiler — that's why this looks confusing.)" >&2
    fi
  fi
  cat >&2 <<EOF

Install one:
  macOS:   brew install openjdk@$MIN_JDK
  Linux:   sudo apt install openjdk-$MIN_JDK-jdk
  Or download from https://adoptium.net/

Then re-run ./build.sh. If it still isn't found, point at it explicitly:
  JAVA_HOME=/path/to/jdk ./build.sh
EOF
  exit 1
}
JAVA_HOME="$JDK_RESULT"
export JAVA_HOME
echo "JDK:   $JAVA_HOME (Java $(jdk_major "$JAVA_HOME"))"

# --- Find (or fetch) Maven ----------------------------------------------------
if command -v mvn >/dev/null 2>&1; then
  MVN="$(command -v mvn)"
else
  MVN="$TOOLS_DIR/apache-maven-$MAVEN_VERSION/bin/mvn"
  if [[ ! -x "$MVN" ]]; then
    echo "Maven not found on PATH — downloading Apache Maven $MAVEN_VERSION into"
    echo "  $TOOLS_DIR (one time, ~9MB; delete that folder to undo)"
    mkdir -p "$TOOLS_DIR"
    curl -fsSL -o "$TOOLS_DIR/maven.tar.gz" \
      "https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz"
    tar xzf "$TOOLS_DIR/maven.tar.gz" -C "$TOOLS_DIR"
    rm -f "$TOOLS_DIR/maven.tar.gz"
  fi
fi
echo "Maven: $MVN"
echo

# --- Build --------------------------------------------------------------------
# The first build downloads the LX libraries from Maven Central; later builds are
# offline-fast.
if [[ $# -gt 0 ]]; then
  "$MVN" -f "$SCRIPT_DIR/pom.xml" "$@"
else
  "$MVN" -f "$SCRIPT_DIR/pom.xml" clean package
fi

echo
echo "Built:"
ls -1 "$SCRIPT_DIR"/blinky-dome/*.jar
