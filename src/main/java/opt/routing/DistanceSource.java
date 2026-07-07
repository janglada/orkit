package opt.routing;

import opt.ModelException;

/**
 * Source for inter-node distances (in meters).
 * Create via the static factory methods.
 */
public sealed interface DistanceSource
        permits DistanceSource.Haversine, DistanceSource.PrecomputedMatrix {

    /** Great-circle distance computed on the fly from latitude/longitude coordinates. */
    record Haversine() implements DistanceSource {}

    /**
     * User-supplied distance matrix (rows = from-node, cols = to-node, values in meters).
     * @param meters square matrix of inter-node distances in meters, indexed as depot-first
     */
    record PrecomputedMatrix(long[][] meters) implements DistanceSource {}

    /** Returns the {@link Haversine} source that derives distances from lat/lon on the fly. */
    static DistanceSource haversine() {
        return new Haversine();
    }

    /** Wraps a caller-supplied square distance matrix; row/column 0 is the depot. */
    static DistanceSource fromMatrix(long[][] metersMatrix) {
        if (metersMatrix == null) {
            throw new ModelException("Distance matrix must not be null.");
        }
        int n = metersMatrix.length;
        if (n == 0) {
            throw new ModelException("Distance matrix must be non-empty and square.");
        }
        for (long[] row : metersMatrix) {
            if (row == null || row.length != n) {
                throw new ModelException("Distance matrix must be square (N×N).");
            }
        }
        return new PrecomputedMatrix(metersMatrix);
    }
}
