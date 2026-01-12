package sim.floorplan.sim;

import java.util.concurrent.atomic.AtomicInteger;

public class StationScheduler {

    public static final class Assignment {
        public final int laneIndex;
        public final long enterSec;
        public final long startSec;
        public final long endSec;

        public Assignment(int laneIndex, long enterSec, long startSec, long endSec) {
            this.laneIndex = laneIndex;
            this.enterSec = enterSec;
            this.startSec = startSec;
            this.endSec = endSec;
        }
    }

    private final long[] nextFreeSecByLane;
    private final AtomicInteger rr = new AtomicInteger(0);

    public StationScheduler(int lanes) {
        this.nextFreeSecByLane = new long[Math.max(1, lanes)];
    }

    public int lanes() { return nextFreeSecByLane.length; }

    public void reset(long nowSec) {
        for (int i = 0; i < nextFreeSecByLane.length; i++) nextFreeSecByLane[i] = nowSec;
        rr.set(0);
    }

    public Assignment assign(long nowSec, double paxPerHour) {
        long serviceSec = secondsPerPassenger(paxPerHour);
        int lane = pickLaneFair();
        long start = Math.max(nowSec, nextFreeSecByLane[lane]);
        long end = start + serviceSec;
        nextFreeSecByLane[lane] = end;
        return new Assignment(lane, nowSec, start, end);
    }

    private int pickLaneFair() {
        // Pick earliest-available lane; tie-break with round-robin so you don’t “clump” into lane 0.
        long bestT = Long.MAX_VALUE;
        int best = 0;
        int start = Math.floorMod(rr.getAndIncrement(), nextFreeSecByLane.length);

        for (int k = 0; k < nextFreeSecByLane.length; k++) {
            int i = (start + k) % nextFreeSecByLane.length;
            long t = nextFreeSecByLane[i];
            if (t < bestT) {
                bestT = t;
                best = i;
            }
        }
        return best;
    }

    public static long secondsPerPassenger(double paxPerHour) {
        if (paxPerHour <= 0) return Long.MAX_VALUE / 4; // effectively “stopped”
        double sec = 3600.0 / paxPerHour;
        long s = (long) Math.ceil(sec);
        return Math.max(1, s);
    }
}
