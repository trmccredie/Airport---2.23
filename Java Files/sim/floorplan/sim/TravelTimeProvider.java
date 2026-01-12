package sim.floorplan.sim;

/**
 * Optional hook used by SimulationEngine for WALKING times only.
 * Service time at checkpoints is determined solely by the lane's ratePerHour
 * (fixed per-passenger duration) and MUST NOT be included here.
 *
 * Return value rules:
 *  - Return a positive integer minutes/seconds for a valid travel time
 *  - Return <= 0 to indicate "unknown" so the engine can fall back to legacy delays
 *
 * Notes:
 *  - The engine currently calls the minutes methods.
 *  - This interface also exposes seconds methods (defaults derive from minutes)
 *    to support finer-grained future engines.
 */
public interface TravelTimeProvider {

    // ============================
    // Minutes API (engine today)
    // ============================
    int minutesTicketToCheckpoint(int ticketCounterIdx, int checkpointIdx);
    int minutesCheckpointToHold(int checkpointIdx, int holdRoomIdx);

    /** Spawn → Ticket (in-person first leg). Default = unknown. */
    default int minutesSpawnToTicket(int ticketCounterIdx) { return -1; }

    /** Spawn → Checkpoint (online first leg). Default = unknown. */
    default int minutesSpawnToCheckpoint(int checkpointIdx) { return -1; }

    // ============================
    // ✅ Seconds API (future engine)
    // ============================

    /** Ticket → Checkpoint in seconds. Default derives from minutes. */
    default int secondsTicketToCheckpoint(int ticketCounterIdx, int checkpointIdx) {
        int m = minutesTicketToCheckpoint(ticketCounterIdx, checkpointIdx);
        return (m > 0) ? (m * 60) : -1;
    }

    /** Checkpoint → Holdroom in seconds. Default derives from minutes. */
    default int secondsCheckpointToHold(int checkpointIdx, int holdRoomIdx) {
        int m = minutesCheckpointToHold(checkpointIdx, holdRoomIdx);
        return (m > 0) ? (m * 60) : -1;
    }

    /** Spawn → Ticket in seconds. Default derives from minutes. */
    default int secondsSpawnToTicket(int ticketCounterIdx) {
        int m = minutesSpawnToTicket(ticketCounterIdx);
        return (m > 0) ? (m * 60) : -1;
    }

    /** Spawn → Checkpoint in seconds. Default derives from minutes. */
    default int secondsSpawnToCheckpoint(int checkpointIdx) {
        int m = minutesSpawnToCheckpoint(checkpointIdx);
        return (m > 0) ? (m * 60) : -1;
    }

    // ============================
    // Walk speed knob
    // ============================

    /** Optional: SimulationEngine will call this (if present) when the walk speed changes. Default = no-op. */
    default void setWalkSpeedMps(double mps) {}
}
