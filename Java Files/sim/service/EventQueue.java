package sim.service;

import java.util.PriorityQueue;

public class EventQueue {

    private final PriorityQueue<Event> pq = new PriorityQueue<>();

    public void add(Event e) { if (e != null) pq.add(e); }

    public Event peek() { return pq.peek(); }

    public Event poll() { return pq.poll(); }

    public boolean isEmpty() { return pq.isEmpty(); }

    public int size() { return pq.size(); }

    public void clear() { pq.clear(); }
}
