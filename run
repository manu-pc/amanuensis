#!/usr/bin/env bash
set -euo pipefail

./mvnw -q package

JAR=$(ls -1 target/*.jar 2>/dev/null | grep -v '\-original' | head -n1 || true)

echo "executando $JAR"
java -jar "$JAR"
