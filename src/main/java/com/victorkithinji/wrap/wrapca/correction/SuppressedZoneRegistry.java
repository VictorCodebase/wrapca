package com.victorkithinji.wrap.wrapca.correction;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks cells temporarily forced to NON_COMBUSTIBLE due to suppression
 * signatures detected in VIIRS thermal data. The CA engine queries this
 * registry before evaluating any frontier cell.
 * <br/> <br/>
 * Suppression entries carry an expiry time. Expired entries are cleaned
 * lazily on query rather than on a background thread — the simulation
 * step rate is frequent enough that stale entries are evicted quickly.
 *<br/><br/>
 * Thread-safe: ConcurrentHashMap supports concurrent reads from the
 * Monte Carlo ForkJoinPool and writes from CV correction.
 */
@Service
public class SuppressedZoneRegistry {

    // cell index (row * gridWidth + col) → suppression expiry
    private final Map<Long, Instant> suppressedCells = new ConcurrentHashMap<>();

    /**
     * Registers a cell as suppressed until the given expiry time.
     * If the cell is already registered, the expiry is overwritten —
     * a later overpass always wins.
     */
    public void register(long cellIndex, Instant expiresAt) {
        suppressedCells.put(cellIndex, expiresAt);
    }

    /**
     * Returns true if the cell is currently under an active suppression window.
     * Lazily removes expired entries on negative checks.
     */
    public boolean isActive(long cellIndex) {
        Instant expiry = suppressedCells.get(cellIndex);
        if (expiry == null) {
            return false;
        }
        if (Instant.now().isAfter(expiry)) {
            suppressedCells.remove(cellIndex);
            return false;
        }
        return true;
    }

    /**
     * Bulk-registers a set of suppressed cell indices all sharing the same expiry.
     * Used by CvStateInjectorService when processing a CV overpass correction.
     */
    public void registerAll(Iterable<Long> cellIndices, Instant expiresAt) {
        for (long idx : cellIndices) {
            suppressedCells.put(idx, expiresAt);
        }
    }

    /**
     * Removes all suppression entries — used when the simulation resets
     * or a new Phase 2 run begins.
     */
    public void clear() {
        suppressedCells.clear();
    }

    /** Returns the current number of active suppression entries (including possibly stale ones). */
    public int size() {
        return suppressedCells.size();
    }
}