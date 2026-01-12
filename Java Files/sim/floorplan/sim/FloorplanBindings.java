package sim.floorplan.sim;

import sim.floorplan.model.FloorplanProject;
import sim.floorplan.model.WalkMask;
import sim.floorplan.model.Zone;
import sim.floorplan.model.ZoneType;
import sim.model.Flight;
import sim.ui.CheckpointConfig;
import sim.ui.TicketCounterConfig;

import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Maps engine indices -> floorplan zones using simple, reliable conventions:
 *  Ticket counter index 0 => T1 anchor, queue polygon T1_QUEUE
 *  Checkpoint index 0     => C1 anchor, queue polygon C1_QUEUE, service area C1_AREA
 *  Holdroom index 0       => H1 anchor, area polygon H1_AREA
 *
 * Also precomputes "slots" inside polygons to place queue dots.
 */
public class FloorplanBindings {

    private final FloorplanProject project;
    private final WalkMask mask;

    private final List<Zone> spawns;
    private final List<Zone> tickets;
    private final List<Zone> checkpoints;
    private final List<Zone> holdrooms;

    private final Map<String, Zone> byId = new HashMap<>();
    private final Map<String, List<Point>> slotCacheByZoneId = new HashMap<>();

    public FloorplanBindings(FloorplanProject project) {
        this.project = project;
        this.mask = (project == null) ? null : project.getMask();

        List<Zone> zs = (project == null) ? Collections.emptyList() : project.getZones();
        for (Zone z : zs) {
            if (z == null || z.getId() == null) continue;
            byId.put(z.getId().trim(), z);
        }

        this.spawns = sortAnchorsOfType(zs, ZoneType.SPAWN);
        this.tickets = sortAnchorsOfType(zs, ZoneType.TICKET_COUNTER);
        this.checkpoints = sortAnchorsOfType(zs, ZoneType.CHECKPOINT);
        this.holdrooms = sortAnchorsOfType(zs, ZoneType.HOLDROOM);
    }

    public WalkMask getMask() { return mask; }
    public FloorplanProject getProject() { return project; }

    public int spawnCount() { return spawns.size(); }
    public int ticketCount() { return tickets.size(); }
    public int checkpointCount() { return checkpoints.size(); }
    public int holdroomCount() { return holdrooms.size(); }

    // Expose animation/time defaults through bindings for convenience (read-only)
    public double getQueueSlideSecondsPerSlot() {
        return project == null ? 0.40 : project.getQueueSlideSecondsPerSlot();
    }
    public double getServicePathClampEpsilonSeconds() {
        return project == null ? 0.20 : project.getServicePathClampEpsilonSeconds();
    }

    /** Convenience getter used by some panels via reflection */
    public Point getSpawnAnchor() { return getSpawnAnchor(0); }

    /** New helper: anchor by exact zone id (null if missing or non-anchor). */
    public Point getAnchorByZoneId(String id) {
        Zone z = (id == null) ? null : byId.get(id.trim());
        return (z == null) ? null : z.getAnchor();
    }

    /** Optional utility */
    public Zone getZoneById(String id) {
        if (id == null) return null;
        return byId.get(id.trim());
    }

    // ---------------------------
    // Stable ID mapping helpers
    // ---------------------------
    public String getTicketZoneId(int idx) {
        Zone z = pickByIndex(tickets, idx);
        return (z == null) ? null : z.getId();
    }

    public String getCheckpointZoneId(int idx) {
        Zone z = pickByIndex(checkpoints, idx);
        return (z == null) ? null : z.getId();
    }

    public String getHoldroomZoneId(int idx) {
        Zone z = pickByIndex(holdrooms, idx);
        return (z == null) ? null : z.getId();
    }

    public List<String> getTicketZoneIds() { return idsOf(tickets); }
    public List<String> getCheckpointZoneIds() { return idsOf(checkpoints); }
    public List<String> getHoldroomZoneIds() { return idsOf(holdrooms); }

    /** Convenience: "<ticketId>_QUEUE" or null if idx out of range. */
    public String getTicketQueueId(int idx) {
        String id = getTicketZoneId(idx);
        return (id == null) ? null : (id + "_QUEUE");
    }

