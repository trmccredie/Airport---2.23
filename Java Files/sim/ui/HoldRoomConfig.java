package sim.ui;

import sim.model.Flight;

import java.io.Serializable;
import java.util.*;

/**
 * UI/Engine configuration for a Hold Room.
 *
 * Key rules supported:
 *  - Each hold room has its own checkpoint->hold walking time (stored as total seconds).
 *  - The UI may allow selecting multiple flights per hold room.
 *  - If no flights are selected, this hold room is considered to accept ALL flights.
 *
 * Note: We store allowed flights by flight number (String) so the config remains stable
 * across copies/snapshots and doesn't depend on object identity.
 */
public class HoldRoomConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int id;

    // Optional display name (kept for reflection resilience with some UIs)
    private String name;

    // If empty => accepts ALL flights
    private final Set<String> allowedFlightNumbers = new LinkedHashSet<>();

    // Total seconds from checkpoint to this hold room
    private int walkSecondsFromCheckpoint;

    public HoldRoomConfig(int id) {
        this(id, 0);
    }

    public HoldRoomConfig(int id, int walkSecondsFromCheckpoint) {
        this.id = id;
        setWalkSecondsFromCheckpoint(walkSecondsFromCheckpoint);
    }

    public int getId() { return id; }

    /* ---------------------------
     * Display name (optional)
     * --------------------------- */
    public String getName() { return name; }
    public void setName(String n) { this.name = (n == null || n.trim().isEmpty()) ? null : n.trim(); }

    /* ---------------------------
     * Allowed flights
     * --------------------------- */

    public Set<String> getAllowedFlightNumbers() {
        return Collections.unmodifiableSet(allowedFlightNumbers);
    }

    /** If empty => accepts ALL flights. */
    public boolean isAllFlights() {
        return allowedFlightNumbers.isEmpty();
    }

    /** Replace allowed flights using flight numbers. */
    public void setAllowedFlightNumbers(Collection<String> flightNumbers) {
        allowedFlightNumbers.clear();
        if (flightNumbers == null) return;
        for (String s : flightNumbers) {
            if (s != null) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) allowedFlightNumbers.add(trimmed);
            }
        }
    }

    /** Replace allowed flights using Flight objects (stores flightNumber strings). */
    public void setAllowedFlights(Collection<Flight> flights) {
        allowedFlightNumbers.clear();
        if (flights == null) return;
        for (Flight f : flights) {
            if (f != null && f.getFlightNumber() != null) {
                String trimmed = f.getFlightNumber().trim();
                if (!trimmed.isEmpty()) allowedFlightNumbers.add(trimmed);
            }
        }
    }

    public void addAllowedFlightNumber(String flightNumber) {
        if (flightNumber == null) return;
        String trimmed = flightNumber.trim();
        if (!trimmed.isEmpty()) allowedFlightNumbers.add(trimmed);
    }

    public void addAllowedFlight(Flight f) {
        if (f == null || f.getFlightNumber() == null) return;
        String trimmed = f.getFlightNumber().trim();
        if (!trimmed.isEmpty()) allowedFlightNumbers.add(trimmed);
    }

    public void removeAllowedFlightNumber(String flightNumber) {
        if (flightNumber == null) return;
        allowedFlightNumbers.remove(flightNumber.trim());
    }

    public void clearAllowedFlights() {
        allowedFlightNumbers.clear();
    }

    public boolean accepts(Flight flight) {
        if (flight == null || flight.getFlightNumber() == null) return false;
        if (allowedFlightNumbers.isEmpty()) return true;
        return allowedFlightNumbers.contains(flight.getFlightNumber().trim());
    }

    /* ---------------------------
     * Walk time
     * --------------------------- */

    public int getWalkSecondsFromCheckpoint() {
        return walkSecondsFromCheckpoint;
    }

    public void setWalkSecondsFromCheckpoint(int totalSeconds) {
        this.walkSecondsFromCheckpoint = Math.max(0, totalSeconds);
    }

    /** Alias for reflection/compatibility with older code paths. */
    public void setWalkSeconds(int totalSeconds) {
        setWalkSecondsFromCheckpoint(totalSeconds);
    }

    public int getWalkMinutes() {
        return walkSecondsFromCheckpoint / 60;
    }

    public int getWalkSecondsPart() {
        return walkSecondsFromCheckpoint % 60;
    }

    public void setWalkTime(int minutes, int seconds) {
        int m = Math.max(0, minutes);
        int s = Math.max(0, Math.min(59, seconds));
        this.walkSecondsFromCheckpoint = m * 60 + s;
    }

    /* ---------------------------
     * Copy helper
     * --------------------------- */

    public HoldRoomConfig copy() {
        HoldRoomConfig c = new HoldRoomConfig(this.id, this.walkSecondsFromCheckpoint);
        c.setName(this.name);
        c.setAllowedFlightNumbers(this.allowedFlightNumbers);
        return c;
    }

    /* ---------------------------
     * Utility
     * --------------------------- */

    @Override
    public String toString() {
        return "HoldRoomConfig{" +
                "id=" + id +
                ", name=" + (name == null ? "-" : ("'" + name + "'")) +
                ", allowedFlightNumbers=" + allowedFlightNumbers +
                ", walkSecondsFromCheckpoint=" + walkSecondsFromCheckpoint +
                '}';
    }

    @Override
       public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HoldRoomConfig)) return false;
        HoldRoomConfig that = (HoldRoomConfig) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
