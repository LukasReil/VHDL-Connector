#!/usr/bin/env bash
# Builds (if needed) and runs the application
set -e
cd "$(dirname "$0")"
if [ ! -d out ]; then
    ./build.sh
fi
java -cp out vhdlconnector.Main
