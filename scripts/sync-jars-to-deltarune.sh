#!/usr/bin/env bash
# Builds both amanuensis jars and pushes them to the deltarune-en-galego-DEV repo.
set -euo pipefail

AMANUENSIS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_REPO="/home/mpereiroc/Documentos/deltarune-en-galego-DEV"
TARGET_BRANCH="main"

cd "$AMANUENSIS_DIR"

echo "== building linux jar =="
./mvnw -q clean package
LINUX_JAR=$(ls target/amanuensis-*.jar | grep -v -- '-windows' | grep -v -- '-original' | head -n1)
cp "$LINUX_JAR" "$TARGET_REPO/amanuensis.jar"

echo "== building windows jar =="
./mvnw -q -Pwindows clean package
WINDOWS_JAR=$(ls target/amanuensis-windows-*.jar | grep -v -- '-original' | head -n1)
cp "$WINDOWS_JAR" "$TARGET_REPO/amanuensis-windows.jar"

cd "$TARGET_REPO"

if git diff --quiet -- amanuensis.jar amanuensis-windows.jar && \
   git diff --cached --quiet -- amanuensis.jar amanuensis-windows.jar; then
    echo "== no changes to jars, nothing to commit =="
    exit 0
fi

git add amanuensis.jar amanuensis-windows.jar
git commit -m "novos jar amanuensis"
git push origin "$TARGET_BRANCH"

echo "== done: jars updated, committed and pushed =="
