#!/usr/bin/env bash
# Classpath para as ferramentas de consola de com.local.map.
#
# Non se usa dependency:build-classpath porque ese plugin pode non estar no
# repositorio local e entón non se pode traballar sen rede. As ferramentas só
# precisan as clases compiladas e gson (nada de JavaFX nin JGit), así que se
# monta a man: target/classes + o gson que xa baixou Maven.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$root/mvnw" -q -o compile >&2

gson="$(find "${HOME}/.m2/repository/com/google/code/gson/gson" \
        -name 'gson-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null \
        | sort | tail -1)"

if [[ -z "$gson" ]]; then
  # sen gson solto: o fat-jar tamén o leva dentro
  gson="$(ls "$root"/target/amanuensis-*.jar 2>/dev/null | grep -v original | head -1 || true)"
fi

if [[ -z "$gson" ]]; then
  echo "non atopo gson nin o fat-jar; executa ./mvnw package unha vez" >&2
  exit 1
fi

echo "$root/target/classes:$gson"
