#!/bin/bash
# rchat on a sprite: the environment from .env (the API key, RCHAT_MODEL,
# RCHAT_TOKEN), then the port the sprite URL routes to and the work
# directory, then the uberjar on babashka. Pushed by `bb deploy <sprite>`.
set -a
[ -f /home/sprite/rchat/.env ] && . /home/sprite/rchat/.env
set +a
export PORT=8080
export RCHAT_WORK=/home/sprite/rchat/work
cd /home/sprite/rchat
exec /home/sprite/.local/bin/bb /home/sprite/rchat/rchat.jar
