package sim.ui;

import java.io.Serializable;
import java.util.Objects;

/**
 * UI/Engine configuration for a single checkpoint.
 *
 * Input:    passengers/hour (industry standard).
 * Engine:   converts ratePerHour into a fixed per-passenger service duration
 *           (serviceSeconds = round(3600 / ratePerHour)). Walking time is handled
 *           separately by TravelTimeProvider and is NOT part of service time.
 *
 * Legacy helpers getRatePerMinute()/getRatePerInterval() remain for UI displays.
 */
public class CheckpointConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    private int id;

    // passengers per hour (industry standard input)
    private double ratePerHour = 120.0; // default 2/min

    public CheckpointConfig(int id) {
        this.id = id;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    /** Passengers per hour for this lane. */
    public double getRatePerHour() { return ratePerHour; }

    public void setRatePerHour(double ratePerHour) {
        this.ratePerHour = Math.max(0.0, ratePerHour);
    }

    /** Convenience for UI displays; engine derives fixed service time from ratePerHour. */
    public double getRatePerMinute() {
        return ratePerHour / 60.0;
    }

    /**
     * Convenience for display/staggering: passengers served per engine interval.
     * @param intervalMinutes engine interval length (minutes, >=1)
     */
    public double getRatePerInterval(int intervalMinutes) {
        int im = Math.max(1, intervalMinutes);
        return getRatePerMinute() * im;
    }

    /**
     * Convenience helper mirroring engine semantics:
     * deterministic service time (seconds) from passengers/hour.
     * Returns Integer.MAX_VALUE when the lane is effectively closed (rate == 0).
     */
    public int getServiceSeconds() {
        double perHour = Math.max(0.0, ratePerHour);
        if (perHour <= 0.0) return Integer.MAX_VALUE;
        int secs = (int) Math.round(3600.0 / perHour);
        return Math.max(1, secs);
    }

    @Override
    public String toString() {
        return "CheckpointConfig{id=" + id + ", ratePerHour=" + ratePerHour + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CheckpointConfig)) return false;
        CheckpointConfig that = (CheckpointConfig) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
