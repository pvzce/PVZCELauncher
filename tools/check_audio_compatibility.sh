#!/usr/bin/env bash
set -eu
repo=$(cd "$(dirname "$0")/.." && pwd)
check_root=${PVZCE_CHECK_DIR:-"$repo/build/audio-compatibility-checks"}
java_command=${PVZCE_JAVA:-java}
mkdir -p "$check_root/classes" "$check_root/tmp"
"$java_command" -Djava.io.tmpdir="$check_root/tmp" -m jdk.compiler/com.sun.tools.javac.Main \
    -source 17 -target 17 -Xlint:-options -d "$check_root/classes" \
    "$repo/FCL/src/main/java/com/tungsten/fcl/generic/LwjglRuntimeOptions.java" \
    "$repo/FCL/src/main/java/com/tungsten/fcl/generic/GameSessionLog.java" \
    "$repo/tools/AudioCompatibilityChecks.java"
"$java_command" -Djava.io.tmpdir="$check_root/tmp" -cp "$check_root/classes" AudioCompatibilityChecks
