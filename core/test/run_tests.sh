#!/bin/sh
# Desktop JVM self-test of the core (needs JDK 17+ and python3 with `cryptography`).
set -e
cd "$(dirname "$0")/.."
OUT=$(mktemp -d); T=$(mktemp -d)
javac -d "$OUT" src/com/nxdump/core/*.java test/RunTest.java 2>/dev/null || \
  java -m jdk.compiler/com.sun.tools.javac.Main -d "$OUT" src/com/nxdump/core/*.java test/RunTest.java
python3 test/gen.py "$T"
check() {
  diff -r exp_base out_base && diff -r exp_merged out_merged && diff -r exp_base out_xci && diff -r exp_dlc out_dlc \
    && rm -rf exp_all && mkdir exp_all && cp -r exp_merged/. exp_all/ && cp -r exp_dlc/. exp_all/ && diff -r exp_all out_all
}
for J in 1 4; do
  (cd "$T" && rm -rf out_base out_merged out_xci out_dlc out_all)
  java -cp "$OUT" RunTest "$T" $J
  (cd "$T" && check && echo "jobs=$J output OK")
done
echo "ALL OK"
