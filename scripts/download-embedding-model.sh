#!/usr/bin/env bash
# Downloads the default embedding model (intfloat/multilingual-e5-small) into models/.
#
# The ONNX export and its tokenizer are published in the official model repository, so the
# `optimum-cli export onnx` step is not needed. The files are ~466 MB in total and therefore live
# outside version control; run this once before starting the application.
#
# Usage: scripts/download-embedding-model.sh [target-dir]

set -euo pipefail

REPO="intfloat/multilingual-e5-small"
TARGET="${1:-$(dirname "$0")/../models/multilingual-e5-small}"

mkdir -p "$TARGET"

for FILE in model.onnx tokenizer.json; do
    if [[ -s "$TARGET/$FILE" ]]; then
        echo "$FILE already present, skipping"
        continue
    fi
    echo "Downloading $FILE ..."
    curl -fL --progress-bar \
        "https://huggingface.co/${REPO}/resolve/main/onnx/${FILE}" \
        -o "$TARGET/$FILE.part"
    mv "$TARGET/$FILE.part" "$TARGET/$FILE"
done

echo "Model ready in $TARGET"
ls -lh "$TARGET"
