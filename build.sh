#!/bin/bash
# Без Maven - напряму javac проти jar-ів LWJGL у lib/.
#   ./build.sh         - зібрати в out/ і зробити voxelin-api.jar (API для модів)
#   ./build.sh test    - ще й прогнати тести (ядро, мешер) у тимчасовій теці
#   ./build.sh api-snapshot - прийняти зміни API модів (docs/api-signatures.txt)
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -encoding UTF-8 -cp "lib/*" -d out $(find src -name "*.java")
# ресурси (шрифт інтерфейсу з ліцензією) - поруч із класами, гра бере їх через getResource
cp -r res/. out/
# стартовий клас під Java 8: на старій Java пояснює, що потрібна 21, замість UnsupportedClassVersionError
javac -encoding UTF-8 --release 8 -nowarn -Xlint:-options -d out boot/voxelin/Boot.java
echo "Зібрано в out/"
# API модів - окремий jar (тільки voxelin/api), проти нього компілюються моди
jar cf voxelin-api.jar -C out voxelin/api
echo "voxelin-api.jar"
if [ "$1" = "test" ]; then
    T="$(mktemp -d "${TMPDIR:-/tmp}/voxelin-test.XXXXXX")"
    trap 'echo "(тимчасова тека тестів: $T)"' EXIT
    javac -encoding UTF-8 -cp "out:lib/*" -d "$T/classes" test/*.java
    OUT="$PWD/out"
    for t in VoxelinSettingsTest VoxelinCoreTest VoxelinMeshTest VoxelinLightTest VoxelinSurvivalTest VoxelinMiningTest VoxelinMenuTest VoxelinCreativeTest VoxelinFlyTest VoxelinCraftTest VoxelinFurnaceTest VoxelinMobTest VoxelinPathTest VoxelinMobSkinTest VoxelinChestTest VoxelinStructureTest VoxelinTempleTest VoxelinTradeTest VoxelinVillageTest VoxelinVillageLifeTest VoxelinDefenseTest VoxelinRulesTest VoxelinBoardsTest VoxelinBowTest VoxelinSkeletonTest VoxelinNetheriteTest VoxelinNetherTest VoxelinMythTest VoxelinArmorTest VoxelinEndTest VoxelinDragonTest VoxelinStoryTest VoxelinNotesTest VoxelinInfectionTest VoxelinBetweenTest VoxelinAudioTest VoxelinLookTest VoxelinMinimapTest VoxelinCodeTest VoxelinBlocksTest VoxelinShareTest VoxelinNetTest VoxelinServerTest VoxelinApiTest VoxelinContentTest VoxelinCrashTest VoxelinUpdateTest VoxelinProgressTest VoxelinScriptTest; do
        ( mkdir -p "$T/run-$t" && cd "$T/run-$t" && java -cp "$PWD/../classes:$OUT:$OLDPWD/lib/*" "$t" "$OUT" )
    done
fi
if [ "$1" = "api-snapshot" ]; then
    # Свідомо прийняти зміни публічного API модів: знімок сигнатур, з яким звіряє VoxelinApiTest
    T="$(mktemp -d "${TMPDIR:-/tmp}/voxelin-api.XXXXXX")"
    javac -encoding UTF-8 -cp "out:lib/*" -d "$T" test/VoxelinApiTest.java
    java -cp "out:$T:lib/*" VoxelinApiTest "$PWD/out" --write docs/api-signatures.txt
fi
if [ "$1" = "balance" ]; then
    T="$(mktemp -d "${TMPDIR:-/tmp}/voxelin-balance.XXXXXX")"
    javac -encoding UTF-8 -cp "out:lib/*" -d "$T/classes" test/VoxelinBalanceReport.java
    OUT="$PWD/out"; REPORT="$PWD/docs/BALANCE.md"
    ( cd "$T" && java -cp "$OUT:$T/classes:$OLDPWD/lib/*" VoxelinBalanceReport "$REPORT" )
fi
