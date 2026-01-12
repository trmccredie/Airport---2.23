package sim.floorplan.model;

public enum ZoneType {
    // Anchors
    SPAWN(true,  false, "Spawn"),
    TICKET_COUNTER(true,  false, "Ticket Counter"),
    CHECKPOINT(true,  false, "Checkpoint"),
    HOLDROOM(true,  false, "Holdroom"),

    // Area-only zones (polygons)
    TICKET_QUEUE_AREA(false, true,  "Ticket Queue Area"),
    CHECKPOINT_QUEUE_AREA(false, true,  "Checkpoint Queue Area"),
    CHECKPOINT_AREA(false, true,  "Checkpoint Area"), // NEW: service window / lane area
    HOLDROOM_AREA(false, true,  "Holdroom Area");

    private final boolean hasAnchor;
    private final boolean hasArea;
    private final String label;

    ZoneType(boolean hasAnchor, boolean hasArea, String label) {
        this.hasAnchor = hasAnchor;
        this.hasArea = hasArea;
        this.label = label;
    }

    public boolean hasAnchor() { return hasAnchor; }
    public boolean hasArea()   { return hasArea; }
    public String getLabel()   { return label; }

    /** Convenience: true when this zone is an anchor (dot) type. */
    public boolean isAnchorType() { return hasAnchor && !hasArea; }

    /** Convenience: true when this zone is a polygon/area type. */
    public boolean isAreaType() { return hasArea && !hasAnchor; }

    /**
     * Safe parser (handy when loading older projects or user-edited files).
     * Returns null if unknown.
     */
    public static ZoneType fromName(String name) {
        if (name == null) return null;
        try {
            return ZoneType.valueOf(name.trim());
        } catch (Exception ignored) {
            return null;
        }
    }
}
