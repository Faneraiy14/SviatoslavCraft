#!/bin/bash
set -e
cd "$(dirname "$0")"
java -cp "out:lib/*" voxelin.client.Voxelin "$@"
