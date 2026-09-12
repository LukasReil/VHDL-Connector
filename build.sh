#!/usr/bin/env bash
# Compiles the project into ./out
set -e
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
find src -name "*.java" > /tmp/vhdlconnector_sources.txt
javac -d out @/tmp/vhdlconnector_sources.txt
rm -f /tmp/vhdlconnector_sources.txt
echo "Build OK -> out/"