    /** Convenience: "<checkpointId>_QUEUE" or null if idx out of range. */
    public String getCheckpointQueueId(int idx) {
        String id = getCheckpointZoneId(idx);
        return (id == null) ? null : (id + "_QUEUE");
    }

    /** Convenience: "<checkpointId>_AREA" or null if idx out of range. */
    public String getCheckpointAreaId(int idx) {
        String id = getCheckpointZoneId(idx);
        return (id == null) ? null : (id + "_AREA");
    }

    /** Convenience: "<holdroomId>_AREA" or null if idx out of range. */
    public String getHoldroomAreaId(int idx) {
        String id = getHoldroomZoneId(idx);
        return (id == null) ? null : (id + "_AREA");
    }

    private static List<String> idsOf(List<Zone> zs) {
        List<String> out = new ArrayList<>();
        if (zs != null) for (Zone z : zs) if (z != null && z.getId() != null) out.add(z.getId().trim());
        return out;
    }

    // ---------------------------
    // Anchors by engine index
    // ---------------------------
    public Point getSpawnAnchor(int idx) {
        Zone z = pickByIndex(spawns, idx);
        return z == null ? null : z.getAnchor();
    }

    public Point getTicketAnchor(int counterIdx) {
        Zone z = pickByIndex(tickets, counterIdx);
        return z == null ? null : z.getAnchor();
    }

    public Point getCheckpointAnchor(int cpIdx) {
        Zone z = pickByIndex(checkpoints, cpIdx);
        return z == null ? null : z.getAnchor();
    }

    public Point getHoldroomAnchor(int holdIdx) {
        Zone z = pickByIndex(holdrooms, holdIdx);
        return z == null ? null : z.getAnchor();
    }

    // ---------------------------
    // Polygons by engine index
    // ---------------------------
    public Polygon getTicketQueuePolygon(int counterIdx) {
        Zone a = pickByIndex(tickets, counterIdx);
        if (a == null || a.getId() == null) return null;
        Zone area = byId.get(a.getId().trim() + "_QUEUE");
        return (area == null) ? null : area.getArea();
    }

    public Polygon getCheckpointQueuePolygon(int cpIdx) {
        Zone a = pickByIndex(checkpoints, cpIdx);
        if (a == null || a.getId() == null) return null;
        Zone area = byId.get(a.getId().trim() + "_QUEUE");
        return (area == null) ? null : area.getArea();
    }

    public Polygon getCheckpointAreaPolygon(int cpIdx) {
        Zone a = pickByIndex(checkpoints, cpIdx);
        if (a == null || a.getId() == null) return null;
        Zone area = byId.get(a.getId().trim() + "_AREA");
        return (area == null) ? null : area.getArea();
    }

    public Polygon getHoldroomAreaPolygon(int holdIdx) {
        Zone a = pickByIndex(holdrooms, holdIdx);
        if (a == null || a.getId() == null) return null;
        Zone area = byId.get(a.getId().trim() + "_AREA");
        return (area == null) ? null : area.getArea();
    }

    // ---------------------------
    // Slot points (legacy helpers)
    // ---------------------------
    public List<Point> getTicketQueueSlots(int counterIdx, int spacingPx) {
        Zone a = pickByIndex(tickets, counterIdx);
        if (a == null || a.getId() == null) return Collections.emptyList();
        return getSlotsForZoneId(a.getId().trim() + "_QUEUE", getTicketQueuePolygon(counterIdx), spacingPx);
    }

    public List<Point> getCheckpointQueueSlots(int cpIdx, int spacingPx) {
        Zone a = pickByIndex(checkpoints, cpIdx);
        if (a == null || a.getId() == null) return Collections.emptyList();
        return getSlotsForZoneId(a.getId().trim() + "_QUEUE", getCheckpointQueuePolygon(cpIdx), spacingPx);
    }

