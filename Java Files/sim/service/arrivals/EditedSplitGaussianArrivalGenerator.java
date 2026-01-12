package sim.service.arrivals;

import sim.model.ArrivalCurveConfig;
import sim.model.Flight;

import java.util.ArrayList;
import java.util.List;

public class EditedSplitGaussianArrivalGenerator implements ArrivalCurveGenerator {

    @Override
    public int[] buildArrivalsPerMinute(Flight f,
                                        int totalPassengers,
                                        ArrivalCurveConfig cfg,
                                        int arrivalSpanMinutes) {

        if (cfg == null) cfg = ArrivalCurveConfig.legacyDefault();
        cfg.validateAndClamp();

        int close = clamp(cfg.getBoardingCloseMinutesBeforeDeparture(), 0, arrivalSpanMinutes);
        int totalMinutes = arrivalSpanMinutes - close;
        if (totalMinutes <= 0) return new int[0];

        if (totalPassengers <= 0) return new int[totalMinutes];

        int peak = clamp(cfg.getPeakMinutesBeforeDeparture(), close, arrivalSpanMinutes);
        int leftSigma = Math.max(1, cfg.getLeftSigmaMinutes());
        int rightSigma = Math.max(1, cfg.getRightSigmaMinutes());

        int windowStart = clamp(cfg.getWindowStartMinutesBeforeDeparture(), close, arrivalSpanMinutes);

        boolean clampEnabled = cfg.isLateClampEnabled();
        int lateClamp = clamp(cfg.getLateClampMinutesBeforeDeparture(), close, arrivalSpanMinutes);

        double[] w = new double[totalMinutes];
        double sumW = 0.0;

        for (int i = 0; i < totalMinutes; i++) {
            double minutesBeforeDeparture = arrivalSpanMinutes - (i + 0.5);

            // earlier than windowStart => zero
            if (minutesBeforeDeparture > windowStart) {
                w[i] = 0.0;
                continue;
            }

            // later than lateClamp (closer to departure) => zero
            if (clampEnabled && minutesBeforeDeparture < lateClamp) {
                w[i] = 0.0;
                continue;
            }

            double sigma = (minutesBeforeDeparture >= peak) ? leftSigma : rightSigma;
            double z = (minutesBeforeDeparture - peak) / sigma;
            double pdf = Math.exp(-0.5 * z * z);

            w[i] = pdf;
            sumW += pdf;
        }

        if (sumW <= 0.0) {
            int[] fallback = new int[totalMinutes];
            int peakIdx = (int) Math.round(arrivalSpanMinutes - peak);
            peakIdx = clamp(peakIdx, 0, totalMinutes - 1);
            fallback[peakIdx] = totalPassengers;
            return fallback;
        }

        int[] out = new int[totalMinutes];
        double[] raw = new double[totalMinutes];

        int floorSum = 0;
        for (int i = 0; i < totalMinutes; i++) {
            raw[i] = (w[i] / sumW) * totalPassengers;
            out[i] = (int) Math.floor(raw[i]);
            floorSum += out[i];
        }

        int remainder = totalPassengers - floorSum;
        if (remainder > 0) {
            List<Integer> idx = new ArrayList<>(totalMinutes);
            for (int i = 0; i < totalMinutes; i++) idx.add(i);

            idx.sort((a, b) -> Double.compare((raw[b] - out[b]), (raw[a] - out[a])));

            for (int k = 0; k < remainder && k < idx.size(); k++) {
                out[idx.get(k)]++;
            }
        }

        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
