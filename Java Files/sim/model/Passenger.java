package sim.model;

public class Passenger {
    private final Flight flight;
    private final int arrivalMinute;             // minute they arrived at airport (relative to sim start)
    private final boolean inPerson;              // true = bought in person, false = online

    // ============================
    // ✅ NEW: interval-independent absolute timestamps (seconds since sim start)
    // ============================
    // -1 means unknown/not-yet
    private int ticketDoneAbsSecond = -1;
    private int checkpointDoneAbsSecond = -1;

    // ============================
    // Legacy minute + second-of-minute timestamps (kept for backward compatibility)
    // ============================

    // Ticket completion time (minute + second-of-minute)
    private int ticketCompletionMinute = -1;
    private int ticketCompletionSecond = -1;   // 0..59 within the minute (or -1 if unknown)

    // Checkpoint entry/completion times
    private int checkpointEntryMinute = -1;
    private int checkpointCompletionMinute = -1;
    private int checkpointCompletionSecond = -1; // 0..59 within the minute (or -1 if unknown)

    // Tracks if passenger missed their flight (boarding closed before checkpoint completion)
    private boolean missed = false;

    // hold-room entry minute (relative to start) and arrival order
    private int holdRoomEntryMinute = -1;
    private int holdRoomSequence = -1;

    // Assigned physical hold room (index into engine's holdRoomLines / holdRoomConfigs list)
    private int assignedHoldRoomIndex = -1;

    /** Old-style constructor: defaults to in-person, unknown minute */
    public Passenger(Flight flight) {
        this(flight, -1, true);
    }

    /** Legacy constructor with arrivalMinute: defaults to in-person */
    public Passenger(Flight flight, int arrivalMinute) {
        this(flight, arrivalMinute, true);
    }

    /** New full constructor: specify arrivalMinute *and* whether in person */
    public Passenger(Flight flight, int arrivalMinute, boolean inPerson) {
        this.flight = flight;
        this.arrivalMinute = arrivalMinute;
        this.inPerson = inPerson;
    }

    /** @return the flight this passenger is on */
    public Flight getFlight() { return flight; }

    /** @return minute they arrived at the airport (relative to schedule start) */
    public int getArrivalMinute() { return arrivalMinute; }

    /** @return true if this passenger bought their ticket in person */
    public boolean isInPerson() { return inPerson; }

    // ==========================================================
    // ✅ NEW absolute-second API (preferred going forward)
    // ==========================================================

    /** Absolute second (since sim start) when ticket service completed, or -1 if unknown. */
    public int getTicketDoneAbsSecond() {
        if (ticketDoneAbsSecond >= 0) return ticketDoneAbsSecond;
        // Best-effort derive from legacy if present
        if (ticketCompletionMinute >= 0 && ticketCompletionSecond >= 0) {
            return ticketCompletionMinute * 60 + ticketCompletionSecond;
        }
        return -1;
    }

    /**
     * Sets absolute ticket completion second.
     * Also updates legacy minute/second fields for backward compatibility.
     */
    public void setTicketDoneAbsSecond(int absSecond) {
        this.ticketDoneAbsSecond = absSecond;
        if (absSecond >= 0) {
            this.ticketCompletionMinute = absSecond / 60;
            this.ticketCompletionSecond = absSecond % 60;
        }
    }

    /** Absolute second (since sim start) when checkpoint service completed, or -1 if unknown. */
    public int getCheckpointDoneAbsSecond() {
        if (checkpointDoneAbsSecond >= 0) return checkpointDoneAbsSecond;
        // Best-effort derive from legacy if present
        if (checkpointCompletionMinute >= 0 && checkpointCompletionSecond >= 0) {
            return checkpointCompletionMinute * 60 + checkpointCompletionSecond;
        }
        return -1;
    }

