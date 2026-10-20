#!/bin/bash
# Збірка прикладу мода. Спершу зберіть гру в корені репозиторію (./build.sh) - вона робить voxelin-api.jar.
set -e
cd "$(dirname "$0")"
API=../../voxelin-api.jar
[ -f "$API" ] || { echo "Нема $API - спершу ./build.sh у корені"; exit 1; }
W="$(mktemp -d "${TMPDIR:-/tmp}/hello-mod.XXXXXX")"
javac -encoding UTF-8 -cp "$API" -d "$W" $(find src -name '*.java')
printf 'Manifest-Version: 1.0\nVoxelin-Mod: hello.HelloMod\nVoxelin-Mod-Id: hello\nVoxelin-Api: 2\n' > "$W/manifest.txt"
jar cfm hello-mod.jar "$W/manifest.txt" -C "$W" hello
echo "hello-mod.jar - поклади в теку mods/ гри"
