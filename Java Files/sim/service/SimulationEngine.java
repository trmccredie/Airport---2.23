// SimulationEngine.java
// STEP 2 UPDATE:
// - FIX: Preserve checkpoint in-service passenger across intervals (no clearing of checkpointServing).
// - IMPROVEMENT: Choose checkpoint lane by minimal *time backlog* (remaining service + queued pax * svcSec).
// - IMPROVEMENT: Spread spawns within the minute with optional 0–59s jitter (seedable).
// - QoL: add RNG seeding, spawn-jitter toggle, and a getter for lane service end times.

package sim.service;

import sim.floorplan.sim.TravelTimeProvider; // optional floorplan travel-time hook
import sim.model.ArrivalCurveConfig;
import sim.model.Flight;
import sim.model.Passenger;
import sim.service.arrivals.ArrivalCurveGenerator;
import sim.service.arrivals.EditedSplitGaussianArrivalGenerator;
import sim.ui.CheckpointConfig;
import sim.ui.GridRenderer;
import sim.ui.HoldRoomConfig;
import sim.ui.TicketCounterConfig;

import java.time.Duration;
import java.time.LocalTime;
import java.util.*;

public class SimulationEngine {
    private final List<Flight> flights;

    // ============================
    // Hold-room configs (physical rooms)
    // ============================
    private final List<HoldRoomConfig> holdRoomConfigs;
    private final Map<Flight, Integer> chosenHoldRoomIndexByFlight = new HashMap<>();

    // Existing held-ups series
    private final Map<Integer, Integer> heldUpsByInterval = new LinkedHashMap<>();

    // Queue totals series (waiting lines only)
    private final Map<Integer, Integer> ticketQueuedByInterval = new LinkedHashMap<>();
    private final Map<Integer, Integer> checkpointQueuedByInterval = new LinkedHashMap<>();
    private final Map<Integer, Integer> holdRoomTotalByInterval = new LinkedHashMap<>();

    // Arrival curve support (minute-based)
    private ArrivalCurveConfig arrivalCurveConfig = ArrivalCurveConfig.legacyDefault();
    private final ArrivalGenerator legacyMinuteGenerator;
    private final ArrivalCurveGenerator editedMinuteGenerator = new EditedSplitGaussianArrivalGenerator();
    private final Map<Flight, int[]> minuteArrivalsMap = new HashMap<>();

    private final Map<Flight, Integer> holdRoomCellSize;

    private final int arrivalSpanMinutes;
    private final int intervalMinutes;
    private final int transitDelayMinutes;    // ticket→checkpoint delay (legacy fallback)
    private final int holdDelayMinutes;       // legacy fallback
    private final int totalIntervals;

    private int currentInterval;

    private final double percentInPerson;

    private final List<TicketCounterConfig> counterConfigs;
    private final List<CheckpointConfig> checkpointConfigs;
    private final int numCheckpoints;
    private final double defaultCheckpointRatePerHour;

    private final LocalTime globalStart;
    private final List<Flight> justClosedFlights = new ArrayList<>();

    private final List<LinkedList<Passenger>> ticketLines;
    private final List<LinkedList<Passenger>> checkpointLines;
    private final List<LinkedList<Passenger>> completedTicketLines;
    private final List<LinkedList<Passenger>> completedCheckpointLines;

    private final List<Map<Flight, Integer>> historyArrivals = new ArrayList<>();
    private final List<Map<Flight, Integer>> historyEnqueuedTicket = new ArrayList<>();
    private final List<Map<Flight, Integer>> historyTicketed = new ArrayList<>();
    private final List<Integer> historyTicketLineSize = new ArrayList<>();
    private final List<Map<Flight, Integer>> historyArrivedToCheckpoint = new ArrayList<>();
    private final List<Integer> historyCPLineSize = new ArrayList<>();
    private final List<Map<Flight, Integer>> historyPassedCheckpoint = new ArrayList<>();
    private final List<List<List<Passenger>>> historyOnlineArrivals = new ArrayList<>();
    private final List<List<List<Passenger>>> historyFromTicketArrivals = new ArrayList<>();

    private final List<LinkedList<Passenger>> holdRoomLines;

    private final List<List<List<Passenger>>> historyServedTicket = new ArrayList<>();
    private final List<List<List<Passenger>>> historyQueuedTicket = new ArrayList<>();
    private final List<List<List<Passenger>>> historyServedCheckpoint = new ArrayList<>();
    private final List<List<List<Passenger>>> historyQueuedCheckpoint = new ArrayList<>();
    private final List<List<List<Passenger>>> historyHoldRooms = new ArrayList<>();

    // === RNG and spawn jitter control ===
    private Random rand = new Random();
    private boolean spawnJitterEnabled = true; // spread arrivals within minute by default

    // Fractional carry (0..1) for per-lane service between intervals.
    // Ticket uses this as before; checkpoint no longer uses it (single-server deterministic service).
    private double[] counterProgress;
    private double[] checkpointProgress;

    // ✅ pending travel maps (ABS SECOND → passengers due to arrive at that node at that second)
    // Keys are absolute seconds since simulation start (globalStart).
    private final Map<Integer, List<Passenger>> pendingToTicket; // spawn -> ticket
    private final Map<Integer, List<Passenger>> pendingToCP;     // spawn/ticket -> checkpoint
    private final Map<Integer, List<Passenger>> pendingToHold;   // checkpoint -> hold

    // ✅ targets (for animation + stable routing)
    private final Map<Passenger, Integer> targetTicketLineByPassenger = new HashMap<>();
    private final Map<Passenger, Integer> targetCheckpointLineByPassenger = new HashMap<>();

    private Passenger[] counterServing;
    private Passenger[] checkpointServing;

    // ✅ Optional floorplan travel time provider
    private TravelTimeProvider travelTimeProvider;

    // ✅ Unified walk-speed (engine = source of truth)
    private double walkSpeedMps = 1.34;

    // ✅ Per-passenger exact completion time (ABS seconds since simulation start)
    private final Map<Passenger, Integer> ticketDoneAbsSecond = new HashMap<>();
    private final Map<Passenger, Integer> checkpointDoneAbsSecond = new HashMap<>();

    // ✅ NEW: Per-passenger checkpoint service start time (ABS seconds)
    private final Map<Passenger, Integer> checkpointStartAbsSecond = new HashMap<>();

    // Optional (helps UI; also snapshotted)
    private final Map<Passenger, Integer> ticketQueueEnterAbsSecond = new HashMap<>();
    private final Map<Passenger, Integer> checkpointQueueEnterAbsSecond = new HashMap<>();
    private final Map<Passenger, Integer> holdRoomEnterAbsSecond = new HashMap<>();

    // ✅ per-lane service end absolute second for checkpoints (0 = idle)
    private int[] checkpointServiceEndAbs;

    // REWIND SUPPORT
    private final List<EngineSnapshot> stateSnapshots = new ArrayList<>();
    private int maxComputedInterval = 0;

    private static final double EPS_RATE = 1e-4;

    private static final class EngineSnapshot {
        final int currentInterval;

        final List<LinkedList<Passenger>> ticketLines;
        final List<LinkedList<Passenger>> completedTicketLines;
        final List<LinkedList<Passenger>> checkpointLines;
        final List<LinkedList<Passenger>> completedCheckpointLines;
        final List<LinkedList<Passenger>> holdRoomLines;

        final double[] counterProgress;
        final double[] checkpointProgress;

        final Map<Integer, List<Passenger>> pendingToTicket;
        final Map<Integer, List<Passenger>> pendingToCP;
        final Map<Integer, List<Passenger>> pendingToHold;

        final Map<Passenger, Integer> targetTicketLineByPassenger;
        final Map<Passenger, Integer> targetCheckpointLineByPassenger;

        final Passenger[] counterServing;
        final Passenger[] checkpointServing;

        final int[] checkpointServiceEndAbs; // NEW

        final List<Flight> justClosedFlights;

        final LinkedHashMap<Integer, Integer> heldUpsByInterval;
        final LinkedHashMap<Integer, Integer> ticketQueuedByInterval;
        final LinkedHashMap<Integer, Integer> checkpointQueuedByInterval;
        final LinkedHashMap<Integer, Integer> holdRoomTotalByInterval;

        final HashMap<Passenger, Integer> ticketDoneAbsSecond;
        final HashMap<Passenger, Integer> checkpointDoneAbsSecond;
        final HashMap<Passenger, Integer> checkpointStartAbsSecond; // NEW

        final HashMap<Passenger, Integer> ticketQueueEnterAbsSecond;
        final HashMap<Passenger, Integer> checkpointQueueEnterAbsSecond;
        final HashMap<Passenger, Integer> holdRoomEnterAbsSecond;