    public List<Point> getHoldroomAreaSlots(int holdIdx, int spacingPx) {
        Zone a = pickByIndex(holdrooms, holdIdx);
        if (a == null || a.getId() == null) return Collections.emptyList();
        return getSlotsForZoneId(a.getId().trim() + "_AREA", getHoldroomAreaPolygon(holdIdx), spacingPx);
    }

    // ---------------------------
    // Slot points (rect-fit, non-overlapping; preferred for time-based sim)
    // ---------------------------
    public List<Point> getCheckpointQueueSlotsRectFitted(int cpIdx, int minSpacingPx) {
        Polygon poly = getCheckpointQueuePolygon(cpIdx);
        return computeSlotsRectFit(poly, mask, passengerWidthPx(), passengerLengthPx(), Math.max(2, minSpacingPx));
    }

    public List<Point> getTicketQueueSlotsRectFitted(int counterIdx, int minSpacingPx) {
        Polygon poly = getTicketQueuePolygon(counterIdx);
        return computeSlotsRectFit(poly, mask, passengerWidthPx(), passengerLengthPx(), Math.max(2, minSpacingPx));
    }

    public List<Point> getHoldroomSlotsRectFitted(int holdIdx, int minSpacingPx) {
        Polygon poly = getHoldroomAreaPolygon(holdIdx);
        return computeSlotsRectFit(poly, mask, holdPassengerWidthPx(), holdPassengerLengthPx(), Math.max(2, minSpacingPx));
    }

    private int passengerWidthPx() {
        if (project == null) return 4; // safe fallback
        double px = project.getPassengerWidthMeters() / Math.max(1e-9, project.getMetersPerPixel());
        return Math.max(2, (int) Math.ceil(px));
    }

    private int passengerLengthPx() {
        if (project == null) return 6; // safe fallback
        double px = project.getPassengerLengthMeters() / Math.max(1e-9, project.getMetersPerPixel());
        return Math.max(2, (int) Math.ceil(px));
    }

    private int holdPassengerWidthPx() {
        if (project == null) return passengerWidthPx();
        double feet = project.getHoldPassengerWidthFeet();
        double meters = feet * 0.3048;
        double px = meters / Math.max(1e-9, project.getMetersPerPixel());
        return Math.max(2, (int) Math.ceil(px));
    }

    private int holdPassengerLengthPx() {
        if (project == null) return passengerLengthPx();
        double feet = project.getHoldPassengerLengthFeet();
        double meters = feet * 0.3048;
        double px = meters / Math.max(1e-9, project.getMetersPerPixel());
        return Math.max(2, (int) Math.ceil(px));
    }

    private List<Point> getSlotsForZoneId(String zoneId, Polygon poly, int spacingPx) {
        if (zoneId == null || poly == null || poly.npoints < 3) return Collections.emptyList();

        int ver = (mask == null) ? 0 : mask.getVersion(); // ✅ cache key includes mask version
        String key = zoneId + "|s=" + Math.max(2, spacingPx) + "|v=" + ver + "|legacy";
        List<Point> cached = slotCacheByZoneId.get(key);
        if (cached != null) return cached;

        List<Point> slots = computeSlotsCenterOnly(poly, mask, Math.max(2, spacingPx));
        slotCacheByZoneId.put(key, slots);
        return slots;
    }

    /**
     * NOTE: This legacy method checks ONLY the center point, not rectangle-fit.
     * FloorplanSimulationPanel can do rectangle-corner-fit checks for footprints.
     */
    private static List<Point> computeSlotsCenterOnly(Polygon poly, WalkMask mask, int spacingPx) {
        Rectangle r = poly.getBounds();
        if (r.width <= 0 || r.height <= 0) return Collections.emptyList();

        List<Point> out = new ArrayList<>();
        int step = Math.max(2, spacingPx);

        int x0 = r.x;
        int y0 = r.y;
        int x1 = r.x + r.width;
        int y1 = r.y + r.height;

        for (int y = y0; y <= y1; y += step) {
            for (int x = x0; x <= x1; x += step) {
                if (!poly.contains(x + 0.5, y + 0.5)) continue;
                if (mask != null) {
                    if (!mask.inBounds(x, y)) continue;
                    if (!mask.isWalkable(x, y)) continue;
                }
                out.add(new Point(x, y));
            }
        }
        return out;
    }

