package com.victorkithinji.wrap.wrapca.ingestion;

import com.victorkithinji.wrap.wrapca.grid.VegetationTypeEnum;

/**
 * ESA WorldCover class codes and their mapping to VegetationTypeEnum.
 *
 * ESA WorldCover uses a single-band GeoTIFF with integer class codes.
 * This class is the single source of truth for that mapping — update it
 * here if the deployment area requires a different interpretation of any code.
 *
 * Codes present in the Aberdare / central Kenya deployment area:
 *   10  Tree cover         → AFROMONTANE_FOREST
 *   20  Shrubland          → SHRUBLAND
 *   30  Grassland          → GRASSLAND
 *   40  Cropland           → CROPLAND  (agricultural fringe of reserve boundary)
 *   50  Built-up           → BUILT     (handles NON_COMBUSTIBLE — replaces OSM masking)
 *   60  Bare/sparse veg    → BARE_SOIL
 *   80  Permanent water    → WATER
 *   90  Herbaceous wetland → WATER
 *
 * Codes outside the deployment area (safe fallbacks):
 *   70  Snow and ice       → BARE_SOIL
 *   95  Mangroves          → BARE_SOIL
 *   100 Moss and lichen    → BARE_SOIL
 *
 * Any unrecognised code resolves to BARE_SOIL. This is intentionally conservative —
 * BARE_SOIL has minimal fuel load, so an unknown cover type will not propagate fire
 * at unrealistic rates. The fallback is logged as a warning by the consumer.
 *
 * Note on DEV-003 and DEV-004: MONTANE_GRASSLAND was renamed to GRASSLAND, and
 * CROPLAND was added as a new enum constant. Both changes are recorded in the
 * deviation discourse. This class references both by their current enum names.
 */
public final class EsaBandLayout {

    private EsaBandLayout() {}

    // ESA WorldCover class code constants
    public static final int CODE_TREE_COVER          = 10;
    public static final int CODE_SHRUBLAND           = 20;
    public static final int CODE_GRASSLAND           = 30;
    public static final int CODE_CROPLAND            = 40;
    public static final int CODE_BUILT_UP            = 50;
    public static final int CODE_BARE_SPARSE         = 60;
    public static final int CODE_SNOW_ICE            = 70;
    public static final int CODE_PERMANENT_WATER     = 80;
    public static final int CODE_HERBACEOUS_WETLAND  = 90;
    public static final int CODE_MANGROVES           = 95;
    public static final int CODE_MOSS_LICHEN         = 100;

    /**
     * Resolves an ESA class code to a VegetationTypeEnum.
     * Unrecognised codes resolve to BARE_SOIL — callers should log a warning
     * if this fallback is hit on a code they did not expect.
     */
    public static VegetationTypeEnum toVegetationTypeEnum(int classCode) {
        return switch (classCode) {
            case CODE_TREE_COVER         -> VegetationTypeEnum.AFROMONTANE_FOREST;
            case CODE_SHRUBLAND          -> VegetationTypeEnum.SHRUBLAND;
            case CODE_GRASSLAND          -> VegetationTypeEnum.GRASSLAND;
            case CODE_CROPLAND           -> VegetationTypeEnum.CROPLAND;
            case CODE_BUILT_UP           -> VegetationTypeEnum.BUILT;
            case CODE_PERMANENT_WATER,
                 CODE_HERBACEOUS_WETLAND -> VegetationTypeEnum.WATER;
            default                      -> VegetationTypeEnum.BARE_SOIL;
        };
    }
}