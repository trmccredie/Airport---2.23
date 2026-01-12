package sim.floorplan.sim;

import sim.floorplan.model.FloorplanProject;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Routing-based TravelTimeProvider for floorplan simulation.
 *
 * WALKING ONLY: Converts A* path length (pixels) into seconds/minutes using:
 *   meters = pixels * metersPerPixel
 *   seconds = meters / walkSpeedMps
 *
 * Return value rules (per TravelTimeProvider contract):
 *  - Return positive integer for valid travel time
 *  - Return <= 0 to indicate "unknown" so engine can fall back
 *
 * IMPORTANT:
 *  - Unknown cases return -1 (never 0) to avoid accidental "instant travel".
 *  - Checkpoint service time is NOT included; the engine derives that from lane rate.
 */
public class FloorplanTravelTimeProvider implements TravelTimeProvider {

    private static final double DEFAULT_METERS_PER_PIXEL = 0.05;

    private final FloorplanProject project;
    private final FloorplanBindings bindings;
    private final PathCache pathCache;

    private double walkSpeedMps;
    private double metersPerPixel = DEFAULT_METERS_PER_PIXEL;

    public FloorplanTravelTimeProvider(FloorplanProject project, double walkSpeedMps) {
        this(project, walkSpeedMps, 4, true);
    }

    public FloorplanTravelTimeProvider(FloorplanProject project, double walkSpeedMps, int stridePx, boolean allowDiagonal) {
        this.project = project;
        this.bindings = new FloorplanBindings(project);
        this.pathCache = new PathCache(bindings.getMask(), Math.max(1, stridePx), allowDiagonal);
        this.walkSpeedMps = Math.max(0.1, walkSpeedMps);
        refreshMetersPerPixelFromProject();
    }

    public void setMetersPerPixel(double metersPerPixel) {
        this.metersPerPixel = (metersPerPixel > 0) ? metersPerPixel : DEFAULT_METERS_PER_PIXEL;
    }

    public double getMetersPerPixel() {
        refreshMetersPerPixelFromProject();
        return metersPerPixel;
    }

    public void setWalkSpeedMps(double walkSpeedMps) {
        this.walkSpeedMps = Math.max(0.1, walkSpeedMps);
    }

    public double getWalkSpeedMps() { return walkSpeedMps; }

    // ============================
    // Minutes API (engine today)
    // ============================

    @Override
    public int minutesSpawnToTicket(int ticketCounterIdx) {
        int sec = secondsSpawnToTicket(ticketCounterIdx);
        return ceilMinutesFromSeconds(sec);
    }

    @Override
    public int minutesSpawnToCheckpoint(int checkpointIdx) {
        int sec = secondsSpawnToCheckpoint(checkpointIdx);
        return ceilMinutesFromSeconds(sec);
    }

    @Override
    public int minutesTicketToCheckpoint(int ticketCounterIdx, int checkpointIdx) {
        int sec = secondsTicketToCheckpoint(ticketCounterIdx, checkpointIdx);
        return ceilMinutesFromSeconds(sec);
    }

    @Override
    public int minutesCheckpointToHold(int checkpointIdx, int holdRoomIdx) {
        int sec = secondsCheckpointToHold(checkpointIdx, holdRoomIdx);
        return ceilMinutesFromSeconds(sec);
    }

    // ============================
    // ✅ Seconds API (for future engine work)
    // ============================

    @Override
    public int secondsSpawnToTicket(int ticketCounterIdx) {
        Point spawn = safeSpawnAnchor();
        Point ticket = bindings.getTicketAnchor(ticketCounterIdx);
        return secondsBetween(spawn, ticket);
    }

    @Override
    public int secondsSpawnToCheckpoint(int checkpointIdx) {
        Point spawn = safeSpawnAnchor();
        Point cp = bindings.getCheckpointAnchor(checkpointIdx);
        return secondsBetween(spawn, cp);
    }

    @Override
    public int secondsTicketToCheckpoint(int ticketCounterIdx, int checkpointIdx) {
        Point a = bindings.getTicketAnchor(ticketCounterIdx);
        Point b = bindings.getCheckpointAnchor(checkpointIdx);
        return secondsBetween(a, b);
    }

    @Override
    public int secondsCheckpointToHold(int checkpointIdx, int holdRoomIdx) {
        Point a = bindings.getCheckpointAnchor(checkpointIdx);
        Point b = bindings.getHoldroomAnchor(holdRoomIdx);
        return secondsBetween(a, b);
    }

    // ============================
    // Core math
    // ============================

    private int secondsBetween(Point a, Point b) {
        // unknown → -1 (never 0)
        if (a == null || b == null) return -1;

        refreshMetersPerPixelFromProject();

        double px = pathLengthPixels(a, b);
        if (px <= 0) return -1;

        double mpp = Math.max(1e-9, metersPerPixel);
        double meters = px * mpp;
        double seconds = meters / Math.max(0.1, walkSpeedMps);

        int sec = (int) Math.ceil(seconds);
        return Math.max(1, sec);
    }

    private static int ceilMinutesFromSeconds(int seconds) {
        if (seconds <= 0) return -1;
        int minutes = (int) Math.ceil(seconds / 60.0);
        return Math.max(1, minutes);
    }

    private double pathLengthPixels(Point a, Point b) {
        List<Point> path = pathCache.path(a, b);
        if (path != null && path.size() >= 2) {
            return PathCache.polylineLengthPixels(path);
        }
        // fallback so we never return "unknown" just because routing failed
        return a.distance(b);
    }

    private Point safeSpawnAnchor() {
        try {
            Method m = bindings.getClass().getMethod("getSpawnAnchor");
            Object out = m.invoke(bindings);
            if (out instanceof Point) return (Point) out;
        } catch (Throwable ignored) { }
        return null;
    }

    private void refreshMetersPerPixelFromProject() {
        if (project == null) return;

        // 1) getMetersPerPixel()
        try {
            Method m = project.getClass().getMethod("getMetersPerPixel");
            Object out = m.invoke(project);
            if (out instanceof Number) {
                double v = ((Number) out).doubleValue();
                if (v > 0) { metersPerPixel = v; return; }
            }
        } catch (Throwable ignored) {}

        // 2) field metersPerPixel
        try {
            Field f = project.getClass().getDeclaredField("metersPerPixel");
            f.setAccessible(true);
            Object out = f.get(project);
            if (out instanceof Number) {
                double v = ((Number) out).doubleValue();
                if (v > 0) { metersPerPixel = v; return; }
            }
        } catch (Throwable ignored) {}

        if (metersPerPixel <= 0) metersPerPixel = DEFAULT_METERS_PER_PIXEL;
    }
}
