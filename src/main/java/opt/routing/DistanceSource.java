package opt.routing;

import opt.ModelException;

/**
 * Source for inter-node distances (in meters) and, optionally, travel times (in minutes).
 * Create via the static factory methods.
 * <p>
 * Matrix layout: rows/columns {@code 0..d-1} are the depots in declaration order, followed by
 * the stops in declaration order.
 */
public sealed interface DistanceSource
        permits DistanceSource.Haversine, DistanceSource.PrecomputedMatrix, DistanceSource.PrecomputedMatrices {

    /** Great-circle distance computed on the fly from latitude/longitude coordinates. */
    record Haversine() implements DistanceSource {}

    /**
     * User-supplied distance matrix; travel times are derived from {@code travelSpeedKmh}.
     * @param meters square matrix of inter-node distances in meters; depots first (declaration order), then stops
     */
    record PrecomputedMatrix(long[][] meters) implements DistanceSource {}

    /**
     * User-supplied distance and travel-time matrices with the same layout, for example from a
     * road-network API. {@code travelSpeedKmh} is ignored.
     * @param meters  square matrix of inter-node distances in meters
     * @param minutes square matrix of inter-node travel times in minutes
     */
    record PrecomputedMatrices(long[][] meters, long[][] minutes) implements DistanceSource {}

    /** Returns the {@link Haversine} source that derives distances from lat/lon on the fly. */
    static DistanceSource haversine() {
        return new Haversine();
    }

    /**
     * Wraps a caller-supplied square distance matrix. Rows/columns {@code 0..d-1} are the
     * depots in declaration order, followed by the stops in declaration order.
     */
    static DistanceSource fromMatrix(long[][] metersMatrix) {
        requireSquare(metersMatrix, "Distance matrix");
        return new PrecomputedMatrix(metersMatrix);
    }

    /**
     * Wraps caller-supplied distance (meters) and travel-time (minutes) matrices of identical
     * layout. Supplying real travel times switches the time dimension on and makes
     * {@code travelSpeedKmh} irrelevant.
     */
    static DistanceSource fromMatrices(long[][] metersMatrix, long[][] minutesMatrix) {
        requireSquare(metersMatrix, "Distance matrix");
        requireSquare(minutesMatrix, "Time matrix");
        if (minutesMatrix.length != metersMatrix.length) {
            throw new ModelException("Time matrix must be square (N×N) and match the distance matrix size ("
                    + metersMatrix.length + "), got " + minutesMatrix.length + ".");
        }
        return new PrecomputedMatrices(metersMatrix, minutesMatrix);
    }

    private static void requireSquare(long[][] matrix, String name) {
        if (matrix == null) {
            throw new ModelException(name + " must not be null.");
        }
        int n = matrix.length;
        if (n == 0) {
            throw new ModelException(name + " must be non-empty and square.");
        }
        for (long[] row : matrix) {
            if (row == null || row.length != n) {
                throw new ModelException(name + " must be square (N×N).");
            }
        }
    }
}
