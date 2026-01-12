package sim.floorplan.sim;

import java.awt.Point;
import java.util.List;

/**
 * TravelTimeProvider derived from PathCache paths.
 *
 * WALKING ONLY: no service times here. Checkpoint service duration is derived from ratePerHour.
 *
 * Preferred mode (floorplan): use metersPerPixel + walkSpeedMps:
 *   seconds = (px * metersPerPixel) / walkSpeedMps
 *   minutes = ceil(seconds / 60)
 *
 * Legacy compatibility: if metersPerPixel <= 0, falls back to pixelsPerMinute.
 */
public class RouterTravelTimeProvider implements TravelTimeProvider {

    private final FloorplanBindings bindings;
    private final PathCache pathCache;

    // Preferred:
    private double walkSpeedMps = 1.34;
    private double metersPerPixel = 0.05;

    // Legacy fallback:
    private final double pixelsPerMinute;

    private final int minMinutes;

    // Old constructor kept (compat)
    public RouterTravelTimeProvider(sim.floorplan.model.FloorplanProject project,
                                    FloorplanBindings bindings,
                                    PathCache pathCache,
                                    double pixelsPerMinute,
                                    int minMinutes) {
        this.bindings = bindings;
        this.pathCache = pathCache;
        this.pixelsPerMinute = Math.max(1.0, pixelsPerMinute);
        this.minMinutes = Math.max(0, minMinutes);
    }

    // New preferred constructor
    public RouterTravelTimeProvider(FloorplanBindings bindings,
                                    PathCache pathCache,
                                    double walkSpeedMps,
                                    double metersPerPixel,
                                    int minMinutes) {
        this.bindings = bindings;
        this.pathCache = pathCache;
        this.walkSpeedMps = Math.max(0.1, walkSpeedMps);
        this.metersPerPixel = (metersPerPixel > 0) ? metersPerPixel : 0.05;
        this.pixelsPerMinute = -1.0;
        this.minMinutes = Math.max(0, minMinutes);
    }

    public void setWalkSpeedMps(double mps) { this.walkSpeedMps = Math.max(0.1, mps); }
    public void setMetersPerPixel(double mpp) { this.metersPerPixel = (mpp > 0) ? mpp : this.metersPerPixel; }

    @Override
    public int minutesTicketToCheckpoint(int ticketLineIdx, int checkpointLineIdx) {
        Point a = bindings.getTicketAnchor(ticketLineIdx);
        Point b = bindings.getCheckpointAnchor(checkpointLineIdx);
        return minutesBetween(a, b);
    }

    @Override
    public int minutesCheckpointToHold(int checkpointLineIdx, int holdRoomIdx) {
        Point a = bindings.getCheckpointAnchor(checkpointLineIdx);
        Point b = bindings.getHoldroomAnchor(holdRoomIdx);
        return minutesBetween(a, b);
    }

    private int minutesBetween(Point a, Point b) {
        if (a == null || b == null) return Math.max(1, minMinutes);

        List<Point> path = pathCache.path(a, b);
        double lenPx = (path != null && path.size() >= 2) ? PathCache.polylineLengthPixels(path) : a.distance(b);

        int minutes;
        if (metersPerPixel > 0 && walkSpeedMps > 0) {
            double meters = lenPx * metersPerPixel;
            double seconds = meters / walkSpeedMps;
            minutes = (int) Math.ceil(seconds / 60.0);
        } else {
            minutes = (int) Math.ceil(lenPx / Math.max(1.0, pixelsPerMinute));
        }

        minutes = Math.max(minMinutes, minutes);
        return Math.max(1, minutes);
    }
}
