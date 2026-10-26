#!/bin/sh
# Desktop JVM self-test of the core (needs JDK 17+ and python3 with `cryptography`).
set -e
cd "$(dirname "$0")/.."
OUT=$(mktemp -d); T=$(mktemp -d)
javac -d "$OUT" src/com/nxdump/core/*.java test/RunTest.java 2>/dev/null || \
  java -m jdk.compiler/com.sun.tools.javac.Main -d "$OUT" src/com/nxdump/core/*.java test/RunTest.java
python3 test/gen.py "$T"
java -cp "$OUT" RunTest "$T"
cd "$T"
diff -r exp_base out_base && diff -r exp_merged out_merged && diff -r exp_base out_xci && echo "ALL OK"
