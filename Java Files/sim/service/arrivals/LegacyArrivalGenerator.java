package sim.service.arrivals;

import sim.model.ArrivalCurveConfig;
import sim.model.Flight;

public class LegacyArrivalGenerator implements ArrivalCurveGenerator {

    @Override
    public int[] buildArrivalsPerMinute(Flight f,
                                        int totalPassengers,
                                        ArrivalCurveConfig cfg,
                                        int arrivalSpanMinutes) {

        sim.service.ArrivalGenerator legacy = new sim.service.ArrivalGenerator(arrivalSpanMinutes, 1);
        int[] perMin = legacy.generateArrivals(f);

        if (perMin == null) return new int[0];
        if (totalPassengers < 0) return perMin;

        int sum = 0;
        for (int v : perMin) sum += Math.max(0, v);

        if (sum == totalPassengers) return perMin;
        return rescaleToTotal(perMin, totalPassengers);
    }

    private static int[] rescaleToTotal(int[] original, int targetTotal) {
        int n = original.length;
        int[] out = new int[n];
        if (targetTotal <= 0 || n == 0) return out;

        long sum = 0;
        for (int v : original) sum += Math.max(0, v);

        if (sum <= 0) {
            out[0] = targetTotal;
            return out;
        }

        double scale = targetTotal / (double) sum;

        double[] frac = new double[n];
        int floorSum = 0;

        for (int i = 0; i < n; i++) {
            double raw = Math.max(0, original[i]) * scale;
            int flo = (int) Math.floor(raw);
            out[i] = flo;
            floorSum += flo;
            frac[i] = raw - flo;
        }

        int remaining = targetTotal - floorSum;

        while (remaining > 0) {
            int bestIdx = 0;
            double bestFrac = -1.0;

            for (int i = 0; i < n; i++) {
                if (frac[i] > bestFrac) {
                    bestFrac = frac[i];
                    bestIdx = i;
                }
            }

            out[bestIdx] += 1;
            frac[bestIdx] = -1.0;
            remaining--;
        }

        return out;
    }
}
