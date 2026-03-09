# Group 4 — Ingestion & CV Integration

## File placement

```
src/main/java/com/victorkithinji/wrap/wrapca/
├── ingestion/
│   ├── BandLayout.java
│   ├── GridBands.java
│   ├── GeoTiffBandReaderService.java
│   ├── IngestionCacheService.java
│   ├── WindField.java
│   └── WindFieldLoaderService.java
│   └── FirePerimeterParserService.java
└── cvintegration/
    ├── FirePerimeterData.java
    └── CvApiClient.java

src/test/java/com/victorkithinji/wrap/wrapca/
├── test/ingestion/
│   └── Group4IngestionTest.java
└── test/util/
    └── SyntheticGeoTiffGenerator.java

data/
├── cache/          ← created automatically by IngestionCacheService
├── wind/
│   └── era5_wind_stub.json   ← copy from this package
└── geotiff/
    └── latest_cv_output.tif  ← run SyntheticGeoTiffGenerator.main() once
```

## First-time setup

1. Copy `era5_wind_stub.json` into `data/wind/`
2. Run `SyntheticGeoTiffGenerator.main()` to produce `data/geotiff/latest_cv_output.tif`
    - From your IDE: run the `main` method directly
    - Or from Maven: `mvn exec:java -Dexec.mainClass="...test.util.SyntheticGeoTiffGenerator"`

## Properties to add to application.properties

```properties
wrap.cv.base-url=http://localhost:5000/api/cv
wrap.cv.stub-mode=true
```

Keep `stub-mode=true` until the CV module is ready for integration.

## CV contract assumptions (confirm before integration)

All assumptions are documented in `BandLayout.java`. The key items to confirm:

| Item | Assumed value | Notes |
|------|--------------|-------|
| Band 1 | NDVI | Index 0 in GeoTools |
| Band 2 | NDMI | Index 1 |
| Band 3 | ELEVATION | Index 2 |
| CRS | EPSG:32737 (UTM 37S) | CV must reproject before export |
| Pixel size | 100 m | CV may export at finer resolution; reader will warn if mismatch |
| GeoJSON CRS | UTM 37S metres | FirePerimeterParserService expects metre coordinates |
| Fuel-state endpoint | GET /fuel-state | Returns octet-stream |
| Perimeter endpoint | GET /fire-perimeter | Returns FirePerimeterData JSON |

The strongest recommendation to make to CV: set band description strings in the
GeoTIFF metadata (NDVI / NDMI / ELEVATION). This allows `BandLayout.USE_BAND_NAMES`
to be set to `true`, making the reader completely order-independent. It's a one-line
change in any Python GeoTIFF writer (rasterio: `dataset.update_tags(1, name='NDVI')`).