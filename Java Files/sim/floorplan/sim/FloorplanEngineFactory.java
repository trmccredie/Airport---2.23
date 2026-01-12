package sim.floorplan.sim;

import sim.floorplan.model.FloorplanProject;
import sim.model.ArrivalCurveConfig;
import sim.model.Flight;
import sim.service.SimulationEngine;
import sim.ui.CheckpointConfig;
import sim.ui.HoldRoomConfig;
import sim.ui.HoldRoomConfigUtil;
import sim.ui.TicketCounterConfig;

import java.util.Collections;
import java.util.List;

/**
 * Builds a SimulationEngine wired to a Floorplan:
 *  - Reads ticket/checkpoint configs from zone metadata when available
 *  - Falls back to reasonable defaults
 *  - Installs FloorplanTravelTimeProvider with the requested walk speed
 *
 * Step 2 note:
 *  - CheckpointConfig.ratePerHour is the source of truth for fixed service time
 *    (engine computes serviceSeconds = round(3600 / ratePerHour)).
 */
public final class FloorplanEngineFactory {

    private FloorplanEngineFactory() {}

    // Defaults used when the floorplan doesn’t specify rates
    // Ticket: 90 pax/hour per counter = 1.5 pax/min
    public static final double DEFAULT_TICKET_RATE_PER_MIN = 1.5;

    // Checkpoint: 300 pax/hour per lane = 5 pax/min (service time = 720ms per pax)
    public static final double DEFAULT_CHECKPOINT_RATE_PER_HR = 300.0;

    public static final int DEFAULT_TRANSIT_DELAY_FALLBACK_MIN = 5;
    public static final int DEFAULT_HOLD_DELAY_FALLBACK_MIN = 5;

    public static SimulationEngine buildFloorplanEngine(
            FloorplanProject project,
            double percentInPerson,
            List<Flight> flights,
            ArrivalCurveConfig curveCfg,
            int arrivalSpanMinutes,
            int intervalMinutes,
            double walkSpeedMps
    ) {
        FloorplanBindings bindings = new FloorplanBindings(project);

        int safeIntervalMinutes = Math.max(1, intervalMinutes);

        // Prefer zone-driven configs; fall back to defaults when absent.
        List<TicketCounterConfig> counterCfgs =
                bindings.buildTicketCounterConfigsFromZones(
                        flights,
                        /*uiFallback*/ null,
                        /*defaultRatePerMin*/ DEFAULT_TICKET_RATE_PER_MIN
                );

        List<CheckpointConfig> checkpointCfgs =
                bindings.buildCheckpointConfigsFromZones(
                        /*uiFallback*/ null,
                        /*defaultRatePerHour*/ DEFAULT_CHECKPOINT_RATE_PER_HR
                );

        // Holdrooms: sensible defaults (1 holdroom per flight)
        List<HoldRoomConfig> holdRooms =
                HoldRoomConfigUtil.buildDefaultHoldRoomConfigs(
                        flights,
                        DEFAULT_HOLD_DELAY_FALLBACK_MIN
                );

        SimulationEngine engine = new SimulationEngine(
                percentInPerson,
                counterCfgs,
                checkpointCfgs,
                arrivalSpanMinutes,
                safeIntervalMinutes,
                DEFAULT_TRANSIT_DELAY_FALLBACK_MIN,
                DEFAULT_HOLD_DELAY_FALLBACK_MIN,
                (flights == null ? Collections.emptyList() : flights),
                holdRooms
        );

        if (curveCfg != null) {
            engine.setArrivalCurveConfig(curveCfg);
        }

        // Travel-time provider (walk speed can be changed later from the UI)
        FloorplanTravelTimeProvider provider = new FloorplanTravelTimeProvider(project, walkSpeedMps);
        engine.setTravelTimeProvider(provider);

        return engine;
    }
}