        EngineSnapshot(
                int currentInterval,
                List<LinkedList<Passenger>> ticketLines,
                List<LinkedList<Passenger>> completedTicketLines,
                List<LinkedList<Passenger>> checkpointLines,
                List<LinkedList<Passenger>> completedCheckpointLines,
                List<LinkedList<Passenger>> holdRoomLines,
                double[] counterProgress,
                double[] checkpointProgress,
                Map<Integer, List<Passenger>> pendingToTicket,
                Map<Integer, List<Passenger>> pendingToCP,
                Map<Integer, List<Passenger>> pendingToHold,
                Map<Passenger, Integer> targetTicketLineByPassenger,
                Map<Passenger, Integer> targetCheckpointLineByPassenger,
                Passenger[] counterServing,
                Passenger[] checkpointServing,
                int[] checkpointServiceEndAbs,
                List<Flight> justClosedFlights,
                LinkedHashMap<Integer, Integer> heldUpsByInterval,
                LinkedHashMap<Integer, Integer> ticketQueuedByInterval,
                LinkedHashMap<Integer, Integer> checkpointQueuedByInterval,
                LinkedHashMap<Integer, Integer> holdRoomTotalByInterval,
                HashMap<Passenger, Integer> ticketDoneAbsSecond,
                HashMap<Passenger, Integer> checkpointDoneAbsSecond,
                HashMap<Passenger, Integer> checkpointStartAbsSecond,
                HashMap<Passenger, Integer> ticketQueueEnterAbsSecond,
                HashMap<Passenger, Integer> checkpointQueueEnterAbsSecond,
                HashMap<Passenger, Integer> holdRoomEnterAbsSecond
        ) {
            this.currentInterval = currentInterval;
            this.ticketLines = ticketLines;
            this.completedTicketLines = completedTicketLines;
            this.checkpointLines = checkpointLines;
            this.completedCheckpointLines = completedCheckpointLines;
            this.holdRoomLines = holdRoomLines;

            this.counterProgress = counterProgress;
            this.checkpointProgress = checkpointProgress;

            this.pendingToTicket = pendingToTicket;
            this.pendingToCP = pendingToCP;
            this.pendingToHold = pendingToHold;

            this.targetTicketLineByPassenger = targetTicketLineByPassenger;
            this.targetCheckpointLineByPassenger = targetCheckpointLineByPassenger;

            this.counterServing = counterServing;
            this.checkpointServing = checkpointServing;

            this.checkpointServiceEndAbs = checkpointServiceEndAbs;

            this.justClosedFlights = justClosedFlights;

            this.heldUpsByInterval = heldUpsByInterval;
            this.ticketQueuedByInterval = ticketQueuedByInterval;
            this.checkpointQueuedByInterval = checkpointQueuedByInterval;
            this.holdRoomTotalByInterval = holdRoomTotalByInterval;

            this.ticketDoneAbsSecond = ticketDoneAbsSecond;
            this.checkpointDoneAbsSecond = checkpointDoneAbsSecond;
            this.checkpointStartAbsSecond = checkpointStartAbsSecond;

            this.ticketQueueEnterAbsSecond = ticketQueueEnterAbsSecond;
            this.checkpointQueueEnterAbsSecond = checkpointQueueEnterAbsSecond;
            this.holdRoomEnterAbsSecond = holdRoomEnterAbsSecond;
        }
    }

    // Constructors preserved
    public SimulationEngine(double percentInPerson,
                            List<TicketCounterConfig> counterConfigs,
                            int numCheckpoints,
                            double checkpointRatePerHour,
                            int arrivalSpanMinutes,
                            int intervalMinutes,
                            int transitDelayMinutes,
                            int holdDelayMinutes,
                            List<Flight> flights) {
        this(percentInPerson, counterConfigs,
                buildDefaultCheckpointConfigs(numCheckpoints, checkpointRatePerHour),
                arrivalSpanMinutes, intervalMinutes, transitDelayMinutes, holdDelayMinutes,
                flights, null);
    }

    public SimulationEngine(double percentInPerson,
                            List<TicketCounterConfig> counterConfigs,
                            int numCheckpoints,
                            double checkpointRatePerHour,
                            int arrivalSpanMinutes,
                            int intervalMinutes,
                            int transitDelayMinutes,
                            int holdDelayMinutes,
                            List<Flight> flights,
                            List<HoldRoomConfig> holdRoomConfigs) {
        this(percentInPerson, counterConfigs,
                buildDefaultCheckpointConfigs(numCheckpoints, checkpointRatePerHour),
                arrivalSpanMinutes, intervalMinutes, transitDelayMinutes, holdDelayMinutes,
                flights, holdRoomConfigs);
    }

    public SimulationEngine(double percentInPerson,
                            List<TicketCounterConfig> counterConfigs,
                            List<CheckpointConfig> checkpointConfigs,
                            int arrivalSpanMinutes,
                            int intervalMinutes,
                            int transitDelayMinutes,
                            int holdDelayMinutes,
                            List<Flight> flights,
                            List<HoldRoomConfig> holdRoomConfigs) {

        this.percentInPerson = percentInPerson;

        this.flights = (flights == null) ? new ArrayList<>() : flights;
        this.counterConfigs = (counterConfigs == null) ? new ArrayList<>() : counterConfigs;

        List<CheckpointConfig> cps = (checkpointConfigs == null) ? new ArrayList<>() : new ArrayList<>(checkpointConfigs);
        if (cps.isEmpty()) {
            CheckpointConfig fallback = new CheckpointConfig(1);
            fallback.setRatePerHour(0.0);
            cps.add(fallback);
        }
        this.checkpointConfigs = cps;
        this.numCheckpoints = this.checkpointConfigs.size();

        this.defaultCheckpointRatePerHour = (this.checkpointConfigs.isEmpty())
                ? 0.0
                : this.checkpointConfigs.get(0).getRatePerHour();

        this.arrivalSpanMinutes = arrivalSpanMinutes;
        this.intervalMinutes = Math.max(1, intervalMinutes);
        this.transitDelayMinutes = transitDelayMinutes;
        this.holdDelayMinutes = holdDelayMinutes;

        if (holdRoomConfigs != null && !holdRoomConfigs.isEmpty()) {
            this.holdRoomConfigs = new ArrayList<>(holdRoomConfigs);
        } else {
            this.holdRoomConfigs = buildDefaultHoldRoomConfigs(this.flights, holdDelayMinutes);
        }
        if (this.holdRoomConfigs.isEmpty()) {
            HoldRoomConfig cfg = new HoldRoomConfig(1);
            cfg.setWalkTime(Math.max(0, holdDelayMinutes), 0);
            this.holdRoomConfigs.add(cfg);
        }

        LocalTime firstDep = this.flights.stream()
                .map(Flight::getDepartureTime)
                .min(LocalTime::compareTo)
                .orElse(LocalTime.MIDNIGHT);
        this.globalStart = firstDep.minusMinutes(arrivalSpanMinutes);

        long maxDepartureMinutes = this.flights.stream()
                .mapToLong(f -> Duration.between(globalStart, f.getDepartureTime()).toMinutes())
                .max().orElse(0);

        // totalIntervals counts engine intervals; currentInterval indexes these.
        this.totalIntervals = (int) (maxDepartureMinutes / this.intervalMinutes) + 1;

        // Force minute-based arrival curves regardless of engine interval size:
        this.legacyMinuteGenerator = new ArrivalGenerator(arrivalSpanMinutes, 1);
        setArrivalCurveConfig(ArrivalCurveConfig.legacyDefault());

        computeChosenHoldRooms();

        holdRoomCellSize = new HashMap<>();
        for (Flight f : this.flights) {
            int total = (int) Math.round(f.getSeats() * f.getFillPercent());
            int bestCell = GridRenderer.MIN_CELL_SIZE;
            for (int rows = 1; rows <= Math.max(1, total); rows++) {
                int cols = (total + rows - 1) / rows;
                int cellByRows = GridRenderer.HOLD_BOX_SIZE / rows;
                int cellByCols = GridRenderer.HOLD_BOX_SIZE / cols;
                int cell = Math.min(cellByRows, cellByCols);
                bestCell = Math.max(bestCell, cell);
            }
            holdRoomCellSize.put(f, bestCell);
        }

        this.currentInterval = 0;

        ticketLines = new ArrayList<>();
        completedTicketLines = new ArrayList<>();
        for (int i = 0; i < this.counterConfigs.size(); i++) {
            ticketLines.add(new LinkedList<>());
            completedTicketLines.add(new LinkedList<>());
        }

        checkpointLines = new ArrayList<>();
        completedCheckpointLines = new ArrayList<>();
        for (int i = 0; i < this.numCheckpoints; i++) {
            checkpointLines.add(new LinkedList<>());
            completedCheckpointLines.add(new LinkedList<>());
        }

        holdRoomLines = new ArrayList<>();
        for (int i = 0; i < this.holdRoomConfigs.size(); i++) {
            holdRoomLines.add(new LinkedList<>());
        }

        counterProgress = new double[this.counterConfigs.size()];
        checkpointProgress = new double[this.numCheckpoints]; // retained for compat (unused by CP now)

        pendingToTicket = new HashMap<>();
        pendingToCP = new HashMap<>();
        pendingToHold = new HashMap<>();

        counterServing = new Passenger[this.counterConfigs.size()];
        checkpointServing = new Passenger[this.numCheckpoints];
        checkpointServiceEndAbs = new int[this.numCheckpoints];

        captureSnapshot0();
    }

    // === RNG / reproducibility ===
    public void setRandomSeed(long seed) {
        this.rand = new Random(seed);
    }

    public void setSpawnJitterEnabled(boolean enabled) {
        this.spawnJitterEnabled = enabled;
    }

    // ✅ Optional floorplan travel time hook
    public void setTravelTimeProvider(TravelTimeProvider p) {
        this.travelTimeProvider = p;
        if (this.travelTimeProvider != null) {
            try {
                this.travelTimeProvider.setWalkSpeedMps(walkSpeedMps);
            } catch (Throwable ignored) {
                try {
                    java.lang.reflect.Method m = this.travelTimeProvider.getClass()
                            .getMethod("setWalkSpeedMps", double.class);
                    m.invoke(this.travelTimeProvider, walkSpeedMps);
                } catch (Throwable ignored2) { }
            }
        }
    }

    public TravelTimeProvider getTravelTimeProvider() {
        return travelTimeProvider;
    }

    // ✅ Unified walk-speed (engine = source of truth). Also forwards to provider if present.
    public double getWalkSpeedMps() {
        return walkSpeedMps;
    }

    public void setWalkSpeedMps(double mps) {
        double v = (Double.isFinite(mps) ? Math.max(0.20, Math.min(3.50, mps)) : 1.34);
        this.walkSpeedMps = v;
        if (travelTimeProvider != null) {
            try {
                travelTimeProvider.setWalkSpeedMps(v);
            } catch (Throwable ignored) {
                try {
                    java.lang.reflect.Method sm = travelTimeProvider.getClass()
                            .getMethod("setWalkSpeedMps", double.class);
                    sm.invoke(travelTimeProvider, v);
                } catch (Throwable ignored2) { }
            }
        }
    }

