package opt.routing;

/**
 * Source for inter-node distances (in meters).
 * Create via the static factory methods.
 */
public sealed interface DistanceSource
        permits DistanceSource.Haversine, DistanceSource.PrecomputedMatrix {

    /** Great-circle distance computed on the fly from latitude/longitude coordinates. */
    record Haversine() implements DistanceSource {}

    /** User-supplied distance matrix (rows = from-node, cols = to-node, values in meters). */
    record PrecomputedMatrix(long[][] meters) implements DistanceSource {}

    static DistanceSource haversine() {
        return new Haversine();
    }

    static DistanceSource fromMatrix(long[][] metersMatrix) {
        return new PrecomputedMatrix(metersMatrix);
    }
}
