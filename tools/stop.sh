#!/bin/sh
# Stops every Tessera window started with ./kotlin run or tools/run.sh (their JVM arguments live in
# an argument file under the build directory, so the main class name is not on the command line).
for p in $(ps -eo pid,command | grep -E "[t]essera(-run\.[A-Za-z0-9]+|/build)/temp/java-args" | awk '{print $1}'); do kill "$p"; done
