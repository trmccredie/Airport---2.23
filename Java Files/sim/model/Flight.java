package sim.model;

// Flight.java

import java.time.LocalTime;
import java.util.Objects;

public class Flight {
    private String flightNumber;
    private LocalTime departureTime;
    private int seats;
    private double fillPercent;
    private ShapeType shape;

    public enum ShapeType { CIRCLE, TRIANGLE, SQUARE, DIAMOND, STAR, HEXAGON }

    public Flight(String flightNumber, LocalTime departureTime, int seats, double fillPercent, ShapeType shape) {
        this.flightNumber  = normalize(flightNumber);
        this.departureTime = departureTime;
        this.seats         = Math.max(0, seats);
        this.fillPercent   = clamp01(fillPercent);
        this.shape         = (shape == null ? ShapeType.CIRCLE : shape);
    }

    public String getFlightNumber() { return flightNumber; }
    public void setFlightNumber(String flightNumber) { this.flightNumber = normalize(flightNumber); }

    public LocalTime getDepartureTime() { return departureTime; }
    public void setDepartureTime(LocalTime departureTime) { this.departureTime = departureTime; }

    public int getSeats() { return seats; }
    public void setSeats(int seats) { this.seats = Math.max(0, seats); }

    public double getFillPercent() { return fillPercent; }
    public void setFillPercent(double fillPercent) { this.fillPercent = clamp01(fillPercent); }

    public ShapeType getShape() { return shape; }
    public void setShape(ShapeType shape) { this.shape = (shape == null ? ShapeType.CIRCLE : shape); }

    /** Convenience: expected passenger count (rounded). */
    public int getPlannedPassengers() {
        return (int) Math.round(seats * fillPercent);
    }

    /** Convenience: compute a close time offset minutes before departure. */
    public LocalTime getCloseTime(int minutesBeforeDeparture) {
        if (departureTime == null) return null;
        int m = Math.max(0, minutesBeforeDeparture);
        return departureTime.minusMinutes(m);
    }

    /** Case-insensitive equality by flight number (trimmed). */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Flight)) return false;
        Flight other = (Flight) o;
        return Objects.equals(normalize(this.flightNumber), normalize(other.flightNumber));
    }

    @Override
    public int hashCode() {
        String n = normalize(this.flightNumber);
        return n == null ? 0 : n.toLowerCase().hashCode();
    }

    @Override
    public String toString() {
        return "Flight{" +
                "flightNumber='" + flightNumber + '\'' +
                ", departureTime=" + departureTime +
                ", seats=" + seats +
                ", fillPercent=" + fillPercent +
                ", shape=" + shape +
                '}';
    }

    // Helpers
    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
