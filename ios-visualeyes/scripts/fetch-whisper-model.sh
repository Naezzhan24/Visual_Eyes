#!/bin/bash
# Downloads the on-device Whisper model (openai_whisper-base.en, ~146MB)
# into WhisperModels/ so it's bundled into the app and works offline from
# the first launch. Run once on the Mac before building:
#   bash scripts/fetch-whisper-model.sh
# Without it the app still works; WhisperKit downloads the model the first
# time the phone is online.
set -euo pipefail

cd "$(dirname "$0")/.."
MODEL=openai_whisper-base.en
DEST="WhisperModels/$MODEL"
HF=https://huggingface.co
mkdir -p "$DEST"

echo "Listing $MODEL files..."
FILES=$(curl -fsSL "$HF/api/models/argmaxinc/whisperkit-coreml/tree/main/$MODEL?recursive=true" \
  | python3 -c 'import sys,json; [print(f["path"]) for f in json.load(sys.stdin) if f["type"]=="file"]')

for path in $FILES; do
  rel="${path#$MODEL/}"
  mkdir -p "$DEST/$(dirname "$rel")"
  echo "  $rel"
  curl -fsSL -o "$DEST/$rel" "$HF/argmaxinc/whisperkit-coreml/resolve/main/$path"
done

# The tokenizer lives in OpenAI's repo; WhisperKit looks for it next to the model.
for f in tokenizer.json tokenizer_config.json; do
  echo "  $f"
  curl -fsSL -o "$DEST/$f" "$HF/openai/whisper-base.en/resolve/main/$f"
done

du -sh "$DEST"
echo "Done. Re-run xcodegen generate so the model is bundled."
