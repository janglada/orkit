package opt.routing.internal;

public record StopDef(
        String id,
        double lat,
        double lon,
        int demand,
        int serviceTimeMin,
        int openMin,
        int closeMin
) {
    public boolean hasTimeWindow() {
        return openMin > 0 || closeMin < 24 * 60;
    }
}
