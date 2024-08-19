#!/bin/bash

which wget > /dev/null
readonly HAS_WGET=$?

which realpath > /dev/null
readonly HAS_COREUTILS=$?

if [ $(uname) = "Linux" ]; then
	if [ ${HAS_WGET} -eq 1 ]; then
		echo "Wget is missing. Please install: sudo apt install wget"
		exit 1
	fi
elif [ $(uname) = "Darwin" ]; then
	if [ ${HAS_WGET} -eq 1 ]; then
		echo "wget is missing. Please install: brew install wget"
		echo "If you need to install brew: /bin/bash -c \"\$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\""
		exit 1
	fi
	if [ ${HAS_COREUTILS} -eq 1 ]; then
		echo "realpath is missing. Please install: brew install coreutils"
		echo "If you need to install brew: /bin/bash -c \"\$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\""
		exit 1
	fi
fi

readonly MAVEN_REPO="${HOME}/.m2"
readonly MAVEN_URL="https://dlcdn.apache.org/maven/maven-3/3.9.4/binaries/apache-maven-3.9.4-bin.tar.gz"
readonly MAVEN_ARCHIVE=$(basename ${MAVEN_URL})
readonly MAVEN_DIRECTORY=$(basename -s -bin.tar.gz ${MAVEN_URL})
readonly MAVEN_TMP_PATH="/var/tmp/${MAVEN_ARCHIVE}"
readonly MAVEN=$(realpath ${MAVEN_DIRECTORY}/bin/mvn)

readonly LINUX_X64_JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.7%2B7/OpenJDK17U-jdk_x64_linux_hotspot_17.0.7_7.tar.gz"
readonly LINUX_X64_JDK_ARCHIVE=$(basename ${LINUX_X64_JDK_URL})
readonly LINUX_X64_JDK_TMP_PATH="/var/tmp/${LINUX_X64_JDK_ARCHIVE}"

readonly OSX_ARM64_JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.7%2B7/OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.7_7.tar.gz"
readonly OSX_ARM64_JDK_ARCHIVE=$(basename ${OSX_ARM64_JDK_URL})
readonly OSX_ARM64_JDK_TMP_PATH="/var/tmp/${OSX_ARM64_JDK_ARCHIVE}"

readonly OSX_X64_JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.7%2B7/OpenJDK17U-jdk_x64_mac_hotspot_17.0.7_7.tar.gz"
readonly OSX_X64_JDK_ARCHIVE=$(basename ${OSX_X64_JDK_URL})
readonly OSX_X64_JDK_TMP_PATH="/var/tmp/${OSX_X64_JDK_ARCHIVE}"

if [ $(uname) = "Linux" ]; then
	export JAVA_HOME=$(realpath $(dirname $0)/jdk-17.0.7+7)
elif [ $(uname) = "Darwin" ]; then
	export JAVA_HOME=$(realpath $(dirname $0)/jdk-17.0.7+7/Contents/Home)
fi


# Change to the actual directory where this script is located since some of our commands need to be run there.
cd $(dirname $0)

# Checkout git submodules.
if [ ! -d lib/lx/git_submodule/src ]; then
	git submodule init
	git submodule update
fi

function build_subproject() {
	if "${MAVEN}" dependency:get -Dartifact=$1 -o -DrepoUrl=file://${MAVEN_REPO} > /dev/null; then
		echo "$1 already in maven repo."
	else
		pushd $2
		"${MAVEN}" install
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
	elif [ $(uname) = "Darwin" ] && [ $(uname -m) = "arm64" ]; then
		if [ -e "${JAVA_HOME}/bin/javac" ]; then
			echo "Using JDK at ${JAVA_HOME}"
		else
			echo "Installing OSX arm64 JDK"
			wget -nc -O "${OSX_ARM64_JDK_TMP_PATH}" "${OSX_ARM64_JDK_URL}"
			tar -xf "${OSX_ARM64_JDK_TMP_PATH}"
		fi
	elif [ $(uname) = "Darwin" ] && [ $(uname -m) = "x86_64" ]; then
		if [ -e "${JAVA_HOME}/bin/javac" ]; then
			echo "Using JDK at ${JAVA_HOME}"
		else
			echo "Installing OSX x86_64 JDK"
			wget -nc -O "${OSX_X64_JDK_TMP_PATH}" "${OSX_X64_JDK_URL}"
			tar -xf "${OSX_X64_JDK_TMP_PATH}"
		fi
	else
		echo "Unknown architecture"
		exit 1
	fi
	# Need to re-export JAVA_HOME since realpath needs the directory to actually exist.
	if [ $(uname) = "Linux" ]; then
		export JAVA_HOME=$(realpath $(dirname $0)/jdk-17.0.7+7)
	elif [ $(uname) = "Darwin" ]; then
		export JAVA_HOME=$(realpath $(dirname $0)/jdk-17.0.7+7/Contents/Home)
	fi
}

function install_maven() {
	if [ ! -e "${MAVEN}" ]; then
		wget -nc -O "${MAVEN_TMP_PATH}" "${MAVEN_URL}"
		tar -xf "${MAVEN_TMP_PATH}"
	fi
}

if [ "$1" != "--fast" ]; then
	install_jdk
	install_maven

	build_subproject "heronarts.lx:lx:HEAD" "lib/lx"
	build_subproject "org.processing:video:HEAD" "lib/processing-video"
	build_subproject "heronarts.p3lx:p3lx:HEAD" "lib/p3lx"
	build_subproject "ddf:minim:v2.2.2" "lib/minim"
else
	shift
fi

"${MAVEN}" compile
"${MAVEN}" exec:java -Dexec.args="$*"
