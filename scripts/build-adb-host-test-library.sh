#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"
: "${JAVA_HOME:?Set JAVA_HOME to your JDK}"
output_dir="${1:-.tools/adb-host-tests}"
mkdir -p "$output_dir"
"${CC:-cc}" -shared -fPIC -O2 -std=c11 -Wall -Wextra \
  -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/linux" \
  -I app/src/main/cpp/spake2/include \
  app/src/main/cpp/adb_spake_jni.c app/src/main/cpp/spake2/spake2.c \
  app/src/main/cpp/spake2/sha512.c -o "$output_dir/libbridgespake.so"
printf '%s\n' "Host JNI test library built: $output_dir/libbridgespake.so"
