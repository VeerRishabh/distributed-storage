#!/usr/bin/env bash
# Compiles to ./out. Uses javac if present, otherwise the JDK compiler module bundled with `java`.
set -euo pipefail; cd "$(dirname "$0")/.."
rm -rf out; mkdir out
if command -v javac >/dev/null; then javac -d out src/*.java
else java -m jdk.compiler/com.sun.tools.javac.Main -d out src/*.java; fi
echo "built -> out/"
