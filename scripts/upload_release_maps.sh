#!/usr/bin/env bash
set -e

TAG="v1.0.0-maps"
TITLE="Mapas Offline por Municipio de Cuba (Protomaps PMTiles)"
NOTES="Archivos .pmtiles individuales por municipio para la app Conexion."
REPO="athenacoree/conexion-"

echo "Preparing municipality .pmtiles assets matching assetFilename structure..."
mkdir -p release_assets
rm -rf release_assets/*

for f in mapas/*/*.pmtiles; do
    if [ -f "$f" ]; then
        prov=$(basename "$(dirname "$f")")
        muni=$(basename "$f")
        target="release_assets/map_${prov}_${muni}"
        target=$(echo "$target" | tr ' ' '_')
        cp "$f" "$target"
    fi
done

ASSET_COUNT=$(ls -1 release_assets/*.pmtiles 2>/dev/null | wc -l)
echo "Prepared $ASSET_COUNT assets in release_assets/"

TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"

if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    echo "Creating GitHub Release $TAG and uploading $ASSET_COUNT municipality .pmtiles assets with gh CLI..."
    gh release create "$TAG" release_assets/*.pmtiles --repo "$REPO" --title "$TITLE" --notes "$NOTES" || gh release upload "$TAG" release_assets/*.pmtiles --repo "$REPO" --clobber
elif [ -n "$TOKEN" ]; then
    echo "Creating GitHub Release $TAG using GitHub REST API..."
    # Create release
    RELEASE_RESPONSE=$(curl -s -X POST \
      -H "Authorization: token $TOKEN" \
      -H "Accept: application/vnd.github.v3+json" \
      "https://api.github.com/repos/$REPO/releases" \
      -d "{\"tag_name\":\"$TAG\",\"name\":\"$TITLE\",\"body\":\"$NOTES\",\"draft\":false,\"prerelease\":false}")

    UPLOAD_URL=$(echo "$RELEASE_RESPONSE" | grep -o '"upload_url": *"[^"]*"' | head -n 1 | cut -d'"' -f4 | sed 's/{?name,label}//')

    if [ -z "$UPLOAD_URL" ]; then
        # Try getting existing release
        RELEASE_RESPONSE=$(curl -s -H "Authorization: token $TOKEN" "https://api.github.com/repos/$REPO/releases/tags/$TAG")
        UPLOAD_URL=$(echo "$RELEASE_RESPONSE" | grep -o '"upload_url": *"[^"]*"' | head -n 1 | cut -d'"' -f4 | sed 's/{?name,label}//')
    fi

    if [ -n "$UPLOAD_URL" ]; then
        echo "Uploading assets to $UPLOAD_URL..."
        for asset in release_assets/*.pmtiles; do
            filename=$(basename "$asset")
            echo "Uploading $filename..."
            curl -s -X POST \
              -H "Authorization: token $TOKEN" \
              -H "Content-Type: application/octet-stream" \
              --data-binary "@$asset" \
              "$UPLOAD_URL?name=$filename" > /dev/null
        done
        echo "Assets uploaded successfully via REST API."
    else
        echo "Error: Could not retrieve release upload URL."
        exit 1
    fi
else
    echo "Notice: Neither logged-in gh CLI nor GH_TOKEN/GITHUB_TOKEN found in environment."
    echo "To upload assets when token is provided, run: GH_TOKEN=<token> ./scripts/upload_release_maps.sh"
fi

echo "Finished scripts/upload_release_maps.sh execution."
