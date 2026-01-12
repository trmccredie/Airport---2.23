package sim.floorplan.path;

import sim.floorplan.model.FloorplanProject;
import sim.floorplan.model.WalkMask;
import sim.floorplan.model.Zone;
import sim.floorplan.model.ZoneIndex;
import sim.floorplan.model.ZoneType;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks reachability between required anchors using A* over the WalkMask.
 * Used during Validate & Lock so you catch bad masks before running the sim.
 */
public class FloorplanConnectivity {

    // Safety cap (keeps A* from running forever on huge plans)
    private static final int MAX_EXPANDED = 2_000_000;

    public static List<String> check(FloorplanProject project, int stridePx, boolean allowDiagonal) {
        List<String> errs = new ArrayList<>();
        if (project == null) return errs;

        WalkMask mask = project.getMask();
        if (mask == null) return errs; // let normal validate() report missing mask

        ZoneIndex idx = new ZoneIndex(project.getZones());

        List<Zone> spawns = idx.spawns();
        List<Zone> tickets = idx.tickets();
        List<Zone> checks = idx.checkpoints();
        List<Zone> holds = idx.holdrooms();

        // If basics missing, normal validate already reports — avoid spam here.
        if (spawns.isEmpty() || tickets.isEmpty() || checks.isEmpty() || holds.isEmpty()) return errs;

        // Use the (single) spawn if multiple exist; validate() should enforce exactly one.
        Zone spawn = spawns.get(0);
        Point sPt = safeAnchor(spawn);

        // SPAWN -> each Ticket
        for (Zone t : tickets) {
            if (!reachable(mask, sPt, safeAnchor(t), stridePx, allowDiagonal)) {
                errs.add("No route (A*) from SPAWN " + safeId(spawn) + " to TICKET_COUNTER " + safeId(t)
                        + ". Fix mask gaps / lower inflate / try smaller stride.");
            }
        }

        // Ticket -> each Checkpoint (we require tickets can reach at least one checkpoint)
        for (Zone t : tickets) {
            boolean ok = false;
            for (Zone c : checks) {
                if (reachable(mask, safeAnchor(t), safeAnchor(c), stridePx, allowDiagonal)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                errs.add("No route (A*) from TICKET_COUNTER " + safeId(t) + " to ANY CHECKPOINT."
                        + " Fix mask connectivity between ticketing and security.");
            }
        }

        // Checkpoint -> each Holdroom (each checkpoint must reach at least one holdroom)
        for (Zone c : checks) {
            boolean ok = false;
            for (Zone h : holds) {
                if (reachable(mask, safeAnchor(c), safeAnchor(h), stridePx, allowDiagonal)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                errs.add("No route (A*) from CHECKPOINT " + safeId(c) + " to ANY HOLDROOM."
                        + " Fix mask connectivity between security and gates.");
            }
        }

        return errs;
    }

    private static boolean reachable(WalkMask mask, Point a, Point b, int stridePx, boolean allowDiagonal) {
        if (mask == null || a == null || b == null) return false;
        List<Point> path = AStarRouter.findPath(mask, a, b, Math.max(1, stridePx), MAX_EXPANDED, allowDiagonal);
        return path != null && path.size() >= 2;
    }

    private static Point safeAnchor(Zone z) {
        if (z == null) return null;
        return z.getAnchor();
    }

    private static String safeId(Zone z) {
        if (z == null) return "(null)";
        String id = z.getId();
        return (id == null || id.trim().isEmpty()) ? "(no id)" : id.trim();
    }
}