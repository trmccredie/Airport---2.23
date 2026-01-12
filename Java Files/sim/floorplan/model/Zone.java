package sim.floorplan.model;

import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public class Zone implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private ZoneType type;

    // either anchor or area (or both later)
    private Point anchor;
    private Polygon area;

    // ==========================
    // ✅ Per-zone metadata
    // ==========================

    // Ticket counters: passengers/minute
    private double ticketRatePerMinute = Double.NaN;

    // Checkpoints: passengers/hour
    private double checkpointRatePerHour = Double.NaN;

    // IMPORTANT: explicit flags (prevents old loads defaulting to 0 from acting like "closed")
    private boolean ticketRateSet = false;
    private boolean checkpointRateSet = false;

    // Ticket counters: allowed flight numbers (empty => accepts ALL)
    private final LinkedHashSet<String> allowedFlightNumbers = new LinkedHashSet<>();

    public Zone() {}

    public Zone(String id, ZoneType type) {
        this.id = id;
        this.type = type;
    }

    public static Zone anchorZone(String id, ZoneType type, Point anchor) {
        Zone z = new Zone(id, type);
        z.anchor = (anchor == null) ? null : new Point(anchor);
        return z;
    }

    public static Zone areaZone(String id, ZoneType type, Polygon area) {
        Zone z = new Zone(id, type);
        z.area = copyPolygon(area);
        return z;
    }

    public Zone copy() {
        Zone z = new Zone(id, type);
        z.anchor = (anchor == null) ? null : new Point(anchor);
        z.area = copyPolygon(area);

        // ✅ copy metadata + explicit flags
        z.ticketRatePerMinute = this.ticketRatePerMinute;
        z.checkpointRatePerHour = this.checkpointRatePerHour;
        z.ticketRateSet = this.ticketRateSet;
        z.checkpointRateSet = this.checkpointRateSet;

        z.allowedFlightNumbers.clear();
        z.allowedFlightNumbers.addAll(this.allowedFlightNumbers);

        return z;
    }

    private static Polygon copyPolygon(Polygon p) {
        if (p == null) return null;
        return new Polygon(p.xpoints.clone(), p.ypoints.clone(), p.npoints);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public ZoneType getType() { return type; }
    public void setType(ZoneType type) { this.type = type; }

    public Point getAnchor() { return anchor; }
    public void setAnchor(Point anchor) { this.anchor = (anchor == null) ? null : new Point(anchor); }

    public Polygon getArea() { return area; }
    public void setArea(Polygon area) { this.area = copyPolygon(area); }

    // ==========================
    // ✅ Metadata getters/setters
    // ==========================
    public boolean hasTicketRatePerMinute() { return ticketRateSet; }

    public double getTicketRatePerMinute() { return ticketRatePerMinute; }

    /** Set to NaN to "unset" */
    public void setTicketRatePerMinute(double v) {
        this.ticketRatePerMinute = v;
        this.ticketRateSet = !Double.isNaN(v);
    }

    public boolean hasCheckpointRatePerHour() { return checkpointRateSet; }

    public double getCheckpointRatePerHour() { return checkpointRatePerHour; }

    /** Set to NaN to "unset" */
    public void setCheckpointRatePerHour(double v) {
        this.checkpointRatePerHour = v;
        this.checkpointRateSet = !Double.isNaN(v);
    }

    /** Empty set => accepts ALL flights */
    public Set<String> getAllowedFlightNumbers() { return allowedFlightNumbers; }

    public void setAllowedFlightNumbers(Collection<String> nums) {
        allowedFlightNumbers.clear();
        if (nums == null) return;
        for (String s : nums) {
            if (s == null) continue;
            String t = s.trim();
            if (!t.isEmpty()) allowedFlightNumbers.add(t);
        }
    }

    // ==========================
    // Milestone 4 helpers
    // ==========================
    public boolean isComplete() {
        if (type == null) return false;
        if (type.hasAnchor()) {
            if (anchor == null) return false;
        }
        if (type.hasArea()) {
            if (area == null || area.npoints < 3) return false;
        }
        return true;
    }

    public Rectangle getBounds() {
        if (area != null && area.npoints >= 3) return area.getBounds();
        if (anchor != null) return new Rectangle(anchor.x, anchor.y, 1, 1);
        return new Rectangle(0, 0, 0, 0);
    }

    public boolean containsPoint(Point p) {
        if (p == null) return false;
        if (area != null && area.npoints >= 3) {
            Rectangle b = area.getBounds();
            if (!b.contains(p)) return false;
            return area.contains(p.x + 0.5, p.y + 0.5);
        }
        return false;
    }

    @Override
    public String toString() {
        return (type == null ? "Zone" : type.getLabel()) + (id == null ? "" : (" (" + id + ")"));
    }
}
