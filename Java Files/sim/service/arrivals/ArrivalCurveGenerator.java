package sim.service.arrivals;

import sim.model.ArrivalCurveConfig;
import sim.model.Flight;

public interface ArrivalCurveGenerator {
    int[] buildArrivalsPerMinute(Flight f,
                                 int totalPassengers,
                                 ArrivalCurveConfig cfg,
                                 int arrivalSpanMinutes);
}