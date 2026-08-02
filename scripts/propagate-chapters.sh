#!/usr/bin/env bash
# Propaga as traducións entre capítulos usando message-map.json.
#
# Por cada mensaxe repetida colle a aparición máis antiga que XA ESTEA TRADUCIDA
# (a primeira, en orde de capítulo, cuxo texto xa non é o inglés orixinal) e
# escríbea nas demais. Coller literalmente a de capítulo 1 revertería ao inglés
# o que estea traducido en capítulos posteriores e non nel.
#
# Por defecto só ENSINA o que faría. Escribe só con --aplicar.
# Todo o que escribe pasa polo rexistro de edicións, así que sube no seguinte push.
#
# uso: scripts/propagate-chapters.sh [raíz-do-repo] [--aplicar] [--forzar-primeira]
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

repo=""
flags=()
for a in "$@"; do
  case "$a" in
    --aplicar|--apply|--forzar-primeira|--force-first) flags+=("$a") ;;
    *) repo="$a" ;;
  esac
done
repo="${repo:-$here/deltarune-en-galego-DEV}"

if [[ ! -f "$repo/message-map.json" ]]; then
  echo "non hai $repo/message-map.json; executa antes scripts/build-message-map.sh" >&2
  exit 1
fi

cp="$("$here/scripts/message-map-cp.sh")"
exec java -Xmx2g -cp "$cp" com.local.map.PropagateChapters "$repo" "${flags[@]}"
