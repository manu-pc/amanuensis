#!/usr/bin/env bash
# Xera message-map.json na raíz do repositorio de tradución.
#
# O mapa di que claves de que capítulos son A MESMA MENSAXE do xogo. Constrúese
# aliñando o código descompilado (ver MessageMapBuilder), non comparando textos:
# o mesmo texto aparece en sitios que non teñen nada que ver ("Check" en 68
# lugares), e a mesma clave pode ser unha mensaxe distinta noutro capítulo.
#
# IMPORTANTE: hai que xeralo sobre os strings.json EN INGLÉS. Sobre unha
# tradución a medias os capítulos xa non coinciden e o aliñamento perde parellas.
#
# uso: scripts/build-message-map.sh [raíz-do-repo]
#      (por defecto ./deltarune-en-galego-DEV)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="${1:-$here/deltarune-en-galego-DEV}"

if [[ ! -d "$repo/lang" ]]; then
  echo "non atopo $repo/lang" >&2
  exit 1
fi

cp="$("$here/scripts/message-map-cp.sh")"
exec java -Xmx2g -cp "$cp" com.local.map.MessageMapBuilder "$repo"
