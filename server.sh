#!/bin/bash
# Сервер без вікна: ./server.sh [--world НАЗВА] [--survival|--creative] [--port N] [--code КОД] ... (--help)
set -e
cd "$(dirname "$0")"
java -Djava.awt.headless=true -cp "out:lib/*" voxelin.server.DedicatedServer "$@"
