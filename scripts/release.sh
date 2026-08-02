#!/usr/bin/env bash
# Publica unha versión nova de Amanuensis para que as apps dos tradutores a collan soas.
#
# Que fai:
#   1. constrúe os dous jars (Linux e Windows) coa versión do pom
#   2. crea unha release en GitHub e sobe os jars como assets
#   3. escribe update.json neste repo (o do código) e faille commit+push
#
# Publícase AQUÍ e non no repositorio de tradución porque aquel é privado: nin
# raw.githubusercontent.com nin os assets dunha release privada responden sen
# token. Neste, que é público, buscar e baixar actualizacións non precisa
# credenciais de ningún tipo, así que funciona antes de iniciar sesión e cun
# token caducado.
#
# O update.json son uns 500 bytes; os jars van como assets da release, así que
# ningún repositorio medra 26 MB por versión.
#
# Requisitos: gh (autenticado) e permiso de push neste repositorio.
#
# uso: scripts/release.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$here"   # a app publícase no seu propio repositorio, que é público

if ! command -v gh >/dev/null; then
  echo "fai falta gh (GitHub CLI) autenticado: https://cli.github.com" >&2
  exit 1
fi

cd "$here"
version="$(./mvnw -q -o help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1)"
if [[ -z "$version" || "$version" == *ERROR* ]]; then
  version="$(grep -m1 -oP '(?<=<version>)[^<]+' pom.xml)"
fi
tag="v$version"
echo "== versión $version =="

# Se a etiqueta xa existe, alguén xa publicou esta versión: hai que subir o pom
# antes, senón as apps non verían diferenza ningunha e non actualizarían.
if gh release view "$tag" --repo "$(git -C "$repo" remote get-url origin)" >/dev/null 2>&1; then
  echo "a release $tag xa existe; sobe a versión no pom.xml primeiro" >&2
  exit 1
fi

echo "== construíndo jar de Linux =="
./mvnw -q clean package
linux_jar="target/amanuensis-$version.jar"
[[ -f "$linux_jar" ]] || linux_jar="$(ls target/amanuensis-*.jar | grep -v -- '-windows' | grep -v -- '-original' | head -1)"

echo "== construíndo jar de Windows =="
./mvnw -q -Pwindows clean package
win_jar="target/amanuensis-windows-$version.jar"
[[ -f "$win_jar" ]] || win_jar="$(ls target/amanuensis-windows-*.jar | grep -v -- '-original' | head -1)"

# O jar de Linux bórrase co `clean` do build de Windows: reconstrúese e gárdase aparte.
stage="$here/target/release"
rm -rf "$stage" && mkdir -p "$stage"
cp "$win_jar" "$stage/amanuensis-windows.jar"
./mvnw -q clean package
cp "$(ls target/amanuensis-*.jar | grep -v -- '-windows' | grep -v -- '-original' | head -1)" \
   "$stage/amanuensis.jar"

sha() { sha256sum "$1" | cut -d' ' -f1; }
size() { stat -c%s "$1"; }

origin="$(git -C "$repo" remote get-url origin)"
slug="$(printf '%s' "$origin" | sed -E 's#.*github\.com[:/]##; s#\.git$##')"
base_url="https://github.com/$slug/releases/download/$tag"

echo "== creando release $tag en $slug =="
notes="${RELEASE_NOTES:-Versión $version}"
gh release create "$tag" \
  "$stage/amanuensis.jar" "$stage/amanuensis-windows.jar" \
  --repo "$slug" --title "$tag" --notes "$notes"

cat > "$repo/update.json" <<EOF
{
    "version": "$version",
    "notes": "$notes",
    "artifacts": {
        "linux": {
            "file": "amanuensis.jar",
            "url": "$base_url/amanuensis.jar",
            "sha256": "$(sha "$stage/amanuensis.jar")",
            "size": $(size "$stage/amanuensis.jar")
        },
        "windows": {
            "file": "amanuensis-windows.jar",
            "url": "$base_url/amanuensis-windows.jar",
            "sha256": "$(sha "$stage/amanuensis-windows.jar")",
            "size": $(size "$stage/amanuensis-windows.jar")
        }
    }
}
EOF

cd "$repo"
branch="$(git rev-parse --abbrev-ref HEAD)"
git add update.json
git -c user.name=manu-pc -c user.email=pereirocondemanuel@gmail.com \
    commit --author="manu-pc <pereirocondemanuel@gmail.com>" -m "amanuensis $version"
git push origin "$branch"

echo
echo "publicado. As apps abertas verano no seguinte ciclo (30 min) ou con «buscar actualizacións»."