    /**
     * Sets absolute checkpoint completion second.
     * Also updates legacy minute/second fields for backward compatibility.
     */
    public void setCheckpointDoneAbsSecond(int absSecond) {
        this.checkpointDoneAbsSecond = absSecond;
        if (absSecond >= 0) {
            this.checkpointCompletionMinute = absSecond / 60;
            this.checkpointCompletionSecond = absSecond % 60;
        }
    }

    // ==========================================================
    // Legacy ticket completion (kept)
    // ==========================================================

    /** @return Minute when this passenger finished service at the ticket counter (or -1 if not yet) */
    public int getTicketCompletionMinute() { return ticketCompletionMinute; }

    public void setTicketCompletionMinute(int ticketCompletionMinute) {
        this.ticketCompletionMinute = ticketCompletionMinute;
        syncTicketAbsFromLegacyIfComplete();
    }

    /** @return Second-of-minute (0..59) when ticket service finished (or -1 if not set). */
    public int getTicketCompletionSecond() { return ticketCompletionSecond; }

    public void setTicketCompletionSecond(int ticketCompletionSecond) {
        this.ticketCompletionSecond = ticketCompletionSecond;
        syncTicketAbsFromLegacyIfComplete();
    }

    private void syncTicketAbsFromLegacyIfComplete() {
        if (ticketCompletionMinute >= 0 && ticketCompletionSecond >= 0) {
            this.ticketDoneAbsSecond = ticketCompletionMinute * 60 + ticketCompletionSecond;
        }
    }

    // ==========================================================
    // Legacy checkpoint times (kept)
    // ==========================================================

    /** @return Minute when this passenger entered the checkpoint queue (or -1 if not yet) */
    public int getCheckpointEntryMinute() { return checkpointEntryMinute; }
    public void setCheckpointEntryMinute(int checkpointEntryMinute) { this.checkpointEntryMinute = checkpointEntryMinute; }

    /** @return Minute when this passenger finished service at the checkpoint (or -1 if not yet) */
    public int getCheckpointCompletionMinute() { return checkpointCompletionMinute; }

    public void setCheckpointCompletionMinute(int checkpointCompletionMinute) {
        this.checkpointCompletionMinute = checkpointCompletionMinute;
        syncCheckpointAbsFromLegacyIfComplete();
    }

    /** @return Second-of-minute (0..59) when checkpoint service finished (or -1 if not set). */
    public int getCheckpointCompletionSecond() { return checkpointCompletionSecond; }

    public void setCheckpointCompletionSecond(int checkpointCompletionSecond) {
        this.checkpointCompletionSecond = checkpointCompletionSecond;
        syncCheckpointAbsFromLegacyIfComplete();
    }

    private void syncCheckpointAbsFromLegacyIfComplete() {
        if (checkpointCompletionMinute >= 0 && checkpointCompletionSecond >= 0) {
            this.checkpointDoneAbsSecond = checkpointCompletionMinute * 60 + checkpointCompletionSecond;
        }
    }

    // ==========================================================
    // Missed
    // ==========================================================

    /** Mark passenger as missed when boarding closes before checkpoint completion */
    public void setMissed(boolean missed) { this.missed = missed; }

    /** Check if passenger missed their flight */
    public boolean isMissed() { return missed; }

    // ==========================================================
    // Hold room
    // ==========================================================

    /** When did they arrive in the hold-room? */
    public int getHoldRoomEntryMinute() { return holdRoomEntryMinute; }
    public void setHoldRoomEntryMinute(int m) { this.holdRoomEntryMinute = m; }

    /** What number were they in arrival order to the hold-room? */
    public int getHoldRoomSequence() { return holdRoomSequence; }
    public void setHoldRoomSequence(int seq) { this.holdRoomSequence = seq; }

    /** Which physical hold room was this passenger assigned to (index)? */
    public int getAssignedHoldRoomIndex() { return assignedHoldRoomIndex; }
    public void setAssignedHoldRoomIndex(int idx) { this.assignedHoldRoomIndex = idx; }

    /** Convenience alias used by some reflection paths. */
    public int getAssignedHoldroomIndex() { return assignedHoldRoomIndex; } // spelling alias
}