    // ✅ Expose interval length in seconds (for UI clock)
    public int getIntervalSeconds() {
        return Math.max(1, intervalMinutes) * 60;
    }

    // Alias used by some panels
    public int getIntervalMinutes() {
        return Math.max(1, intervalMinutes);
    }

    // ✅ best-effort reflection call for optional spawn legs
    private int callOptionalProviderMinutes(String methodName, int idx) {
        if (travelTimeProvider == null) return -1;
        try {
            java.lang.reflect.Method m = travelTimeProvider.getClass().getMethod(methodName, int.class);
            Object out = m.invoke(travelTimeProvider, idx);
            if (out instanceof Number) return ((Number) out).intValue();
        } catch (Throwable ignored) { }
        return -1;
    }

    // Arrival curve API
    public void setArrivalCurveConfig(ArrivalCurveConfig cfg) {
        ArrivalCurveConfig copy = copyCfg(cfg);
        copy.setBoardingCloseMinutesBeforeDeparture(ArrivalCurveConfig.DEFAULT_BOARDING_CLOSE);
        copy.validateAndClamp();
        this.arrivalCurveConfig = copy;
        rebuildMinuteArrivalsMap();
    }

    public ArrivalCurveConfig getArrivalCurveConfigCopy() {
        return copyCfg(this.arrivalCurveConfig);
    }

    private static ArrivalCurveConfig copyCfg(ArrivalCurveConfig src) {
        if (src == null) return ArrivalCurveConfig.legacyDefault();

        ArrivalCurveConfig c = ArrivalCurveConfig.legacyDefault();
        c.setLegacyMode(src.isLegacyMode());
        c.setPeakMinutesBeforeDeparture(src.getPeakMinutesBeforeDeparture());
        c.setLeftSigmaMinutes(src.getLeftSigmaMinutes());
        c.setRightSigmaMinutes(src.getRightSigmaMinutes());
        c.setLateClampEnabled(src.isLateClampEnabled());
        c.setLateClampMinutesBeforeDeparture(src.getLateClampMinutesBeforeDeparture());
        c.setWindowStartMinutesBeforeDeparture(src.getWindowStartMinutesBeforeDeparture());
        c.setBoardingCloseMinutesBeforeDeparture(src.getBoardingCloseMinutesBeforeDeparture());
        c.validateAndClamp();
        return c;
    }

    private void rebuildMinuteArrivalsMap() {
        minuteArrivalsMap.clear();
        for (Flight f : flights) {
            int totalPassengers = (int) Math.round(f.getSeats() * f.getFillPercent());
            int[] perMin;
            if (arrivalCurveConfig == null || arrivalCurveConfig.isLegacyMode()) {
                perMin = legacyMinuteGenerator.generateArrivals(f);
            } else {
                perMin = editedMinuteGenerator.buildArrivalsPerMinute(
                        f,
                        totalPassengers,
                        arrivalCurveConfig,
                        arrivalSpanMinutes
                );
            }
            minuteArrivalsMap.put(f, (perMin == null) ? new int[0] : perMin);
        }
    }

    private static List<CheckpointConfig> buildDefaultCheckpointConfigs(int numCheckpoints, double checkpointRatePerHour) {
        int n = Math.max(0, numCheckpoints);
        double rateHr = Math.max(0.0, checkpointRatePerHour);

        List<CheckpointConfig> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CheckpointConfig cfg = new CheckpointConfig(i + 1);
            cfg.setRatePerHour(rateHr);
            list.add(cfg);
        }

