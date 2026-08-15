import urllib.request
import json
import os
import subprocess
import datetime
from shapely.geometry import shape, mapping, Point

def find_latest_protomaps_build():
    today = datetime.date.today()
    for i in range(365):
        d = today - datetime.timedelta(days=i)
        url = f"https://build.protomaps.com/{d.strftime('%Y%m%d')}.pmtiles"
        req = urllib.request.Request(url, method='HEAD', headers={'User-Agent': 'Mozilla/5.0'})
        try:
            res = urllib.request.urlopen(req)
            if res.status == 200:
                print(f"Found Protomaps build: {url}")
                return url
        except Exception:
            pass
    raise RuntimeError("Could not find recent Protomaps build")

def download_pmtiles_cli():
    if not os.path.exists("./pmtiles"):
        print("Downloading pmtiles CLI...")
        cmd = "curl -sL https://github.com/protomaps/go-pmtiles/releases/download/v1.25.0/go-pmtiles_1.25.0_Linux_x86_64.tar.gz | tar -xz pmtiles"
        subprocess.run(cmd, shell=True, check=True)

def main():
    download_pmtiles_cli()

    # Step 1: Base map of Cuba
    protomaps_url = find_latest_protomaps_build()
    cuba_pmtiles = "cuba.pmtiles"
    if not os.path.exists(cuba_pmtiles):
        print("Extracting Cuba base map (zoom 0..15, bbox)...")
        extract_cmd = ["./pmtiles", "extract", protomaps_url, cuba_pmtiles, "--bbox=-85.0,19.7,-74.0,23.3", "--maxzoom=15"]
        subprocess.run(extract_cmd, check=True)

    # Step 2: Boundaries from geoBoundaries API
    print("Fetching geoBoundaries metadata...")
    def fetch_json(url):
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        return json.loads(urllib.request.urlopen(req).read().decode('utf-8'))

    adm1_meta = fetch_json("https://www.geoboundaries.org/api/current/gbOpen/CUB/ADM1/")
    adm2_meta = fetch_json("https://www.geoboundaries.org/api/current/gbOpen/CUB/ADM2/")

    adm1_gj = fetch_json(adm1_meta['gjDownloadURL'])
    adm2_gj = fetch_json(adm2_meta['gjDownloadURL'])

    print(f"ADM1 (Provinces) count: {len(adm1_gj['features'])}")
    print(f"ADM2 (Municipalities) count: {len(adm2_gj['features'])}")

    prov_shapes = []
    for f in adm1_gj['features']:
        p_name = f['properties']['shapeName']
        prov_shapes.append((p_name, shape(f['geometry'])))

    # Step 3: Extract per municipality into /mapas/<provincia>/<municipio>.pmtiles
    os.makedirs("mapas", exist_ok=True)
    features_list = []

    repo_url = "https://github.com/athenacoree/conexion-/releases/download/v1.0.0-maps"

    for i, f in enumerate(adm2_gj['features']):
        m_name = f['properties']['shapeName']
        m_geom = shape(f['geometry'])

        # Spatial match to ADM1 province
        best_prov = None
        max_area = 0
        for p_name, p_geom in prov_shapes:
            try:
                inter = m_geom.intersection(p_geom).area
                if inter > max_area:
                    max_area = inter
                    best_prov = p_name
            except Exception:
                pass
        if best_prov is None:
            best_prov = min(prov_shapes, key=lambda x: m_geom.centroid.distance(x[1]))[0]

        prov_dir = os.path.join("mapas", best_prov)
        os.makedirs(prov_dir, exist_ok=True)
        out_file = os.path.join(prov_dir, f"{m_name}.pmtiles")

        # Extract tile subset
        temp_gj = {"type": "Feature", "geometry": mapping(m_geom), "properties": {}}
        with open("temp_muni.geojson", "w") as tmp:
            json.dump(temp_gj, tmp)

        if not os.path.exists(out_file) or os.path.getsize(out_file) == 0:
            sub_cmd = ["./pmtiles", "extract", cuba_pmtiles, out_file, "--region=temp_muni.geojson"]
            subprocess.run(sub_cmd, check=True)

        asset_filename = f"map_{best_prov}_{m_name}.pmtiles".replace(" ", "_")
        download_url = f"{repo_url}/{asset_filename}"

        simplified_geom = m_geom.simplify(0.001, preserve_topology=True)

        features_list.append({
            "type": "Feature",
            "properties": {
                "id": i,
                "province": best_prov,
                "municipality": m_name,
                "relativePath": f"mapas/{best_prov}/{m_name}.pmtiles",
                "assetFilename": asset_filename,
                "downloadUrl": download_url
            },
            "geometry": mapping(simplified_geom)
        })

    if os.path.exists("temp_muni.geojson"):
        os.remove("temp_muni.geojson")

    # Save asset for Android App
    os.makedirs("app/src/main/assets", exist_ok=True)
    json_path = "app/src/main/assets/cuba_municipios.json"
    geojson_data = {
        "type": "FeatureCollection",
        "features": features_list
    }
    with open(json_path, "w", encoding="utf-8") as out:
        json.dump(geojson_data, out, ensure_ascii=False)

    print(f"Generated {len(features_list)} municipalities into /mapas/ and {json_path}")

if __name__ == "__main__":
    main()
