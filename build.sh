#!/bin/bash
# Без Maven - напряму javac проти jar-ів LWJGL у lib/.
#   ./build.sh         - зібрати в out/ і зробити voxelin-api.jar (API для модів)
#   ./build.sh test    - ще й прогнати тести (ядро, мешер) у тимчасовій теці
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -encoding UTF-8 -cp "lib/*" -d out $(find src -name "*.java")
echo "Зібрано в out/"
# API модів - окремий jar (тільки voxelin/api), проти нього компілюються моди
jar cf voxelin-api.jar -C out voxelin/api
echo "voxelin-api.jar"
if [ "$1" = "test" ]; then
    T="$(mktemp -d "${TMPDIR:-/tmp}/voxelin-test.XXXXXX")"
    trap 'echo "(тимчасова тека тестів: $T)"' EXIT
    javac -encoding UTF-8 -cp "out:lib/*" -d "$T/classes" test/*.java
    OUT="$PWD/out"
    for t in VoxelinSettingsTest VoxelinCoreTest VoxelinMeshTest VoxelinLightTest VoxelinSurvivalTest VoxelinMiningTest VoxelinMenuTest VoxelinCraftTest VoxelinFurnaceTest VoxelinMobTest VoxelinChestTest VoxelinStructureTest VoxelinTradeTest VoxelinVillageTest VoxelinDefenseTest VoxelinRulesTest VoxelinArmorTest VoxelinEndTest VoxelinDragonTest VoxelinStoryTest VoxelinInfectionTest VoxelinBetweenTest VoxelinAudioTest VoxelinLookTest VoxelinProgressTest; do
        ( mkdir -p "$T/run-$t" && cd "$T/run-$t" && java -cp "$OUT:$PWD/../classes:$OLDPWD/lib/*" "$t" "$OUT" )
    done
fi
if [ "$1" = "balance" ]; then
    T="$(mktemp -d "${TMPDIR:-/tmp}/voxelin-balance.XXXXXX")"
    javac -encoding UTF-8 -cp "out:lib/*" -d "$T/classes" test/VoxelinBalanceReport.java
    OUT="$PWD/out"; REPORT="$PWD/docs/BALANCE.md"
    ( cd "$T" && java -cp "$OUT:$T/classes:$OLDPWD/lib/*" VoxelinBalanceReport "$REPORT" )
fi
