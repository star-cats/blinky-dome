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
find_jdk() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
    echo "$JAVA_HOME"
    return 0
  fi
  # macOS ships a helper that knows where every installed JDK lives
  if [[ -x /usr/libexec/java_home ]]; then
    local home
    if home="$(/usr/libexec/java_home -v 21+ 2>/dev/null)"; then
      echo "$home"
      return 0
    fi
  fi
  if command -v javac >/dev/null 2>&1; then
    echo "$(dirname "$(dirname "$(command -v javac)")")"
    return 0
  fi
  return 1
}

if ! JAVA_HOME="$(find_jdk)"; then
  cat >&2 <<'EOF'
error: no JDK found (need Java 21 or newer, with javac).

Install one:
  macOS:   brew install openjdk@21
           sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
                        /Library/Java/JavaVirtualMachines/openjdk-21.jdk
  Linux:   sudo apt install openjdk-21-jdk
  Or download from https://adoptium.net/

Then re-run ./build.sh (or set JAVA_HOME by hand).
EOF
  exit 1
fi
export JAVA_HOME
echo "JDK:   $JAVA_HOME"

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
