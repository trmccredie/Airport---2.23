// FloorplanProject.java
package sim.floorplan.model;

import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FloorplanProject {
    private File pdfFile;
    private int pageIndex;
    private Integer dpi;

    // not meant for disk serialization right now; fine for runtime
    private BufferedImage floorplanImage;

    private WalkMask mask;

    // ✅ Scale (meters per pixel) — set this from project calibration
    private double metersPerPixel = 0.05;

    // ✅ Passenger footprint (for lines/transit), stored in FEET for UI clarity
    private double passengerLengthFeet = 2.0;
    private double passengerWidthFeet  = 1.5;

    // ✅ Holdroom passenger footprint (can differ visually)
    private double holdPassengerLengthFeet = 2.0;
    private double holdPassengerWidthFeet  = 1.5;

    // ✅ Sim/visual defaults (Part 1 scope: exposed here, used by sim/ui later)
    // Time-based simulation flag (informational for now)
    private boolean timeBased = true;
    // Queue animation: seconds to advance by one slot
    private double queueSlideSecondsPerSlot = 0.40; // spec: 0.40 s/slot
    // Checkpoint service path clamping epsilon (visual grace period)
    private double servicePathClampEpsilonSeconds = 0.20; // spec: 0.20 s

    // ✅ Zones
    private final List<Zone> zones = new ArrayList<>();

    public File getPdfFile() { return pdfFile; }
    public void setPdfFile(File pdfFile) { this.pdfFile = pdfFile; }

    public int getPageIndex() { return pageIndex; }
    public void setPageIndex(int pageIndex) { this.pageIndex = pageIndex; }

    public Integer getDpi() { return dpi; }
    public void setDpi(Integer dpi) { this.dpi = dpi; }

    public BufferedImage getFloorplanImage() { return floorplanImage; }
    public void setFloorplanImage(BufferedImage floorplanImage) { this.floorplanImage = floorplanImage; }

    public WalkMask getMask() { return mask; }
    public void setMask(WalkMask mask) { this.mask = mask; }

    public double getMetersPerPixel() { return metersPerPixel; }
    public void setMetersPerPixel(double metersPerPixel) {
        if (Double.isFinite(metersPerPixel) && metersPerPixel > 0) this.metersPerPixel = metersPerPixel;
    }

    // ==========================================================
    // ✅ Passenger footprint (feet)
    // ==========================================================
    public double getPassengerLengthFeet() { return passengerLengthFeet; }
    public void setPassengerLengthFeet(double passengerLengthFeet) {
        if (Double.isFinite(passengerLengthFeet) && passengerLengthFeet > 0) this.passengerLengthFeet = passengerLengthFeet;
    }

    public double getPassengerWidthFeet() { return passengerWidthFeet; }
    public void setPassengerWidthFeet(double passengerWidthFeet) {
        if (Double.isFinite(passengerWidthFeet) && passengerWidthFeet > 0) this.passengerWidthFeet = passengerWidthFeet;
    }

    // Convenience conversions (useful for sim math)
    public double getPassengerLengthMeters() { return passengerLengthFeet * 0.3048; }
    public double getPassengerWidthMeters()  { return passengerWidthFeet  * 0.3048; }
    public double getPassengerAreaSqFt() { return passengerLengthFeet * passengerWidthFeet; }
    public double getPassengerAreaSqM()  { return getPassengerLengthMeters() * getPassengerWidthMeters(); }

    // ==========================================================
    // ✅ Holdroom passenger footprint (feet)
    // ==========================================================
    public double getHoldPassengerLengthFeet() { return holdPassengerLengthFeet; }
    public void setHoldPassengerLengthFeet(double v) {
        if (Double.isFinite(v) && v > 0) this.holdPassengerLengthFeet = v;
    }

    public double getHoldPassengerWidthFeet() { return holdPassengerWidthFeet; }
    public void setHoldPassengerWidthFeet(double v) {
        if (Double.isFinite(v) && v > 0) this.holdPassengerWidthFeet = v;
    }

    // Legacy alias getters/setters (SimulationFrame uses reflection with these names too)
    public double getHoldroomPassengerLengthFeet() { return getHoldPassengerLengthFeet(); }
    public double getHoldroomPassengerWidthFeet()  { return getHoldPassengerWidthFeet(); }
    public void setHoldroomPassengerLengthFeet(double v) { setHoldPassengerLengthFeet(v); }
    public void setHoldroomPassengerWidthFeet(double v)  { setHoldPassengerWidthFeet(v); }

    // ==========================================================
    // ✅ Sim/visual defaults
    // ==========================================================
    public boolean isTimeBased() { return timeBased; }
    public void setTimeBased(boolean timeBased) { this.timeBased = timeBased; }

    public double getQueueSlideSecondsPerSlot() { return queueSlideSecondsPerSlot; }
    public void setQueueSlideSecondsPerSlot(double v) {
        if (Double.isFinite(v) && v > 0) this.queueSlideSecondsPerSlot = v;
    }

    public double getServicePathClampEpsilonSeconds() { return servicePathClampEpsilonSeconds; }
    public void setServicePathClampEpsilonSeconds(double v) {
        if (Double.isFinite(v) && v >= 0) this.servicePathClampEpsilonSeconds = v;
    }

    // ==========================================================
    // ✅ Zones
    // ==========================================================
    public List<Zone> getZones() { return zones; }

    public void setZones(List<Zone> zs) {
        zones.clear();
        if (zs != null) for (Zone z : zs) zones.add(z == null ? null : z.copy());
    }

    public void addZone(Zone z) { if (z != null) zones.add(z); }

    public void removeZone(Zone z) { zones.remove(z); }

    public FloorplanProject copy() {
        FloorplanProject p = new FloorplanProject();
        p.setPdfFile(pdfFile);
        p.setPageIndex(pageIndex);
        p.setDpi(dpi);
        p.setFloorplanImage(floorplanImage);
        p.setMask(mask == null ? null : mask.copy());
        p.setMetersPerPixel(metersPerPixel);

        // ✅ copy footprints
        p.setPassengerLengthFeet(passengerLengthFeet);
        p.setPassengerWidthFeet(passengerWidthFeet);
        p.setHoldPassengerLengthFeet(holdPassengerLengthFeet);
        p.setHoldPassengerWidthFeet(holdPassengerWidthFeet);

        // ✅ copy sim defaults
        p.setTimeBased(timeBased);
        p.setQueueSlideSecondsPerSlot(queueSlideSecondsPerSlot);
        p.setServicePathClampEpsilonSeconds(servicePathClampEpsilonSeconds);

        p.setZones(zones);
        return p;
    }

    // ==========================================================
    // Validation
    // ==========================================================
    public List<String> validate() {
        List<String> errs = new ArrayList<>();

        if (floorplanImage == null) errs.add("No floorplan image rendered.");
        if (mask == null) errs.add("No walk mask exists (generate auto-mask first).");

        // If both exist, ensure dimensions match
        if (floorplanImage != null && mask != null) {
            if (mask.getWidth() != floorplanImage.getWidth() || mask.getHeight() != floorplanImage.getHeight()) {
                errs.add("Mask dimensions do not match image dimensions. Rebuild mask after rendering/loading.");
            }
        }

        // Passenger footprint sanity (non-blocking warnings as errors for now)
        if (!(Double.isFinite(passengerLengthFeet) && passengerLengthFeet > 0)) {
            errs.add("Passenger Length (ft) must be > 0.");
        }
        if (!(Double.isFinite(passengerWidthFeet) && passengerWidthFeet > 0)) {
            errs.add("Passenger Width (ft) must be > 0.");
        }
        if (!(Double.isFinite(holdPassengerLengthFeet) && holdPassengerLengthFeet > 0)) {
            errs.add("Holdroom Passenger Length (ft) must be > 0.");
        }
        if (!(Double.isFinite(holdPassengerWidthFeet) && holdPassengerWidthFeet > 0)) {
            errs.add("Holdroom Passenger Width (ft) must be > 0.");
        }

        // Zone IDs must be unique (ignore null/blank)
        {
            Set<String> seen = new HashSet<>();
            for (Zone z : zones) {
                if (z == null) continue;
                String id = (z.getId() == null) ? null : z.getId().trim();
                if (id == null || id.isEmpty()) continue;
                if (!seen.add(id)) {
                    errs.add("Duplicate zone id detected: " + id + " (IDs must be unique)");
                }
            }
        }

        // Required anchor types exist
        int spawnCount = countType(ZoneType.SPAWN);
        if (spawnCount == 0) errs.add("Missing SPAWN anchor (place exactly one).");
        if (spawnCount > 1) errs.add("Too many SPAWN anchors (" + spawnCount + "). Place exactly one.");

        if (countType(ZoneType.TICKET_COUNTER) == 0) errs.add("Missing TICKET_COUNTER anchor (place at least one).");
        if (countType(ZoneType.CHECKPOINT) == 0) errs.add("Missing CHECKPOINT anchor (place at least one).");
        if (countType(ZoneType.HOLDROOM) == 0) errs.add("Missing HOLDROOM anchor (place at least one).");

        // Validate anchors and areas
        for (Zone z : zones) {
            if (z == null) continue;
            ZoneType t = z.getType();
            if (t == null) {
                errs.add("Zone has null type: " + safeId(z));
                continue;
            }

            // Anchor validation
            if (t.hasAnchor()) {
                if (z.getAnchor() == null) {
                    errs.add(t.getLabel() + " zone missing anchor: " + safeId(z));
                } else if (mask != null) {
                    int ax = z.getAnchor().x;
                    int ay = z.getAnchor().y;
                    if (!mask.inBounds(ax, ay)) {
                        errs.add(t.getLabel() + " anchor out of bounds: " + safeId(z));
                    } else if (!mask.isWalkable(ax, ay)) {
                        errs.add(t.getLabel() + " anchor must be on GREEN walkable pixel: " + safeId(z));
                    }
                }
            }

            // Area validation
            if (t.hasArea()) {
                Polygon pz = z.getArea();
                if (pz == null || pz.npoints < 3) {
                    errs.add(t.getLabel() + " polygon missing/invalid: " + safeId(z));
                } else {
                    double areaPx = polygonAreaAbs(pz);
                    if (areaPx < 50.0) {
                        errs.add(t.getLabel() + " polygon too small: " + safeId(z));
                    }
                    if (mask != null) {
                        double frac = walkableFraction(pz, mask);
                        if (frac < 0.50) {
                            errs.add(t.getLabel() + " polygon is mostly RED/blocked (walkable "
                                    + String.format("%.0f%%", frac * 100) + "): " + safeId(z));
                        }
                    }
                }
            }
        }

        // Enforce: each anchor has matching area polygon (by convention)
        for (Zone a : zones) {
            if (a == null || a.getType() == null) continue;
            if (!a.getType().hasAnchor()) continue;
            if (a.getId() == null || a.getId().trim().isEmpty()) continue;

            if (a.getType() == ZoneType.TICKET_COUNTER) {
                String areaId = a.getId() + "_QUEUE";
                if (findZone(areaId, ZoneType.TICKET_QUEUE_AREA) == null) {
                    errs.add("Ticket Counter " + a.getId()
                            + " needs a TICKET_QUEUE_AREA polygon (id: " + areaId + ")");
                }
            } else if (a.getType() == ZoneType.CHECKPOINT) {
                String qId = a.getId() + "_QUEUE";
                if (findZone(qId, ZoneType.CHECKPOINT_QUEUE_AREA) == null) {
                    errs.add("Checkpoint " + a.getId()
                            + " needs a CHECKPOINT_QUEUE_AREA polygon (id: " + qId + ")");
                }
                String svcId = a.getId() + "_AREA";
                if (findZone(svcId, ZoneType.CHECKPOINT_AREA) == null) {
                    errs.add("Checkpoint " + a.getId()
                            + " needs a CHECKPOINT_AREA polygon (id: " + svcId + ")");
                }
            } else if (a.getType() == ZoneType.HOLDROOM) {
                String areaId = a.getId() + "_AREA";
                if (findZone(areaId, ZoneType.HOLDROOM_AREA) == null) {
                    errs.add("Holdroom " + a.getId()
                            + " needs a HOLDROOM_AREA polygon (id: " + areaId + ")");
                }
            }
        }

        // Enforce: each area polygon has matching anchor (by convention)
        for (Zone area : zones) {
            if (area == null || area.getType() == null) continue;
            if (!area.getType().hasArea()) continue;

            String id = (area.getId() == null) ? "" : area.getId().trim();
            if (id.isEmpty()) {
                errs.add(area.getType().getLabel() + " has no id (expected *_QUEUE or *_AREA).");
                continue;
            }

            if (area.getType() == ZoneType.TICKET_QUEUE_AREA) {
                if (!id.endsWith("_QUEUE")) {
                    errs.add("Ticket Queue Area id must end with _QUEUE (found: " + id + ")");
                } else {
                    String anchorId = id.substring(0, id.length() - "_QUEUE".length());
                    if (findZone(anchorId, ZoneType.TICKET_COUNTER) == null) {
                        errs.add("Ticket Queue Area " + id + " has no matching Ticket Counter anchor (id: " + anchorId + ")");
                    }
                }
            } else if (area.getType() == ZoneType.CHECKPOINT_QUEUE_AREA) {
                if (!id.endsWith("_QUEUE")) {
                    errs.add("Checkpoint Queue Area id must end with _QUEUE (found: " + id + ")");
                } else {
                    String anchorId = id.substring(0, id.length() - "_QUEUE".length());
                    if (findZone(anchorId, ZoneType.CHECKPOINT) == null) {
                        errs.add("Checkpoint Queue Area " + id + " has no matching Checkpoint anchor (id: " + anchorId + ")");
                    }
                }
            } else if (area.getType() == ZoneType.CHECKPOINT_AREA) {
                if (!id.endsWith("_AREA")) {
                    errs.add("Checkpoint Area id must end with _AREA (found: " + id + ")");
                } else {
                    String anchorId = id.substring(0, id.length() - "_AREA".length());
                    if (findZone(anchorId, ZoneType.CHECKPOINT) == null) {
                        errs.add("Checkpoint Area " + id + " has no matching Checkpoint anchor (id: " + anchorId + ")");
                    }
                }
            } else if (area.getType() == ZoneType.HOLDROOM_AREA) {
                if (!id.endsWith("_AREA")) {
                    errs.add("Holdroom Area id must end with _AREA (found: " + id + ")");
                } else {
                    String anchorId = id.substring(0, id.length() - "_AREA".length());
                    if (findZone(anchorId, ZoneType.HOLDROOM) == null) {
                        errs.add("Holdroom Area " + id + " has no matching Holdroom anchor (id: " + anchorId + ")");
                    }
                }
            }
        }

        return errs;
    }

    private static String safeId(Zone z) {
        String id = (z == null) ? null : z.getId();
        return (id == null || id.trim().isEmpty()) ? "(no id)" : id;
    }

    private int countType(ZoneType t) {
        int c = 0;
        for (Zone z : zones) if (z != null && z.getType() == t) c++;
        return c;
    }

    private Zone findZone(String id, ZoneType t) {
        for (Zone z : zones) {
            if (z == null) continue;
            if (z.getType() == t && id != null && id.equals(z.getId())) return z;
        }
        return null;
    }

    private static double polygonAreaAbs(Polygon p) {
        if (p == null || p.npoints < 3) return 0.0;
        long sum = 0;
        for (int i = 0; i < p.npoints; i++) {
            int j = (i + 1) % p.npoints;
            sum += (long) p.xpoints[i] * p.ypoints[j] - (long) p.xpoints[j] * p.ypoints[i];
        }
        return Math.abs(sum) / 2.0;
    }

    private static double walkableFraction(Polygon poly, WalkMask mask) {
        Rectangle r = poly.getBounds();
        if (r.width <= 0 || r.height <= 0) return 0.0;

        int maxSamples = 12000;
        long cells = (long) r.width * (long) r.height;
        int step = 1;
        if (cells > maxSamples) step = (int) Math.ceil(Math.sqrt(cells / (double) maxSamples));
        step = Math.max(1, step);

        long inside = 0;
        long walk = 0;

        int x0 = r.x;
        int y0 = r.y;
        int x1 = r.x + r.width;
        int y1 = r.y + r.height;

        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                if (!mask.inBounds(x, y)) continue;
                if (!poly.contains(x + 0.5, y + 0.5)) continue;
                inside++;
                if (mask.isWalkable(x, y)) walk++;
            }
        }

        if (inside == 0) return 0.0;
        return walk / (double) inside;
    }
}
