#!/bin/bash

readonly MAVEN_REPO="${HOME}/.m2"
readonly MAVEN_URL="https://dlcdn.apache.org/maven/maven-3/3.9.2/binaries/apache-maven-3.9.2-bin.tar.gz"
readonly MAVEN_ARCHIVE=$(basename ${MAVEN_URL})
readonly MAVEN_DIRECTORY=$(basename -s -bin.tar.gz ${MAVEN_URL})
readonly MAVEN_TMP_PATH="/var/tmp/${MAVEN_ARCHIVE}"
readonly MAVEN="${MAVEN_DIRECTORY}/bin/mvn"

readonly LINUX_X64_JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.7%2B7/OpenJDK17U-jdk_x64_linux_hotspot_17.0.7_7.tar.gz"
readonly LINUX_X64_JDK_ARCHIVE=$(basename ${LINUX_X64_JDK_URL})
readonly LINUX_X64_JDK_TMP_PATH="/var/tmp/${LINUX_X64_JDK_ARCHIVE}"

export JAVA_HOME=$(realpath $(dirname $0)/jdk-17.0.7+7)

# Change to the actual directory where this script is located since some of our commands need to be run there.
cd $(dirname $0)

function build_subproject() {
	if "${MAVEN}" dependency:get -Dartifact=$1 -o -DrepoUrl=file://${MAVEN_REPO}; then
		echo "$1 already in maven repo."
	else
		pushd $2
		mvn install
		popd
	fi
}

function install_jdk() {
	if [ $(uname) = "Linux" ] && [ $(uname -m) =  "x86_64" ]; then
		if [ -e "${JAVA_HOME}/bin/javac" ]; then
			echo "Using JDK at ${JAVA_HOME}"
		else
			echo "Installing Linux x86_64 JDK"
			wget -nc -O "${LINUX_X64_JDK_TMP_PATH}" "${LINUX_X64_JDK_URL}"
			tar -xf "${LINUX_X64_JDK_TMP_PATH}"
		fi
	else
		echo "Unknown architecture"
		exit 1
	fi
}

function install_maven() {
	if [ ! -e "${MAVEN}" ]; then
		wget -nc -O "${MAVEN_TMP_PATH}" "${MAVEN_URL}"
		tar -xf "${MAVEN_TMP_PATH}"
	fi
}

install_jdk
install_maven

build_subproject "heronarts.lx:lx:HEAD" "lib/lx"
build_subproject "org.processing:video:HEAD" "lib/processing-video"
build_subproject "heronarts.p3lx:p3lx:HEAD" "lib/p3lx"
build_subproject "ddf:minim:v2.2.2" "lib/minim"

"${MAVEN}" compile exec:java