    /**
     * Rectangle-fit slot sampling to avoid overlapping passengers.
     * Axis-aligned rectangle (widthPx x lengthPx) around each candidate center must:
     *  - lie inside the polygon (corner check)
     *  - be walkable at corners if mask is present
     *  - be at least minSpacingPx away from previously accepted centers
     */
    private static List<Point> computeSlotsRectFit(Polygon poly, WalkMask mask,
                                                   int widthPx, int lengthPx, int minSpacingPx) {
        if (poly == null || poly.npoints < 3) return Collections.emptyList();
        Rectangle r = poly.getBounds();
        if (r.width <= 0 || r.height <= 0) return Collections.emptyList();

        int halfW = Math.max(1, widthPx / 2);
        int halfL = Math.max(1, lengthPx / 2);
        int step = Math.max(2, Math.min(widthPx, lengthPx));
        int sep2 = Math.max(minSpacingPx, Math.min(widthPx, lengthPx));
        int sep2Sq = sep2 * sep2;

        int x0 = r.x + halfW;
        int y0 = r.y + halfL;
        int x1 = r.x + r.width - halfW;
        int y1 = r.y + r.height - halfL;

        List<Point> out = new ArrayList<>();

        for (int y = y0; y <= y1; y += step) {
            for (int x = x0; x <= x1; x += step) {
                // Fast outside test using corners
                int[][] corners = new int[][]{
                        {x - halfW, y - halfL}, {x + halfW, y - halfL},
                        {x + halfW, y + halfL}, {x - halfW, y + halfL}
                };
                boolean ok = true;
                for (int[] c : corners) {
                    double cx = c[0] + 0.5, cy = c[1] + 0.5;
                    if (!poly.contains(cx, cy)) { ok = false; break; }
                    if (mask != null) {
                        int ix = c[0], iy = c[1];
                        if (!mask.inBounds(ix, iy) || !mask.isWalkable(ix, iy)) { ok = false; break; }
                    }
                }
                if (!ok) continue;

                // Non-overlap test vs accepted centers
                boolean farEnough = true;
                for (Point p : out) {
                    int dx = p.x - x, dy = p.y - y;
                    if (dx*dx + dy*dy < sep2Sq) { farEnough = false; break; }
                }
                if (!farEnough) continue;

                out.add(new Point(x, y));
            }
        }
        return out;
    }

    // ==========================================================
    // Existing config-from-zones helpers (kept; reflection-based)
    // ==========================================================
    public List<TicketCounterConfig> buildTicketCounterConfigsFromZones(
            List<Flight> flights,
            List<TicketCounterConfig> uiFallback,
            double defaultRatePerMin
    ) {
        Map<String, Flight> byNum = new HashMap<>();
        if (flights != null) {
            for (Flight f : flights) {
                if (f != null && f.getFlightNumber() != null) byNum.put(f.getFlightNumber().trim(), f);
            }
        }

        List<TicketCounterConfig> out = new ArrayList<>();
        for (int i = 0; i < tickets.size(); i++) {
            Zone z = tickets.get(i);

            TicketCounterConfig cfgFromUi = (uiFallback != null && i < uiFallback.size()) ? uiFallback.get(i) : null;

            double rate = Double.NaN;

            Double zonePerMin = readDouble(z,
                    "getTicketRatePerMinute", "getTicketRatePerMin",
                    "getRatePerMinute", "getRatePerMin",
                    "getServiceRatePerMinute"
            );

            Double zonePerHour = readDouble(z,
                    "getTicketRatePerHour", "getRatePerHour", "getServiceRatePerHour"
            );

            if (zonePerMin != null && zonePerMin > 0) rate = zonePerMin;
            else if (zonePerHour != null && zonePerHour > 0) rate = zonePerHour / 60.0;

            if (!(rate > 0)) {
                if (cfgFromUi != null && cfgFromUi.getRate() > 0) rate = cfgFromUi.getRate();
                else rate = defaultRatePerMin;
            }

            Set<Flight> allowed = new HashSet<>();

            Object allowedRaw = readObject(z,
                    "getAllowedFlightNumbers", "getAllowedFlights", "getAllowedFlightNums", "getAllowedFlightIds"
            );

            if (allowedRaw instanceof Collection) {
                for (Object o : (Collection<?>) allowedRaw) {
                    if (o == null) continue;
                    if (o instanceof Flight) {
                        allowed.add((Flight) o);
                    } else {
                        String s = String.valueOf(o).trim();
                        if (!s.isEmpty()) {
                            Flight f = byNum.get(s);
                            if (f != null) allowed.add(f);
                        }
                    }
                }
            } else if (allowedRaw instanceof String) {
                String s = ((String) allowedRaw).trim();
                if (!s.isEmpty()) {
                    for (String part : s.split("[,;\\n\\t ]+")) {
                        String num = part.trim();
                        if (num.isEmpty()) continue;
                        Flight f = byNum.get(num);
                        if (f != null) allowed.add(f);
                    }
                }
            }

            if (allowed.isEmpty() && cfgFromUi != null && cfgFromUi.getAllowedFlights() != null) {
                allowed.addAll(cfgFromUi.getAllowedFlights());
            }

            TicketCounterConfig cfg = new TicketCounterConfig(i + 1, rate, allowed);
            out.add(cfg);
        }
        return out;
    }

