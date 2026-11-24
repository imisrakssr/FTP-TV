#!/usr/bin/env bash
# Generates binary assets (launcher icons, TV banner, signing keystore) so the repo only holds text files.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -c "import PIL" 2>/dev/null || python3 -m pip install --quiet pillow
python3 ci/logo.py app/src/main/res
if [ ! -f app/ftptv.keystore ]; then
  base64 -d ci/keystore.b64 > app/ftptv.keystore
fi
echo "Assets ready."
