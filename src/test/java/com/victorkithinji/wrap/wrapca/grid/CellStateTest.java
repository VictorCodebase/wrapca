package com.victorkithinji.wrap.wrapca.grid;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CellStateEnum}.
 * Focuses on ordinal stability — CaGrid stores states as int ordinals in its
 * int[][] array, so the declaration order is part of the public contract.
 * Any reordering would silently corrupt persisted grids and saved run files.
 */
class CellStateTest {

    @Test
    void ordinal_UNBURNED_isZero() {
        // int[][] state arrays are zero-initialised by the JVM, so UNBURNED
        // must be ordinal 0 for a freshly allocated grid to be valid.
        assertEquals(0, CellStateEnum.UNBURNED.ordinal(),
                "UNBURNED must be ordinal 0 — JVM zero-initialises int arrays");
    }

    @Test
    void ordinal_BURNING_isOne() {
        assertEquals(1, CellStateEnum.BURNING.ordinal());
    }

    @Test
    void ordinal_BURNED_isTwo() {
        assertEquals(2, CellStateEnum.BURNED.ordinal());
    }

    @Test
    void ordinal_NON_COMBUSTIBLE_isThree() {
        assertEquals(3, CellStateEnum.NON_COMBUSTIBLE.ordinal());
    }

    @Test
    void exactlyFourStates_noAccidentalAdditions() {
        assertEquals(4, CellStateEnum.values().length,
                "Adding a state requires updating CaGrid, fuel models, and serialised run files");
    }

    @Test
    void valuesArrayRoundTrip_ordinalToEnum() {
        // This is the pattern used throughout CaGrid — verify it works for all states.
        for (CellStateEnum state : CellStateEnum.values()) {
            assertSame(state, CellStateEnum.values()[state.ordinal()],
                    "Ordinal round-trip failed for " + state);
        }
    }
}