// --- ArrivalGenerator.java ---
package sim.service;

import sim.model.Flight;
import java.util.*;

/**
 * Deterministic arrivals generator:
 * - Builds a minute-by-minute PDF across (arrivalSpan - 20) minutes (default 20-min cutoff).
 * - Allocates exact integer arrivals per minute summing to planned pax.
 * - Optionally buckets into UI intervals (e.g., 1-min snapshots).
 *
 * All math is deterministic (no RNG), so repeated runs are reproducible.
 */
public class ArrivalGenerator {
    private final int totalMinutes;       // total minutes from arrival start to cutoff
    private final int intervalMinutes;
    private final double[] minuteProbabilities;

    /**
     * @param arrivalSpanMinutes minutes from (first departure - span) to departure
     * @param intervalMinutes    UI interval size (minutes) for bucketing
     */
    public ArrivalGenerator(int arrivalSpanMinutes, int intervalMinutes) {
        // arrivals span from (departure - arrivalSpan) up to (departure - 20)
        int raw = Math.max(0, arrivalSpanMinutes - 20);
        // Guard against degenerate cases (keep at least 1 minute so math/loops don’t explode)
        this.totalMinutes = Math.max(1, raw);
        this.intervalMinutes = Math.max(1, intervalMinutes);

        // build a minute-by-minute probability distribution (normalized PDF; bell-ish)
        minuteProbabilities = new double[totalMinutes];
        double mean = (totalMinutes - 1) / 2.0;
        double sigma = Math.max(1.0, totalMinutes / 6.0);
        double sum = 0;
        for (int m = 0; m < totalMinutes; m++) {
            double x = (m - mean) / sigma;
            double pdf = Math.exp(-0.5 * x * x);
            minuteProbabilities[m] = pdf;
            sum += pdf;
        }
        // normalize
        for (int m = 0; m < totalMinutes; m++) {
            minuteProbabilities[m] /= sum;
        }
    }

    /**
     * Returns an array of length totalMinutes where each entry is the exact
     * number of arrivals in that minute (summing to totalPassengers).
     */
    public int[] generatePerMinuteArrivals(Flight flight) {
        int planned = (flight == null) ? 0 : (int) Math.round(flight.getSeats() * flight.getFillPercent());
        planned = Math.max(0, planned);

        int[] arrivals = new int[totalMinutes];
        if (planned == 0) return arrivals;

        double[] raw = new double[totalMinutes];
        int floorSum = 0;
        for (int m = 0; m < totalMinutes; m++) {
            raw[m] = minuteProbabilities[m] * planned;
            arrivals[m] = (int) Math.floor(raw[m]);
            floorSum += arrivals[m];
        }
        int remainder = planned - floorSum;

        // Assign the remainder to minutes with the largest fractional part
        List<Integer> idx = new ArrayList<>(totalMinutes);
        for (int m = 0; m < totalMinutes; m++) idx.add(m);
        idx.sort((a, b) -> Double.compare((raw[b] - arrivals[b]), (raw[a] - arrivals[a])));

        for (int k = 0; k < remainder && k < idx.size(); k++) {
            arrivals[idx.get(k)]++;
        }
        return arrivals;
    }

    /**
     * Aggregates per-minute arrivals into interval buckets.
     * Returns an int[] of length floor(totalMinutes / intervalMinutes).
     */
    public int[] generateArrivals(Flight flight) {
        int[] minuteArr = generatePerMinuteArrivals(flight);
        int nIntervals = Math.max(1, totalMinutes / intervalMinutes);
        int[] bucketed = new int[nIntervals];
        for (int i = 0; i < nIntervals; i++) {
            int sum = 0;
            int start = i * intervalMinutes;
            int end = Math.min(start + intervalMinutes, totalMinutes);
            for (int m = start; m < end; m++) sum += minuteArr[m];
            bucketed[i] = sum;
        }
        return bucketed;
    }

    public int getTotalMinutes() {
        return totalMinutes;
    }

    public int getIntervalMinutes() {
        return intervalMinutes;
    }
}
