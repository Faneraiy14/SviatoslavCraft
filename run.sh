#!/bin/bash
set -e
cd "$(dirname "$0")"
java -cp "out:lib/*" com.sviatoslav.craft.game.SviatoslavCraft
