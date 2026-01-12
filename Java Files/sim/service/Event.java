package sim.service;

public class Event implements Comparable<Event> {

    public enum Type {
        ARRIVAL,
        START_TICKETING,
        FINISH_TICKETING,
        START_SCREENING,
        FINISH_SCREENING,
        ENTER_HOLDROOM,
        FLIGHT_CLOSE
    }

    public final int timeSec; // absolute seconds since sim start
    public final Type type;
    public final Object payload; // Passenger, Flight, tuple, etc.

    public Event(int timeSec, Type type, Object payload) {
        this.timeSec = timeSec;
        this.type = type;
        this.payload = payload;
    }

    @Override
    public int compareTo(Event o) {
        return Integer.compare(this.timeSec, o.timeSec);
    }

    @Override
    public String toString() {
        return "Event{" + "t=" + timeSec + ", " + type + '}';
    }
}