    public List<CheckpointConfig> buildCheckpointConfigsFromZones(
            List<CheckpointConfig> uiFallback,
            double defaultRatePerHour
    ) {
        List<CheckpointConfig> out = new ArrayList<>();
        for (int i = 0; i < checkpoints.size(); i++) {
            Zone z = checkpoints.get(i);

            CheckpointConfig cfg = new CheckpointConfig(i + 1);

            CheckpointConfig ui = (uiFallback != null && i < uiFallback.size()) ? uiFallback.get(i) : null;

            Double zonePerHour = readDouble(z,
                    "getCheckpointRatePerHour", "getRatePerHour", "getServiceRatePerHour"
            );
            Double zonePerMin = readDouble(z,
                    "getCheckpointRatePerMinute", "getCheckpointRatePerMin",
                    "getRatePerMinute", "getRatePerMin", "getServiceRatePerMinute"
            );

            double perHour;
            if (zonePerHour != null && zonePerHour > 0) perHour = zonePerHour;
            else if (zonePerMin != null && zonePerMin > 0) perHour = zonePerMin * 60.0;
            else if (ui != null && ui.getRatePerHour() > 0) perHour = ui.getRatePerHour();
            else perHour = defaultRatePerHour;

            cfg.setRatePerHour(perHour);
            out.add(cfg);
        }
        return out;
    }

    // ---------------------------
    // Reflection helpers
    // ---------------------------
    private static Double readDouble(Object target, String... methodNames) {
        Object o = readObject(target, methodNames);
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object readObject(Object target, String... methodNames) {
        if (target == null) return null;
        for (String name : methodNames) {
            try {
                Method m = target.getClass().getMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ---------------------------
    // Utilities
    // ---------------------------
    private static Zone pickByIndex(List<Zone> list, int idx) {
        if (list == null || list.isEmpty()) return null;
        int i = Math.max(0, Math.min(idx, list.size() - 1));
        return list.get(i);
    }

    private static List<Zone> sortAnchorsOfType(List<Zone> zones, ZoneType t) {
        List<Zone> out = new ArrayList<>();
        if (zones != null) {
            for (Zone z : zones) if (z != null && z.getType() == t) out.add(z);
        }
        out.sort(Comparator.comparingInt(FloorplanBindings::idNumberOrMax)
                .thenComparing(z -> (z.getId() == null ? "" : z.getId())));
        return out;
    }

    private static int idNumberOrMax(Zone z) {
        if (z == null || z.getId() == null) return Integer.MAX_VALUE;
        String id = z.getId().trim();
        int i = id.length() - 1;
        while (i >= 0 && Character.isDigit(id.charAt(i))) i--;
        if (i == id.length() - 1) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(id.substring(i + 1));
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }
}

