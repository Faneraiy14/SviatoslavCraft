#!/bin/bash
# Той самий принцип, що й "npm run ship" у fl-launcher-code (Sviatoslav
# попросив - "система як з лаунчером, щоб по 1 команді чистилась dist,
# робився новий реліз і заливався на GitHub"): одна команда - чистить
# dist/, збирає, пакує в архів, тегує коміт і публікує GitHub Release в
# ОКРЕМОМУ публічному репозиторії voxelin-releases (лише готові збірки,
# БЕЗ вихідного коду - джерело лишається тут, у SviatoslavCraft).
set -e
cd "$(dirname "$0")"

VERSION="$1"
if [ -z "$VERSION" ]; then
    echo "Використання: ./ship.sh <версія, напр. 0.1.0>"
    exit 1
fi

RELEASES_REPO="Faneraiy14/voxelin-releases"
# lib/ тепер містить і Linux-, і Windows-natives LWJGL одночасно (сама
# бібліотека автовизначає ОС і бере потрібний файл під час запуску,
# перевірено живцем - обидва набори natives мирно співіснують на
# класпасі) - тому один архів працює на обох ОС, суфікс "-linux"
# прибрано.
ARTIFACT="Voxelin-v${VERSION}"
DIST="dist"

echo "== Очищення $DIST/ =="
rm -rf "$DIST"
mkdir -p "$DIST"

echo "== Збірка =="
./build.sh

echo "== Пакування =="
PKG="$DIST/$ARTIFACT"
mkdir -p "$PKG"
cp -r out "$PKG/"
cp -r lib "$PKG/"
cat > "$PKG/run.sh" << 'RUNEOF'
#!/bin/bash
set -e
cd "$(dirname "$0")"
java -cp "out:lib/*" voxelin.client.Voxelin
RUNEOF
chmod +x "$PKG/run.sh"
cat > "$PKG/run.bat" << 'RUNEOF'
@echo off
cd /d "%~dp0"
java -cp "out;lib/*" voxelin.client.Voxelin
RUNEOF
cat > "$PKG/server.sh" << 'RUNEOF'
#!/bin/bash
# Сервер без вікна (налаштування - server.properties, створюється з першим запуском; --help - параметри)
set -e
cd "$(dirname "$0")"
java -Djava.awt.headless=true -cp "out:lib/*" voxelin.server.DedicatedServer "$@"
RUNEOF
chmod +x "$PKG/server.sh"
cat > "$PKG/server.bat" << 'RUNEOF'
@echo off
cd /d "%~dp0"
java -Djava.awt.headless=true -cp "out;lib/*" voxelin.server.DedicatedServer %*
RUNEOF
# для модерів: API (компілювати моди проти нього) і приклади поруч з архівом гри
cp voxelin-api.jar "$DIST/voxelin-api.jar"
EX="$DIST/examples-src"; mkdir -p "$EX/hello-mod"
cp -r examples/hello-mod/src "$EX/hello-mod/"
sed 's|../../voxelin-api.jar|voxelin-api.jar|' examples/hello-mod/build.sh > "$EX/hello-mod/build.sh"
cp examples/hello-script/main.nx "$EX/main.nx"
( cd "$EX" && zip -r -q ../voxelin-examples.zip . )
( cd "$DIST" && zip -r -q "$ARTIFACT.zip" "$ARTIFACT" )

echo "== Тег v$VERSION =="
git tag "v$VERSION"
git push origin "v$VERSION"

echo "== Реліз на GitHub ($RELEASES_REPO) =="
gh release create "v$VERSION" "$DIST/$ARTIFACT.zip" "$DIST/voxelin-api.jar" "$DIST/voxelin-examples.zip" \
    --repo "$RELEASES_REPO" \
    --title "v$VERSION" \
    --notes "Voxelin v$VERSION (Linux + Windows, x64, потрібна Java 21)"

echo "== Готово: v$VERSION =="
echo "https://github.com/$RELEASES_REPO/releases/tag/v$VERSION"
