#!/usr/bin/env bash
set -eu
repo=$(cd "$(dirname "$0")/.." && pwd)
check_root=${PVZCE_CHECK_DIR:-"$repo/build/pvzce-profile-checks"}
java_command=${PVZCE_JAVA:-java}
mkdir -p "$check_root/classes" "$check_root/tmp"
"$java_command" -Djava.io.tmpdir="$check_root/tmp" -m jdk.compiler/com.sun.tools.javac.Main \
    -d "$check_root/classes" "$repo/FCL/src/main/java/com/tungsten/fcl/generic/JavaLaunchSpec.java" \
    "$repo/FCL/src/main/java/com/tungsten/fcl/generic/PvzceProfile.java" \
    "$repo/FCL/src/main/java/com/tungsten/fcl/generic/GameContentPaths.java" \
    "$repo/FCL/src/main/java/com/tungsten/fcl/generic/GameInputState.java" "$repo/tools/PvzceProfileChecks.java"
"$java_command" -Djava.io.tmpdir="$check_root/tmp" -cp "$check_root/classes" PvzceProfileChecks
