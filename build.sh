#!/bin/bash
# Без Maven (немає sudo для встановлення на цій машині) - напряму javac
# проти jar-ів LWJGL, завантажених у lib/.
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -encoding UTF-8 -cp "lib/*" -d out $(find src -name "*.java")
echo "Зібрано в out/"
