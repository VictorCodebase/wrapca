# WRaP CA Engine — Deviation Discourse

## Purpose

This document records deviations from the submitted project proposal. Each entry states what changed, why, and which
groups implemented it. Agents updating this document should add a new entry per deviation — one entry per logical
change, not one per file touched. Keep entries concise. Do not restate the original design at length — only what changed
and why.

---

## DEV-001 — Vegetation type source changed from NDVI inference to ESA WorldCover classification

**What changed:** `VegetationType` per cell is now derived from ESA WorldCover land cover class codes rather than NDVI
thresholding.

**Why:** NDVI measures photosynthetic density, not cover type. Ecologically distinct types — grassland and open forest
canopy — can produce identical NDVI values. Since `VegetationType` is the direct key into the Rothermel fuel model, a
misclassification produces categorically wrong fire behaviour, not a marginal error. ESA WorldCover provides a free,
10m, globally consistent land cover classification that maps cleanly onto the engine's required vegetation categories.

**Groups affected:** 4 (ingestion — new ESA read path and cache), 5 (grid initialisation — resampling and resolution
logic)

---

## DEV-002 — OSM scope reduced to road geometry only

**What changed:** OSM is no longer used for built-up area masking. ESA WorldCover class code 50 (built-up) handles
NON_COMBUSTIBLE classification for settlements. OSM is retained solely for road and path linestring geometry, used in
ignition likelihood index I(c).

**Why:** ESA provides a superior and more consistent source for built-up classification at 10m resolution, eliminating a
redundant data dependency. OSM built-up coverage in the East Africa deployment area is inconsistent. OSM's unique
contribution — linear road and path features that ESA cannot represent — is preserved.

**Groups affected:** 4 (ingestion — new `OsmRoadLoaderService` scoped to highway tags only), 5 (grid initialisation —
road proximity computation), 7 (montecarlo — proximity input source change)

---

## DEV-003 — `MONTANE_GRASSLAND` renamed to `GRASSLAND`

**What changed:** The `VegetationType` enum constant `MONTANE_GRASSLAND` is renamed to `GRASSLAND`. Fuel parameters are
unchanged.

**Why:** "Montane" implies a high-altitude ecological zone. The grid boundary in practice includes lower-elevation areas
and agricultural fringe where the label would be ecologically misleading to users viewing the output map. The rename is
cosmetic with respect to fire physics.

**Groups affected:** 1 (grid — enum rename), any group referencing `MONTANE_GRASSLAND` by name must update to
`GRASSLAND`

---

## DEV-004 — `CROPLAND` added as a named vegetation type

**What changed:** A new `VegetationType` constant `CROPLAND` is added. It resolves to grassland-equivalent Rothermel
fuel parameters internally. It is displayed as "Cropland" on the client UI.

**Why:** ESA WorldCover class 40 (cropland) is present along the Aberdare forest reserve boundary due to smallholder
agricultural encroachment. Collapsing this silently into `GRASSLAND` would display "Grassland" to users on cells that
are visibly farmland. The named constant preserves display accuracy without introducing new fuel model complexity — the
underlying fire behaviour is identical to grassland.

**Groups affected:** 1 (grid — enum addition, fuel model JSON entry), 4 (ingestion — `EsaBandLayout` mapping), 9 (
output — ordinal included in response), 11 (dto — `vegetationTypeOrdinals` field)

---

## DEV-005 — Vegetation type ordinals added to Phase 1 API response

**What changed:** `PhaseOneResultResponse` gains a new field `int[] vegetationTypeOrdinals` — a flat array of
`VegetationType` ordinals, one per cell, same dimensions as the existing risk arrays.

**Why:** The proposal's objective five requires interactive heatmaps and visualisation. A risk heatmap rendered over an
unlabelled grid is difficult for a forest officer to interpret and act on. The vegetation layer provides spatial context
at no additional computation cost — it is derived entirely from data already present in `CaGrid.environment` at response
assembly time. This is framed as a visualisation aid for the existing output, not a new feature. No new endpoint, no new
data fetch, no new computation.

**Groups affected:** 9 (output — `SimulationResultAssembler` populates the field), 11 (dto — field addition to
`PhaseOneResultResponse`)

# Group 12 Deviations — add to deviation-discourse.md

---

## DEV-007 — Bounding box stored on WrapSessionFacade, not on CaGrid

**What changed:** `SessionStatusResponseDto` requires `minX/minY/maxX/maxY` (UTM 37S metres)
to allow the frontend to convert pixel-space perimeter coordinates to geographic positions.
`CaGrid` does not carry spatial metadata per its Group 1 contract. The facade captures these
four values from the resampled `GridBands` object immediately after resampling and stores them
as `volatile double` fields on `WrapSessionFacade`.

**Why:** Adding bounding box fields to `CaGrid` would violate its contract (pure state storage,
zero spatial metadata). The facade is the correct owner since it is the only class that holds
both the `GridBands` (before they are discarded after grid initialisation) and the long-lived
session state. No other class is affected.

**Groups affected:** 12 (facade — fields added), consumers reading spatial bounds should query
`GET /api/session/status` rather than the grid object directly.

---

## DEV-008 — Phase 1 ensemble master seed fixed at compile-time constant

**What changed:** The master RNG seed for the Phase 1 Monte Carlo ensemble is fixed at
`42L` as a private constant in `WrapSessionFacade`. It is not configurable via
`application.properties`.

**Why:** Reproducibility is more useful than variability for a pre-fire risk product delivered
to forest officers. A fixed seed means identical input data produces identical output maps,
which is desirable for audit and comparison. If scenario testing with different seeds becomes
a requirement, add `wrap.simulation.master-seed` to `application.properties` and bind it via
`SimulationConfig`. The interface requires no other changes.

**Groups affected:** 12 only.