
package sim.floorplan.model;

import java.util.*;

/**
 * Lightweight index over zones for fast lookups.
 * Runtime helper (not required to serialize).
 */
public class ZoneIndex {

    private final List<Zone> zones;
    private final Map<String, Zone> byId = new HashMap<>();
    private final EnumMap<ZoneType, List<Zone>> byType = new EnumMap<>(ZoneType.class);

    public ZoneIndex(List<Zone> zones) {
        this.zones = (zones == null) ? Collections.emptyList() : zones;

        for (ZoneType t : ZoneType.values()) byType.put(t, new ArrayList<>());

        for (Zone z : this.zones) {
            if (z == null || z.getType() == null) continue;

            byType.get(z.getType()).add(z);

            String id = (z.getId() == null) ? null : z.getId().trim();
            if (id != null && !id.isEmpty()) {
                // if duplicates exist, last one wins — validation should catch duplicates anyway
                byId.put(id, z);
            }
        }
    }

    public List<Zone> all() { return zones; }

    public Zone byId(String id) {
        if (id == null) return null;
        return byId.get(id.trim());
    }

    public List<Zone> ofType(ZoneType type) {
        if (type == null) return Collections.emptyList();
        return Collections.unmodifiableList(byType.getOrDefault(type, Collections.emptyList()));
    }

    // Anchor helpers
    public List<Zone> spawns() { return ofType(ZoneType.SPAWN); }
    public List<Zone> tickets() { return ofType(ZoneType.TICKET_COUNTER); }
    public List<Zone> checkpoints() { return ofType(ZoneType.CHECKPOINT); }
    public List<Zone> holdrooms() { return ofType(ZoneType.HOLDROOM); }

    // Convention helpers
    public Zone ticketQueueFor(String ticketId) {
        return byId(ticketId + "_QUEUE");
    }

    public Zone checkpointQueueFor(String checkpointId) {
        return byId(checkpointId + "_QUEUE");
    }

    public Zone holdroomAreaFor(String holdroomId) {
        return byId(holdroomId + "_AREA");
    }
}
