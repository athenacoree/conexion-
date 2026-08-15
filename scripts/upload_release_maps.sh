#!/usr/bin/env bash
set -e

TAG="v1.0.0-maps"
TITLE="Mapas Offline por Municipio de Cuba (Protomaps PMTiles)"
NOTES="Archivos .pmtiles individuales por municipio para la app Conexion."

echo "Creating GitHub Release $TAG and uploading 168 municipality .pmtiles assets..."
gh release create "$TAG" mapas/*/*.pmtiles --title "$TITLE" --notes "$NOTES" || gh release upload "$TAG" mapas/*/*.pmtiles --clobber

echo "Successfully uploaded 168 municipality map assets to GitHub Release $TAG!"
