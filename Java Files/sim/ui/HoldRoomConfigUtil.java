package sim.ui;

import sim.model.Flight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Helper to build sensible default HoldRoomConfig lists.
 * This does NOT replace HoldRoomConfig; it just builds them.
 */
public final class HoldRoomConfigUtil {

    private HoldRoomConfigUtil() { }

    /**
     * 1:1 holdroom-to-flight mapping (if flights provided).
     * - sets walk time to holdDelayMinutes
     * - allows exactly that flight (keeps room selection stable)
     * If flights is null/empty, returns a single room that accepts all flights.
     */
    public static List<HoldRoomConfig> buildDefaultHoldRoomConfigs(List<Flight> flights, int holdDelayMinutes) {
        int mins = Math.max(0, holdDelayMinutes);

        List<HoldRoomConfig> out = new ArrayList<>();
        if (flights == null || flights.isEmpty()) {
            HoldRoomConfig cfg = new HoldRoomConfig(1);
            cfg.setWalkTime(mins, 0);
            // empty allowed list == accepts all flights
            out.add(cfg);
            return out;
        }

        for (int i = 0; i < flights.size(); i++) {
            Flight f = flights.get(i);
            HoldRoomConfig cfg = new HoldRoomConfig(i + 1);
            cfg.setWalkTime(mins, 0);
            if (f != null) {
                cfg.setAllowedFlights(Collections.singletonList(f));
            }
            out.add(cfg);
        }
        return out;
    }
}