        if (list.isEmpty()) {
            CheckpointConfig cfg = new CheckpointConfig(1);
            cfg.setRatePerHour(0.0);
            list.add(cfg);
        }
        return list;
    }

    private static List<HoldRoomConfig> buildDefaultHoldRoomConfigs(List<Flight> flights, int holdDelayMinutes) {
        List<HoldRoomConfig> list = new ArrayList<>();
        if (flights == null) return list;

        for (int i = 0; i < flights.size(); i++) {
            Flight f = flights.get(i);
            HoldRoomConfig cfg = new HoldRoomConfig(i + 1);
            cfg.setWalkTime(Math.max(0, holdDelayMinutes), 0);
            if (f != null) cfg.setAllowedFlights(Collections.singletonList(f));
            list.add(cfg);
        }
        return list;
    }

    private void computeChosenHoldRooms() {
        chosenHoldRoomIndexByFlight.clear();

        int roomCount = holdRoomConfigs.size();
        if (roomCount <= 0) return;

        for (Flight f : flights) {
            List<Integer> candidates = new ArrayList<>();
            int bestSeconds = Integer.MAX_VALUE;

            for (int r = 0; r < roomCount; r++) {
                HoldRoomConfig cfg = holdRoomConfigs.get(r);
                if (cfg == null) continue;
                if (!cfg.accepts(f)) continue;

                int ws = safeWalkSeconds(cfg);
                if (ws < bestSeconds) {
                    bestSeconds = ws;
                    candidates.clear();
                    candidates.add(r);
                } else if (ws == bestSeconds) {
                    candidates.add(r);
                }
            }

            int chosen;
            if (!candidates.isEmpty()) {
                chosen = candidates.get(rand.nextInt(candidates.size()));
            } else {
                int acceptAll = -1;
                for (int r = 0; r < roomCount; r++) {
                    HoldRoomConfig cfg = holdRoomConfigs.get(r);
                    if (cfg != null && cfg.getAllowedFlightNumbers().isEmpty()) {
                        acceptAll = r;
                        break;
                    }
                }
                chosen = (acceptAll >= 0) ? acceptAll : 0;
            }

            chosenHoldRoomIndexByFlight.put(f, clamp(chosen, 0, roomCount - 1));
        }
    }

    private int safeWalkSeconds(HoldRoomConfig cfg) {
        if (cfg == null) return Math.max(0, holdDelayMinutes) * 60;
        return Math.max(0, cfg.getWalkSecondsFromCheckpoint());
    }

    private int getBoardingCloseIdxMinutes(Flight f) {
        return (int) Duration.between(
                globalStart,
                f.getDepartureTime().minusMinutes(ArrivalCurveConfig.DEFAULT_BOARDING_CLOSE)
        ).toMinutes();
    }

    private int getDepartureIdxMinutes(Flight f) {
        return (int) Duration.between(
                globalStart,
                f.getDepartureTime()
        ).toMinutes();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void inc(Map<Flight, Integer> map, Flight f, int delta) {
        if (map == null || f == null || delta == 0) return;
        map.put(f, map.getOrDefault(f, 0) + delta);
    }

    private static Map<Flight, Integer> mapCopy(Map<Flight, Integer> m) {
        return (m == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(m);
    }

    // ---- Rates (ticket = per-second accrual; checkpoint = fixed time per pax)
    private double getTicketRatePerSecond(int counterIdx) {
        if (counterIdx < 0 || counterIdx >= counterConfigs.size()) return 0.0;
        // TicketCounterConfig.getRate() is "pax per minute"
        return Math.max(0.0, counterConfigs.get(counterIdx).getRate()) / 60.0;
    }

    private int getCheckpointServiceSeconds(int checkpointIdx) {
        if (checkpointIdx < 0 || checkpointIdx >= checkpointConfigs.size()) return Integer.MAX_VALUE;
        double perHour = Math.max(0.0, checkpointConfigs.get(checkpointIdx).getRatePerHour());
        double svc = 3600.0 / Math.max(EPS_RATE, perHour);
        int secs = Math.max(1, (int) Math.round(svc));
        return secs;
    }

    // ---- Travel seconds helpers (schedule in absolute seconds) ----
    private int travelSecondsSpawnToTicket(int ticketIdx) {
        int min = callOptionalProviderMinutes("minutesSpawnToTicket", ticketIdx);
        int sec = (min > 0) ? min * 60 : -1;
        if (sec <= 0) sec = Math.max(0, transitDelayMinutes) * 60;
        return Math.max(1, sec);
    }

    private int travelSecondsSpawnToCheckpoint(int checkpointIdx) {
        int min = callOptionalProviderMinutes("minutesSpawnToCheckpoint", checkpointIdx);
        int sec = (min > 0) ? min * 60 : -1;
        if (sec <= 0) sec = Math.max(0, transitDelayMinutes) * 60;
        return Math.max(1, sec);
    }

    private int travelSecondsTicketToCheckpoint(int ticketIdx, int checkpointIdx) {
        int sec = -1;
        if (travelTimeProvider != null) {
            try {
                int min = travelTimeProvider.minutesTicketToCheckpoint(ticketIdx, checkpointIdx);
                sec = (min > 0) ? min * 60 : -1;
            } catch (Throwable ignored) { }
        }
        if (sec <= 0) sec = Math.max(0, transitDelayMinutes) * 60;
        return Math.max(1, sec);
    }

    private int travelSecondsCheckpointToHold(int checkpointIdx, int holdRoomIdx) {
        int sec = -1;
        if (travelTimeProvider != null) {
            try {
                int min = travelTimeProvider.minutesCheckpointToHold(checkpointIdx, holdRoomIdx);
                sec = (min > 0) ? min * 60 : -1;
            } catch (Throwable ignored) { }
        }
        if (sec <= 0) {
            // Prefer configured hold-room walk seconds if present; else fallback holdDelayMinutes.
            int ws = safeWalkSeconds(holdRoomConfigs.get(clamp(holdRoomIdx, 0, holdRoomConfigs.size() - 1)));
            sec = (ws > 0) ? ws : (Math.max(0, holdDelayMinutes) * 60);
        }
        return Math.max(1, sec);
    }

    // ---- Reflection helpers: set optional new fields on Passenger if they exist ----
    private static void tryInvokeIntSetter(Passenger p, String methodName, int value) {
        if (p == null) return;
        try {
            java.lang.reflect.Method m = p.getClass().getMethod(methodName, int.class);
            m.invoke(p, value);
        } catch (Throwable ignored) { }
    }

    // Snapshots
    private void captureSnapshot0() {
        stateSnapshots.clear();

        heldUpsByInterval.clear();
        ticketQueuedByInterval.clear();
        checkpointQueuedByInterval.clear();
        holdRoomTotalByInterval.clear();

        justClosedFlights.clear();

        targetTicketLineByPassenger.clear();
        targetCheckpointLineByPassenger.clear();

        ticketDoneAbsSecond.clear();
        checkpointDoneAbsSecond.clear();
        checkpointStartAbsSecond.clear();
        ticketQueueEnterAbsSecond.clear();
        checkpointQueueEnterAbsSecond.clear();
        holdRoomEnterAbsSecond.clear();

        Arrays.fill(counterServing, null);
        // DO NOT clear checkpointServing or service-end markers here; this is the initial state.
        Arrays.fill(checkpointServing, null);
        Arrays.fill(checkpointServiceEndAbs, 0);

        pendingToTicket.clear();
        pendingToCP.clear();
        pendingToHold.clear();

        recordQueueTotalsForCurrentInterval();

        EngineSnapshot s0 = makeSnapshot();
        stateSnapshots.add(s0);
        maxComputedInterval = 0;
    }

    private EngineSnapshot makeSnapshot() {
        return new EngineSnapshot(
                currentInterval,
                deepCopyLinkedLists(ticketLines),
                deepCopyLinkedLists(completedTicketLines),
                deepCopyLinkedLists(checkpointLines),
                deepCopyLinkedLists(completedCheckpointLines),
                deepCopyLinkedLists(holdRoomLines),
                Arrays.copyOf(counterProgress, counterProgress.length),
                Arrays.copyOf(checkpointProgress, checkpointProgress.length),
                deepCopyPendingMap(pendingToTicket),
                deepCopyPendingMap(pendingToCP),
                deepCopyPendingMap(pendingToHold),
                new HashMap<>(targetTicketLineByPassenger),
                new HashMap<>(targetCheckpointLineByPassenger),
                Arrays.copyOf(counterServing, counterServing.length),
                Arrays.copyOf(checkpointServing, checkpointServing.length),
                Arrays.copyOf(checkpointServiceEndAbs, checkpointServiceEndAbs.length),
                new ArrayList<>(justClosedFlights),
                new LinkedHashMap<>(heldUpsByInterval),
                new LinkedHashMap<>(ticketQueuedByInterval),
                new LinkedHashMap<>(checkpointQueuedByInterval),
                new LinkedHashMap<>(holdRoomTotalByInterval),
                new HashMap<>(ticketDoneAbsSecond),
                new HashMap<>(checkpointDoneAbsSecond),
                new HashMap<>(checkpointStartAbsSecond),
                new HashMap<>(ticketQueueEnterAbsSecond),
                new HashMap<>(checkpointQueueEnterAbsSecond),
                new HashMap<>(holdRoomEnterAbsSecond)
        );
    }

    private void appendSnapshotAfterInterval() {
        EngineSnapshot snap = makeSnapshot();

        if (currentInterval < stateSnapshots.size()) {
            stateSnapshots.set(currentInterval, snap);
        } else {
            stateSnapshots.add(snap);
        }
        maxComputedInterval = Math.max(maxComputedInterval, currentInterval);
    }

    private void restoreSnapshot(int targetInterval) {
        int t = clamp(targetInterval, 0, maxComputedInterval);
        EngineSnapshot s = stateSnapshots.get(t);

        this.currentInterval = s.currentInterval;

        restoreLinkedListsInPlace(ticketLines, s.ticketLines);
        restoreLinkedListsInPlace(completedTicketLines, s.completedTicketLines);
        restoreLinkedListsInPlace(checkpointLines, s.checkpointLines);
        restoreLinkedListsInPlace(completedCheckpointLines, s.completedCheckpointLines);
        restoreLinkedListsInPlace(holdRoomLines, s.holdRoomLines);

        if (this.counterProgress == null || this.counterProgress.length != s.counterProgress.length) {
            this.counterProgress = Arrays.copyOf(s.counterProgress, s.counterProgress.length);
        } else {
            System.arraycopy(s.counterProgress, 0, this.counterProgress, 0, s.counterProgress.length);
        }

        if (this.checkpointProgress == null || this.checkpointProgress.length != s.checkpointProgress.length) {
            this.checkpointProgress = Arrays.copyOf(s.checkpointProgress, s.checkpointProgress.length);
        } else {
            System.arraycopy(s.checkpointProgress, 0, this.checkpointProgress, 0, s.checkpointProgress.length);
        }

        this.pendingToTicket.clear();
        this.pendingToTicket.putAll(deepCopyPendingMap(s.pendingToTicket));

        this.pendingToCP.clear();
        this.pendingToCP.putAll(deepCopyPendingMap(s.pendingToCP));

        this.pendingToHold.clear();
        this.pendingToHold.putAll(deepCopyPendingMap(s.pendingToHold));

        this.targetTicketLineByPassenger.clear();
        this.targetTicketLineByPassenger.putAll(new HashMap<>(s.targetTicketLineByPassenger));

        this.targetCheckpointLineByPassenger.clear();
        this.targetCheckpointLineByPassenger.putAll(new HashMap<>(s.targetCheckpointLineByPassenger));

        if (this.counterServing == null || this.counterServing.length != s.counterServing.length) {
            this.counterServing = Arrays.copyOf(s.counterServing, s.counterServing.length);
        } else {
            System.arraycopy(s.counterServing, 0, this.counterServing, 0, s.counterServing.length);
        }

        if (this.checkpointServing == null || this.checkpointServing.length != s.checkpointServing.length) {
            this.checkpointServing = Arrays.copyOf(s.checkpointServing, s.checkpointServing.length);
        } else {
            System.arraycopy(s.checkpointServing, 0, this.checkpointServing, 0, s.checkpointServing.length);
        }

        if (this.checkpointServiceEndAbs == null || this.checkpointServiceEndAbs.length != s.checkpointServiceEndAbs.length) {
            this.checkpointServiceEndAbs = Arrays.copyOf(s.checkpointServiceEndAbs, s.checkpointServiceEndAbs.length);
        } else {
            System.arraycopy(s.checkpointServiceEndAbs, 0, this.checkpointServiceEndAbs, 0, s.checkpointServiceEndAbs.length);
        }

        this.justClosedFlights.clear();
        this.justClosedFlights.addAll(s.justClosedFlights);

        this.heldUpsByInterval.clear();
        this.heldUpsByInterval.putAll(s.heldUpsByInterval);

        this.ticketQueuedByInterval.clear();
        this.ticketQueuedByInterval.putAll(s.ticketQueuedByInterval);

        this.checkpointQueuedByInterval.clear();
        this.checkpointQueuedByInterval.putAll(s.checkpointQueuedByInterval);

        this.holdRoomTotalByInterval.clear();
        this.holdRoomTotalByInterval.putAll(s.holdRoomTotalByInterval);

        this.ticketDoneAbsSecond.clear();
        this.ticketDoneAbsSecond.putAll(s.ticketDoneAbsSecond);

        this.checkpointDoneAbsSecond.clear();
        this.checkpointDoneAbsSecond.putAll(s.checkpointDoneAbsSecond);

        this.checkpointStartAbsSecond.clear();
        this.checkpointStartAbsSecond.putAll(s.checkpointStartAbsSecond);

        this.ticketQueueEnterAbsSecond.clear();
        this.ticketQueueEnterAbsSecond.putAll(s.ticketQueueEnterAbsSecond);

        this.checkpointQueueEnterAbsSecond.clear();
        this.checkpointQueueEnterAbsSecond.putAll(s.checkpointQueueEnterAbsSecond);

        this.holdRoomEnterAbsSecond.clear();
        this.holdRoomEnterAbsSecond.putAll(s.holdRoomEnterAbsSecond);
    }

    // Rewind API
    public boolean canRewind() { return currentInterval > 0; }
    public boolean canFastForward() { return currentInterval < maxComputedInterval; }
    public int getMaxComputedInterval() { return maxComputedInterval; }

    public void goToInterval(int targetInterval) { restoreSnapshot(targetInterval); }
    public void rewindOneInterval() { if (canRewind()) restoreSnapshot(currentInterval - 1); }

    public void fastForwardOneInterval() {
        if (canFastForward()) restoreSnapshot(currentInterval + 1);
        else computeNextInterval();
    }

    public void computeNextInterval() {
        if (currentInterval >= totalIntervals) return;

        if ((currentInterval + 1) <= maxComputedInterval) {
            restoreSnapshot(currentInterval + 1);
            return;
        }

        simulateInterval();
    }

    public void runAllIntervals() {
        currentInterval = 0;

        clearHistory();

        heldUpsByInterval.clear();
        ticketQueuedByInterval.clear();
        checkpointQueuedByInterval.clear();
        holdRoomTotalByInterval.clear();

        justClosedFlights.clear();

        targetTicketLineByPassenger.clear();
        targetCheckpointLineByPassenger.clear();

        ticketDoneAbsSecond.clear();
        checkpointDoneAbsSecond.clear();
        checkpointStartAbsSecond.clear();
        ticketQueueEnterAbsSecond.clear();
        checkpointQueueEnterAbsSecond.clear();
        holdRoomEnterAbsSecond.clear();

        ticketLines.forEach(LinkedList::clear);
        completedTicketLines.forEach(LinkedList::clear);
        checkpointLines.forEach(LinkedList::clear);
        completedCheckpointLines.forEach(LinkedList::clear);
        holdRoomLines.forEach(LinkedList::clear);
        Arrays.fill(counterProgress, 0);
        Arrays.fill(checkpointProgress, 0);

        pendingToTicket.clear();
        pendingToCP.clear();
        pendingToHold.clear();

        Arrays.fill(counterServing, null);
        // DO NOT clear checkpointServing here; carry-over across full-run reset is fine to clear though.
        Arrays.fill(checkpointServing, null);
        Arrays.fill(checkpointServiceEndAbs, 0);

        captureSnapshot0();

        while (currentInterval < totalIntervals) {
            simulateInterval();
        }
    }

    // Boarding close mark missed
    private void handleBoardingCloseMarkMissed(Flight f) {
        if (f == null) return;
        if (!justClosedFlights.contains(f)) justClosedFlights.add(f);

        int chosenRoom = chosenHoldRoomIndexByFlight.getOrDefault(f, 0);
        chosenRoom = clamp(chosenRoom, 0, holdRoomLines.size() - 1);

        Set<Passenger> inChosen = new HashSet<>();
        for (Passenger p : holdRoomLines.get(chosenRoom)) {
            if (p != null && p.getFlight() == f) inChosen.add(p);
        }

        markMissedNotInChosen(ticketLines, f, inChosen);
        markMissedNotInChosen(completedTicketLines, f, inChosen);
        markMissedNotInChosen(checkpointLines, f, inChosen);
        markMissedNotInChosen(completedCheckpointLines, f, inChosen);

        purgeFromPendingMap(pendingToTicket, f, inChosen);
        purgeFromPendingMap(pendingToCP, f, inChosen);
        purgeFromPendingMap(pendingToHold, f, inChosen);

        for (int i = 0; i < counterServing.length; i++) {
            Passenger p = counterServing[i];
            if (p != null && p.getFlight() == f && !inChosen.contains(p)) p.setMissed(true);
        }
        for (int i = 0; i < checkpointServing.length; i++) {
            Passenger p = checkpointServing[i];
            if (p != null && p.getFlight() == f && !inChosen.contains(p)) p.setMissed(true);
        }
    }

    private void markMissedNotInChosen(List<LinkedList<Passenger>> lists, Flight f, Set<Passenger> inChosen) {
        for (LinkedList<Passenger> line : lists) {
            for (Passenger p : line) {
                if (p != null && p.getFlight() == f && !inChosen.contains(p)) p.setMissed(true);
            }
        }
    }

    private void purgeFromPendingMap(Map<Integer, List<Passenger>> pending, Flight f, Set<Passenger> inChosen) {
        Iterator<Map.Entry<Integer, List<Passenger>>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, List<Passenger>> e = it.next();
            List<Passenger> list = e.getValue();
            if (list == null) continue;

            list.removeIf(p -> {
                if (p != null && p.getFlight() == f && !inChosen.contains(p)) {
                    p.setMissed(true);
                    targetTicketLineByPassenger.remove(p);
                    targetCheckpointLineByPassenger.remove(p);
                    ticketDoneAbsSecond.remove(p);
                    checkpointDoneAbsSecond.remove(p);
                    checkpointStartAbsSecond.remove(p);
                    ticketQueueEnterAbsSecond.remove(p);
                    checkpointQueueEnterAbsSecond.remove(p);
                    holdRoomEnterAbsSecond.remove(p);
                    return true;
                }
                return false;
            });

            if (list.isEmpty()) it.remove();
        }
    }

    // close clear (non-hold)
    private void clearFlightFromNonHoldAreas(Flight f) {
        for (LinkedList<Passenger> line : ticketLines) line.removeIf(p -> p != null && p.getFlight() == f);
        for (LinkedList<Passenger> line : completedTicketLines) line.removeIf(p -> p != null && p.getFlight() == f);
        for (LinkedList<Passenger> line : checkpointLines) line.removeIf(p -> p != null && p.getFlight() == f);
        for (LinkedList<Passenger> line : completedCheckpointLines) line.removeIf(p -> p != null && p.getFlight() == f);

        purgeAllFromPendingMap(pendingToTicket, f);
        purgeAllFromPendingMap(pendingToCP, f);
        purgeAllFromPendingMap(pendingToHold, f);

        for (int i = 0; i < counterServing.length; i++) {
            Passenger p = counterServing[i];
            if (p != null && p.getFlight() == f) counterServing[i] = null;
        }
        for (int i = 0; i < checkpointServing.length; i++) {
            Passenger p = checkpointServing[i];
            if (p != null && p.getFlight() == f) checkpointServing[i] = null;
        }
        Arrays.fill(checkpointServiceEndAbs, 0);

        targetTicketLineByPassenger.keySet().removeIf(p -> p != null && p.getFlight() == f);
        targetCheckpointLineByPassenger.keySet().removeIf(p -> p != null && p.getFlight() == f);

        ticketDoneAbsSecond.keySet().removeIf(p -> p != null && p.getFlight() == f);
        checkpointDoneAbsSecond.keySet().removeIf(p -> p != null && p.getFlight() == f);
        checkpointStartAbsSecond.keySet().removeIf(p -> p != null && p.getFlight() == f);

        ticketQueueEnterAbsSecond.keySet().removeIf(p -> p != null && p.getFlight() == f);
        checkpointQueueEnterAbsSecond.keySet().removeIf(p -> p != null && p.getFlight() == f);
        holdRoomEnterAbsSecond.keySet().removeIf(p -> p != null && p.getFlight() == f);
    }

    private void purgeAllFromPendingMap(Map<Integer, List<Passenger>> pending, Flight f) {
        Iterator<Map.Entry<Integer, List<Passenger>>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, List<Passenger>> e = it.next();
            List<Passenger> list = e.getValue();
            if (list == null) continue;

            list.removeIf(p -> {
                if (p != null && p.getFlight() == f) {
                    targetTicketLineByPassenger.remove(p);
                    targetCheckpointLineByPassenger.remove(p);
                    ticketDoneAbsSecond.remove(p);
                    checkpointDoneAbsSecond.remove(p);
                    checkpointStartAbsSecond.remove(p);
                    ticketQueueEnterAbsSecond.remove(p);
                    checkpointQueueEnterAbsSecond.remove(p);
                    holdRoomEnterAbsSecond.remove(p);
                    return true;
                }
                return false;
            });

            if (list.isEmpty()) it.remove();
        }
    }

    // departure clear (hold rooms)
    private void clearFlightFromHoldRooms(Flight f) {
        for (LinkedList<Passenger> room : holdRoomLines) {
            room.removeIf(p -> p != null && p.getFlight() == f);
        }
        ticketDoneAbsSecond.keySet().removeIf(p -> p != null && p.getFlight() == f);
        checkpointDoneAbsSecond.keySet().removeIf(p -> p != null && p.getFlight() == f);
        checkpointStartAbsSecond.keySet().removeIf(p -> p != null && p.getFlight() == f);
        ticketQueueEnterAbsSecond.keySet().removeIf(p -> p != null && p.getFlight() == f);
        checkpointQueueEnterAbsSecond.keySet().removeIf(p -> p != null && p.getFlight() == f);
        holdRoomEnterAbsSecond.keySet().removeIf(p -> p != null && p.getFlight() == f);
    }

    // queue helpers
    private Passenger takeFirstNotMissed(LinkedList<Passenger> q) {
        if (q == null || q.isEmpty()) return null;
        Iterator<Passenger> it = q.iterator();
        while (it.hasNext()) {
            Passenger p = it.next();
            if (p != null && !p.isMissed()) {
                it.remove();
                return p;
            }
        }
        return null;
    }

    private void removeFromCompletedTicketLines(Passenger p) {
        if (p == null) return;
        for (LinkedList<Passenger> line : completedTicketLines) {
            Iterator<Passenger> it = line.iterator();
            while (it.hasNext()) {
                if (it.next() == p) {
                    it.remove();
                    return;
                }
            }
        }
    }

    private void removeFromCompletedCheckpointLines(Passenger p) {
        if (p == null) return;
        for (LinkedList<Passenger> line : completedCheckpointLines) {
            Iterator<Passenger> it = line.iterator();
            while (it.hasNext()) {
                if (it.next() == p) {
                    it.remove();
                    return;
                }
            }
        }
    }

    private int pickBestCheckpointLine() {
        int bestC = 0;
        for (int j = 1; j < numCheckpoints; j++) {
            if (checkpointLines.get(j).size() < checkpointLines.get(bestC).size()) bestC = j;
        }
        return bestC;
    }

    // NEW: pick lane by minimal *time backlog* at absolute second absSec
    private int pickBestCheckpointLineAtSecond(int absSec) {
        int best = 0;
        long bestLoad = Long.MAX_VALUE;
        for (int c = 0; c < numCheckpoints; c++) {
            int svc = getCheckpointServiceSeconds(c);

            // remaining time on current pax (if any)
            int rem = 0;
            int end = checkpointServiceEndAbs[c];
            if (end > absSec) rem = end - absSec;

            // count non-missed passengers queued
            int q = 0;
            for (Passenger p : checkpointLines.get(c)) if (p != null && !p.isMissed()) q++;

            long load = rem + (long) q * svc;

            if (load < bestLoad) {
                bestLoad = load;
                best = c;
            } else if (load == bestLoad) {
                // tie-breaker: smaller queue size, then lower lane id
                int curQ = q;
                int bestQ = 0;
                for (Passenger bp : checkpointLines.get(best)) if (bp != null && !bp.isMissed()) bestQ++;
                if (curQ < bestQ || (curQ == bestQ && c < best)) best = c;
            }
        }
        return best;
    }

    // ===========================
    // MAIN SIMULATION STEP
    // ===========================
    public void simulateInterval() {
        justClosedFlights.clear();
        Arrays.fill(counterServing, null);
        // CRITICAL: DO NOT clear checkpointServing here; it must persist across intervals so the
        // passenger in service can complete and be forwarded to hold at the correct absolute second.
        // (Bug fix from previous version.)

        final int intervalSeconds = getIntervalSeconds();
        final int intervalStartAbsSec = currentInterval * intervalSeconds;
        final int intervalEndAbsSec = intervalStartAbsSec + intervalSeconds;

        final int intervalStartMinuteIdx = intervalStartAbsSec / 60;

        Map<Flight, Integer> arrivalsThisInterval = new LinkedHashMap<>();
        Map<Flight, Integer> enqueuedTicketThisInterval = new LinkedHashMap<>();
        Map<Flight, Integer> ticketedThisInterval = new LinkedHashMap<>();
        Map<Flight, Integer> arrivedToCheckpointThisInterval = new LinkedHashMap<>();
        Map<Flight, Integer> passedCheckpointThisInterval = new LinkedHashMap<>();

        List<List<Passenger>> onlineArrivalsThisInterval = new ArrayList<>();
        List<List<Passenger>> fromTicketArrivalsThisInterval = new ArrayList<>();
        for (int i = 0; i < numCheckpoints; i++) {
            onlineArrivalsThisInterval.add(new ArrayList<>());
            fromTicketArrivalsThisInterval.add(new ArrayList<>());
        }

        // Precompute close/departure events that fall inside this interval window
        Map<Integer, List<Flight>> closeEventsAtAbsSecond = new HashMap<>();
        Map<Integer, List<Flight>> departEventsAtAbsSecond = new HashMap<>();
        for (Flight f : flights) {
            int closeAbs = getBoardingCloseIdxMinutes(f) * 60;
            if (closeAbs >= intervalStartAbsSec && closeAbs < intervalEndAbsSec) {
                closeEventsAtAbsSecond.computeIfAbsent(closeAbs, k -> new ArrayList<>()).add(f);
            }
            int depAbs = getDepartureIdxMinutes(f) * 60;
            if (depAbs >= intervalStartAbsSec && depAbs < intervalEndAbsSec) {
                departEventsAtAbsSecond.computeIfAbsent(depAbs, k -> new ArrayList<>()).add(f);
            }
        }

        // 1) Spawn passengers minute-by-minute across this interval; schedule queue-entry in ABS seconds
        for (int subMin = 0; subMin < intervalMinutes; subMin++) {
            int minuteIdx = intervalStartMinuteIdx + subMin;

            for (Flight f : flights) {
                int[] perMin = minuteArrivalsMap.get(f);
                long offset = Duration.between(globalStart,
                                f.getDepartureTime().minusMinutes(arrivalSpanMinutes))
                        .toMinutes();
                int idx = minuteIdx - (int) offset;

                if (perMin != null && idx >= 0 && idx < perMin.length) {
                    int totalHere = Math.max(0, perMin[idx]);

                    inc(arrivalsThisInterval, f, totalHere);

                    int inPerson = (int) Math.round(totalHere * percentInPerson);
                    int online = totalHere - inPerson;

                    if (counterConfigs.isEmpty()) {
                        online += inPerson;
                        inPerson = 0;
                    }

                    // allowed ticket counters for this flight
                    List<Integer> allowed = new ArrayList<>();
                    for (int j = 0; j < counterConfigs.size(); j++) {
                        if (counterConfigs.get(j).accepts(f)) allowed.add(j);
                    }
                    if (allowed.isEmpty() && !counterConfigs.isEmpty()) {
                        for (int j = 0; j < counterConfigs.size(); j++) allowed.add(j);
                    }

                    int minuteStartAbs = minuteIdx * 60;

                    // in-person passengers
                    for (int i = 0; i < inPerson; i++) {
                        Passenger p = new Passenger(f, minuteIdx, true);

                        int ticketIdx = 0;
                        if (!allowed.isEmpty()) {
                            ticketIdx = allowed.get(0);
                            for (int ci : allowed) {
                                if (ticketLines.get(ci).size() < ticketLines.get(ticketIdx).size()) ticketIdx = ci;
                            }
                        }
                        targetTicketLineByPassenger.put(p, ticketIdx);

                        // jitter spawn within the minute to reduce clumping
                        int jitter = (spawnJitterEnabled ? rand.nextInt(60) : 0);

                        int dueAbsSec;
                        if (travelTimeProvider != null) {
                            dueAbsSec = minuteStartAbs + jitter + travelSecondsSpawnToTicket(ticketIdx);
                        } else {
                            // blank-canvas: arrive to ticket near that minute boundary
                            dueAbsSec = minuteStartAbs + jitter;
                        }
                        pendingToTicket.computeIfAbsent(dueAbsSec, x -> new ArrayList<>()).add(p);
                    }

                    // online passengers
                    for (int i = 0; i < online; i++) {
                        Passenger p = new Passenger(f, minuteIdx, false);

                        // defer lane choice until actual arrival time (state-aware)
                        int jitter = (spawnJitterEnabled ? rand.nextInt(60) : 0);

                        int dueAbsSec;
                        if (travelTimeProvider != null) {
                            // We don't yet know the best lane; choose on arrival using current loads.
                            // Travel time requires a candidate lane; approximate with "current best" at minute start.
                            // We’ll still select final lane on arrival.
                            int protoLane = pickBestCheckpointLineAtSecond(minuteStartAbs);
                            dueAbsSec = minuteStartAbs + jitter + travelSecondsSpawnToCheckpoint(protoLane);
                            // Mark an intended lane only for animation if desired; not binding.
                            targetCheckpointLineByPassenger.put(p, protoLane);
                        } else {
                            dueAbsSec = minuteStartAbs + jitter;
                        }
                        pendingToCP.computeIfAbsent(dueAbsSec, x -> new ArrayList<>()).add(p);
                    }
                }
            }
        }

        // 2) Run the interval window in 1-second steps (service + arrivals + travel completions)
        double[] ticketDebt = Arrays.copyOf(counterProgress, counterProgress.length);

        double[] ticketRateSec = new double[counterConfigs.size()];
        for (int c = 0; c < ticketRateSec.length; c++) ticketRateSec[c] = getTicketRatePerSecond(c);

        for (int sec = 0; sec < intervalSeconds; sec++) {
            int absSec = intervalStartAbsSec + sec;

            // 2a) Flight close events at this second
            List<Flight> closes = closeEventsAtAbsSecond.get(absSec);
            if (closes != null) {
                for (Flight f : closes) handleBoardingCloseMarkMissed(f);
            }

            // 2b) Flight departures at this second (clear hold rooms)
            List<Flight> deps = departEventsAtAbsSecond.get(absSec);
            if (deps != null) {
                for (Flight f : deps) clearFlightFromHoldRooms(f);
            }

            // 2c) Arrive to ticket (spawn->ticket)
            List<Passenger> toTicketNow = pendingToTicket.remove(absSec);
            if (toTicketNow != null) {
                for (Passenger p : toTicketNow) {
                    if (p == null || p.isMissed()) continue;

                    Integer t = targetTicketLineByPassenger.get(p);
                    int ticketIdx = (t == null) ? 0 : clamp(t, 0, ticketLines.size() - 1);

                    ticketLines.get(ticketIdx).add(p);
                    ticketQueueEnterAbsSecond.put(p, absSec);
                    tryInvokeIntSetter(p, "setTicketQueueEnterAbsSecond", absSec);

                    inc(enqueuedTicketThisInterval, p.getFlight(), 1);
                }
            }

            // 2d) Arrive to checkpoint (spawn->cp OR ticket->cp)
            List<Passenger> toCPNow = pendingToCP.remove(absSec);
            if (toCPNow != null) {
                for (Passenger p : toCPNow) {
                    if (p == null || p.isMissed()) continue;

                    boolean isOnline = !p.isInPerson();
                    if (!isOnline) {
                        // leaving ticket staging at checkpoint arrival time
                        removeFromCompletedTicketLines(p);
                    }

                    int minuteIdx = absSec / 60;
                    p.setCheckpointEntryMinute(minuteIdx);
                    checkpointQueueEnterAbsSecond.put(p, absSec);
                    tryInvokeIntSetter(p, "setCheckpointQueueEnterAbsSecond", absSec);

                    Integer target = targetCheckpointLineByPassenger.remove(p);
                    int cpLine = (target != null)
                            ? clamp(target, 0, numCheckpoints - 1)
                            : pickBestCheckpointLineAtSecond(absSec);

                    checkpointLines.get(cpLine).add(p);
                    inc(arrivedToCheckpointThisInterval, p.getFlight(), 1);

                    if (isOnline) onlineArrivalsThisInterval.get(cpLine).add(p);
                    else fromTicketArrivalsThisInterval.get(cpLine).add(p);
                }
            }

            // 2e) Arrive to hold (cp->hold)
            List<Passenger> toHoldNow = pendingToHold.remove(absSec);
            if (toHoldNow != null) {
                for (Passenger p : toHoldNow) {
                    if (p == null || p.isMissed()) continue;

                    // leaving checkpoint staging at hold arrival time
                    removeFromCompletedCheckpointLines(p);

                    Flight f = p.getFlight();
                    int closeAbsSec = getBoardingCloseIdxMinutes(f) * 60;
                    if (absSec < closeAbsSec) {
                        int roomIdx = p.getAssignedHoldRoomIndex();
                        if (roomIdx < 0) {
                            roomIdx = chosenHoldRoomIndexByFlight.getOrDefault(f, 0);
                            p.setAssignedHoldRoomIndex(roomIdx);
                        }
                        roomIdx = clamp(roomIdx, 0, holdRoomLines.size() - 1);

                        p.setHoldRoomEntryMinute(absSec / 60);
                        int seq = holdRoomLines.get(roomIdx).size() + 1;
                        p.setHoldRoomSequence(seq);
                        holdRoomLines.get(roomIdx).add(p);

                        holdRoomEnterAbsSecond.put(p, absSec);
                        tryInvokeIntSetter(p, "setHoldRoomEnterAbsSecond", absSec);
                    } else {
                        p.setMissed(true);
                    }
                }
            }

            // 2f) Ticket service at this second (per-lane)
            for (int c = 0; c < counterConfigs.size(); c++) {
                // If lane is empty (or only has missed pax), treat as idle: no carry accrues.
                if (ticketLines.get(c).isEmpty()) {
                    ticketDebt[c] = 0.0;
                    continue;
                }

                ticketDebt[c] += ticketRateSec[c];

                while (ticketDebt[c] >= 1.0) {
                    Passenger next = takeFirstNotMissed(ticketLines.get(c));
                    if (next == null) {
                        // no eligible pax -> idle
                        ticketDebt[c] = 0.0;
                        break;
                    }

                    counterServing[c] = next;

                    int doneAbsSec = absSec;
                    ticketDoneAbsSecond.put(next, doneAbsSec);
                    tryInvokeIntSetter(next, "setTicketDoneAbsSecond", doneAbsSec);

                    // keep old minute+sec fields (compat)
                    next.setTicketCompletionMinute(doneAbsSec / 60);
                    tryInvokeIntSetter(next, "setTicketCompletionSecond", doneAbsSec % 60);

                    completedTicketLines.get(c).add(next);
                    inc(ticketedThisInterval, next.getFlight(), 1);

                    if (!next.isMissed()) {
                        int targetCp = pickBestCheckpointLineAtSecond(absSec);
                        targetCheckpointLineByPassenger.put(next, targetCp);

                        int travelSec = travelSecondsTicketToCheckpoint(c, targetCp);
                        int arriveAbs = doneAbsSec + travelSec;
                        pendingToCP.computeIfAbsent(arriveAbs, x -> new ArrayList<>()).add(next);
                    }

                    ticketDebt[c] -= 1.0;

                    // if queue is now empty, lane becomes idle immediately (no banked partial work)
                    if (ticketLines.get(c).isEmpty()) {
                        ticketDebt[c] = 0.0;
                        break;
                    }
                }
            }

            // 2g) Checkpoint service at this second (per-lane, FIXED service time)
            for (int c = 0; c < numCheckpoints; c++) {
                // First, finalize any service that completes *at* this second
                if (checkpointServiceEndAbs[c] > 0 && absSec >= checkpointServiceEndAbs[c]) {
                    Passenger doneP = checkpointServing[c];
                    checkpointServing[c] = null;
                    checkpointServiceEndAbs[c] = 0;

                    if (doneP != null && !doneP.isMissed()) {
                        int doneAbsSec = absSec; // equal to precomputed end
                        // move to staging, bump metrics, schedule to hold
                        completedCheckpointLines.get(c).add(doneP);
                        inc(passedCheckpointThisInterval, doneP.getFlight(), 1);

                        int targetRoom = chosenHoldRoomIndexByFlight.getOrDefault(doneP.getFlight(), 0);
                        targetRoom = clamp(targetRoom, 0, holdRoomConfigs.size() - 1);
                        doneP.setAssignedHoldRoomIndex(targetRoom);

                        int travelSec = travelSecondsCheckpointToHold(c, targetRoom);
                        int arriveAbs = doneAbsSec + travelSec;
                        pendingToHold.computeIfAbsent(arriveAbs, x -> new ArrayList<>()).add(doneP);
                    }
                }

                // If lane is idle, try to start a new service immediately
                if (checkpointServiceEndAbs[c] == 0) {
                    Passenger next = takeFirstNotMissed(checkpointLines.get(c));
                    if (next != null) {
                        beginCheckpointService(next, c, absSec);
                    }
                }
            }
        }

        // Persist fractional carry for next interval (ticket only)
        for (int i = 0; i < counterProgress.length; i++) {
            double v = ticketDebt[i];
            if (!Double.isFinite(v) || v < 0) v = 0;
            // keep within [0,1)
            if (v >= 1.0) v = v - Math.floor(v);
            counterProgress[i] = v;
        }
        // checkpointProgress retained (no longer used for service); keep zeros
        for (int i = 0; i < checkpointProgress.length; i++) {
            double v = 0.0;
            checkpointProgress[i] = v;
        }

        // record histories (per interval end state)
        historyServedTicket.add(deepCopyPassengerLists(completedTicketLines));
        historyQueuedTicket.add(deepCopyPassengerLists(ticketLines));
        historyServedCheckpoint.add(deepCopyPassengerLists(completedCheckpointLines));
        historyQueuedCheckpoint.add(deepCopyPassengerLists(checkpointLines));
        historyHoldRooms.add(deepCopyPassengerLists(holdRoomLines));

        historyArrivals.add(mapCopy(arrivalsThisInterval));
        historyEnqueuedTicket.add(mapCopy(enqueuedTicketThisInterval));
        historyTicketed.add(mapCopy(ticketedThisInterval));
        historyArrivedToCheckpoint.add(mapCopy(arrivedToCheckpointThisInterval));
        historyPassedCheckpoint.add(mapCopy(passedCheckpointThisInterval));

        int ticketWaitingNow = ticketLines.stream().mapToInt(List::size).sum();
        int checkpointWaitingNow = checkpointLines.stream().mapToInt(List::size).sum();
        historyTicketLineSize.add(ticketWaitingNow);
        historyCPLineSize.add(checkpointWaitingNow);

        historyOnlineArrivals.add(deepCopyListOfLists(onlineArrivalsThisInterval));
        historyFromTicketArrivals.add(deepCopyListOfLists(fromTicketArrivalsThisInterval));

        // If any flights closed during this interval, clear non-hold areas (legacy behavior)
        if (!justClosedFlights.isEmpty()) {
            for (Flight f : justClosedFlights) clearFlightFromNonHoldAreas(f);
        }

        removeMissedPassengers();

        currentInterval++;

        int stillInTicketQueue = ticketLines.stream().mapToInt(List::size).sum();
        int stillInCheckpointQueue = checkpointLines.stream().mapToInt(List::size).sum();
        heldUpsByInterval.put(currentInterval, stillInTicketQueue + stillInCheckpointQueue);

        recordQueueTotalsForCurrentInterval();
        appendSnapshotAfterInterval();
    }

    // Start a checkpoint service for one passenger on lane c at absolute second tStart
    private void beginCheckpointService(Passenger p, int laneIdx, int tStartAbsSec) {
        if (p == null) return;
        int svcSec = getCheckpointServiceSeconds(laneIdx);
        int doneAbs = tStartAbsSec + Math.max(1, svcSec);

        checkpointServing[laneIdx] = p;
        checkpointServiceEndAbs[laneIdx] = doneAbs;

        checkpointStartAbsSecond.put(p, tStartAbsSec);
        checkpointDoneAbsSecond.put(p, doneAbs);

        tryInvokeIntSetter(p, "setCheckpointStartAbsSecond", tStartAbsSec);
        tryInvokeIntSetter(p, "setCheckpointDoneAbsSecond", doneAbs);

        // legacy minute/second fields (compat)
        p.setCheckpointCompletionMinute(doneAbs / 60);
        tryInvokeIntSetter(p, "setCheckpointCompletionSecond", doneAbs % 60);
    }

    private static List<List<Passenger>> deepCopyListOfLists(List<List<Passenger>> src) {
        List<List<Passenger>> out = new ArrayList<>();
        if (src == null) return out;
        for (List<Passenger> l : src) out.add(new ArrayList<>(l));
        return out;
    }

    public void removeMissedPassengers() {
        ticketLines.forEach(line -> line.removeIf(Passenger::isMissed));
        completedTicketLines.forEach(line -> line.removeIf(Passenger::isMissed));
        checkpointLines.forEach(line -> line.removeIf(Passenger::isMissed));
        completedCheckpointLines.forEach(line -> line.removeIf(Passenger::isMissed));
        holdRoomLines.forEach(line -> line.removeIf(Passenger::isMissed));

        targetTicketLineByPassenger.keySet().removeIf(p -> p == null || p.isMissed());
        targetCheckpointLineByPassenger.keySet().removeIf(p -> p == null || p.isMissed());

        ticketDoneAbsSecond.keySet().removeIf(p -> p == null || p.isMissed());
        checkpointDoneAbsSecond.keySet().removeIf(p -> p == null || p.isMissed());
        checkpointStartAbsSecond.keySet().removeIf(p -> p == null || p.isMissed());

        ticketQueueEnterAbsSecond.keySet().removeIf(p -> p == null || p.isMissed());
        checkpointQueueEnterAbsSecond.keySet().removeIf(p -> p == null || p.isMissed());
        holdRoomEnterAbsSecond.keySet().removeIf(p -> p == null || p.isMissed());
    }

    private List<List<Passenger>> deepCopyPassengerLists(List<LinkedList<Passenger>> original) {
        List<List<Passenger>> copy = new ArrayList<>();
        for (LinkedList<Passenger> line : original) copy.add(new ArrayList<>(line));
        return copy;
    }

    private void clearHistory() {
        historyArrivals.clear();
        historyEnqueuedTicket.clear();
        historyTicketed.clear();
        historyTicketLineSize.clear();
        historyArrivedToCheckpoint.clear();
        historyCPLineSize.clear();
        historyPassedCheckpoint.clear();

        historyServedTicket.clear();
        historyQueuedTicket.clear();
        historyOnlineArrivals.clear();
        historyFromTicketArrivals.clear();
        historyServedCheckpoint.clear();
        historyQueuedCheckpoint.clear();
        historyHoldRooms.clear();

        Arrays.fill(counterProgress, 0);
        Arrays.fill(checkpointProgress, 0);
        Arrays.fill(counterServing, null);
        Arrays.fill(checkpointServing, null);
        Arrays.fill(checkpointServiceEndAbs, 0);

        pendingToTicket.clear();
        pendingToCP.clear();
        pendingToHold.clear();

        targetTicketLineByPassenger.clear();
        targetCheckpointLineByPassenger.clear();

        holdRoomLines.forEach(LinkedList::clear);
        justClosedFlights.clear();

        ticketDoneAbsSecond.clear();
        checkpointDoneAbsSecond.clear();
        checkpointStartAbsSecond.clear();
        ticketQueueEnterAbsSecond.clear();
        checkpointQueueEnterAbsSecond.clear();
        holdRoomEnterAbsSecond.clear();
    }

    private static List<LinkedList<Passenger>> deepCopyLinkedLists(List<LinkedList<Passenger>> original) {
        List<LinkedList<Passenger>> copy = new ArrayList<>(original.size());
        for (LinkedList<Passenger> line : original) copy.add(new LinkedList<>(line));
        return copy;
    }

    private static void restoreLinkedListsInPlace(List<LinkedList<Passenger>> target,
                                                 List<LinkedList<Passenger>> source) {
        if (target.size() != source.size()) {
            target.clear();
            for (LinkedList<Passenger> src : source) target.add(new LinkedList<>(src));
            return;
        }
        for (int i = 0; i < target.size(); i++) {
            LinkedList<Passenger> t = target.get(i);
            t.clear();
            t.addAll(source.get(i));
        }
    }

    private static Map<Integer, List<Passenger>> deepCopyPendingMap(Map<Integer, List<Passenger>> original) {
        Map<Integer, List<Passenger>> copy = new HashMap<>();
        for (Map.Entry<Integer, List<Passenger>> e : original.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return copy;
    }

    // RESTORED METHODS (compat)
    public List<Flight> getFlightsJustClosed() {
        return new ArrayList<>(justClosedFlights);
    }

    public Map<Flight, int[]> getMinuteArrivalsMap() {
        return Collections.unmodifiableMap(minuteArrivalsMap);
    }

    public int getTotalArrivalsAtInterval(int intervalIndex) {
        if (intervalIndex < 0) return 0;
        return getTotalArrivalsAtMinute(intervalIndex * intervalMinutes);
    }

    public int getTotalArrivalsAtMinute(int minuteSinceGlobalStart) {
        int sum = 0;
        for (Flight f : flights) {
            int[] perMin = minuteArrivalsMap.get(f);
            if (perMin == null) continue;

            long offset = Duration.between(
                    globalStart,
                    f.getDepartureTime().minusMinutes(arrivalSpanMinutes)
            ).toMinutes();

            int idx = minuteSinceGlobalStart - (int) offset;
            if (idx >= 0 && idx < perMin.length) sum += perMin[idx];
        }
        return sum;
    }

    // HISTORY GETTERS (compat)
    public List<Map<Flight, Integer>> getHistoryArrivals() { return historyArrivals; }
    public List<Map<Flight, Integer>> getHistoryEnqueuedTicket() { return historyEnqueuedTicket; }
    public List<Map<Flight, Integer>> getHistoryTicketed() { return historyTicketed; }
    public List<Integer> getHistoryTicketLineSize() { return historyTicketLineSize; }
    public List<Map<Flight, Integer>> getHistoryArrivedToCheckpoint() { return historyArrivedToCheckpoint; }
    public List<Integer> getHistoryCPLineSize() { return historyCPLineSize; }
    public List<Map<Flight, Integer>> getHistoryPassedCheckpoint() { return historyPassedCheckpoint; }
    public List<List<List<Passenger>>> getHistoryServedTicket() { return historyServedTicket; }
    public List<List<List<Passenger>>> getHistoryQueuedTicket() { return historyQueuedTicket; }
    public List<List<List<Passenger>>> getHistoryOnlineArrivals() { return historyOnlineArrivals; }
    public List<List<List<Passenger>>> getHistoryFromTicketArrivals() { return historyFromTicketArrivals; }
    public List<List<List<Passenger>>> getHistoryServedCheckpoint() { return historyServedCheckpoint; }
    public List<List<List<Passenger>>> getHistoryQueuedCheckpoint() { return historyQueuedCheckpoint; }
    public List<List<List<Passenger>>> getHistoryHoldRooms() { return historyHoldRooms; }

    // PUBLIC GETTERS
    public List<Flight> getFlights() { return flights; }
    public double getPercentInPerson() { return percentInPerson; }
    public int getArrivalSpan() { return arrivalSpanMinutes; }
    public int getInterval() { return intervalMinutes; }
    public int getTotalIntervals() { return totalIntervals; }
    public int getCurrentInterval() { return currentInterval; }

    public int getNumTicketCounters() { return counterConfigs == null ? 0 : counterConfigs.size(); }
    public int getNumCheckpoints() { return numCheckpoints; }
    public double getDefaultCheckpointRatePerHour() { return defaultCheckpointRatePerHour; }

    public List<LinkedList<Passenger>> getTicketLines() { return ticketLines; }
    public List<LinkedList<Passenger>> getCheckpointLines() { return checkpointLines; }
    public List<LinkedList<Passenger>> getCompletedTicketLines() { return completedTicketLines; }
    public List<LinkedList<Passenger>> getCompletedCheckpointLines() { return completedCheckpointLines; }
    public List<LinkedList<Passenger>> getHoldRoomLines() { return holdRoomLines; }

    public Passenger[] getCounterServing() { return counterServing; }
    public Passenger[] getCheckpointServing() { return checkpointServing; }

    public int getTransitDelayMinutes() { return transitDelayMinutes; }
    public int getHoldDelayMinutes() { return holdDelayMinutes; }

    public List<HoldRoomConfig> getHoldRoomConfigs() { return Collections.unmodifiableList(holdRoomConfigs); }
    public List<TicketCounterConfig> getCounterConfigs() { return Collections.unmodifiableList(counterConfigs); }
    public List<CheckpointConfig> getCheckpointConfigs() { return Collections.unmodifiableList(checkpointConfigs); }
    public LocalTime getGlobalStartTime() { return globalStart; }

    public int getHoldRoomCellSize(Flight f) {
        return holdRoomCellSize.getOrDefault(f, GridRenderer.MIN_CELL_SIZE);
    }

    // ✅ pending getters used by FloorplanSimulationPanel
    // IMPORTANT: Keys are ABS SECONDS (not interval indices).
    public Map<Integer, List<Passenger>> getPendingToTicket() { return Collections.unmodifiableMap(pendingToTicket); }
    public Map<Integer, List<Passenger>> getPendingToCP() { return Collections.unmodifiableMap(pendingToCP); }
    public Map<Integer, List<Passenger>> getPendingToHold() { return Collections.unmodifiableMap(pendingToHold); }

    // ✅ target getters
    public Integer getTargetTicketLineFor(Passenger p) { return (p == null) ? null : targetTicketLineByPassenger.get(p); }
    public Map<Passenger, Integer> getTargetCheckpointLineByPassenger() { return Collections.unmodifiableMap(targetCheckpointLineByPassenger); }
    public Integer getTargetCheckpointLineFor(Passenger p) { return (p == null) ? null : targetCheckpointLineByPassenger.get(p); }

    public int getChosenHoldRoomIndexForFlight(Flight f) {
        if (f == null) return 0;
        return chosenHoldRoomIndexByFlight.getOrDefault(f, 0);
    }

    // ✅ exact completion time getters (ABS seconds since sim start)
    public Integer getTicketDoneAbsSecond(Passenger p) { return (p == null) ? null : ticketDoneAbsSecond.get(p); }
    public Integer getCheckpointDoneAbsSecond(Passenger p) { return (p == null) ? null : checkpointDoneAbsSecond.get(p); }
    public Integer getCheckpointStartAbsSecond(Passenger p) { return (p == null) ? null : checkpointStartAbsSecond.get(p); }

    // Optional enter-time getters (helpful for floorplan renderer)
    public Integer getTicketQueueEnterAbsSecond(Passenger p) { return (p == null) ? null : ticketQueueEnterAbsSecond.get(p); }
    public Integer getCheckpointQueueEnterAbsSecond(Passenger p) { return (p == null) ? null : checkpointQueueEnterAbsSecond.get(p); }
    public Integer getHoldRoomEnterAbsSecond(Passenger p) { return (p == null) ? null : holdRoomEnterAbsSecond.get(p); }

    // NEW QoL: expose lane service end times for UI/diagnostics
    public int[] getCheckpointLaneServiceEndAbs() {
        return Arrays.copyOf(checkpointServiceEndAbs, checkpointServiceEndAbs.length);
    }

    // QUEUE TOTALS METRICS
    public int getTicketQueuedAtInterval(int intervalIndex) {
        Integer v = ticketQueuedByInterval.get(intervalIndex);
        return v == null ? 0 : v;
    }

    public int getCheckpointQueuedAtInterval(int intervalIndex) {
        Integer v = checkpointQueuedByInterval.get(intervalIndex);
        return v == null ? 0 : v;
    }

    public int getHoldRoomTotalAtInterval(int intervalIndex) {
        Integer v = holdRoomTotalByInterval.get(intervalIndex);
        return v == null ? 0 : v;
    }

    public Map<Integer, Integer> getHoldUpsByInterval() { return new LinkedHashMap<>(heldUpsByInterval); }
    public Map<Integer, Integer> getTicketQueuedByInterval() { return new LinkedHashMap<>(ticketQueuedByInterval); }
    public Map<Integer, Integer> getCheckpointQueuedByInterval() { return new LinkedHashMap<>(checkpointQueuedByInterval); }
    public Map<Integer, Integer> getHoldRoomTotalByInterval() { return new LinkedHashMap<>(holdRoomTotalByInterval); }

    private void recordQueueTotalsForCurrentInterval() {
        int ticketWaiting = ticketLines.stream().mapToInt(List::size).sum();
        int checkpointWaiting = checkpointLines.stream().mapToInt(List::size).sum();
        int holdTotal = holdRoomLines.stream().mapToInt(List::size).sum();

        ticketQueuedByInterval.put(currentInterval, ticketWaiting);
        checkpointQueuedByInterval.put(currentInterval, checkpointWaiting);
        holdRoomTotalByInterval.put(currentInterval, holdTotal);
    }
}
