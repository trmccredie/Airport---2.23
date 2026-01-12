package sim.ui;

import sim.floorplan.model.FloorplanProject;                 // ✅ NEW
import sim.floorplan.sim.TravelTimeProvider;                 // ✅ NEW
import sim.floorplan.ui.FloorplanEditorPanel;                // ✅ NEW
import sim.model.ArrivalCurveConfig;
import sim.model.Flight;
import sim.service.SimulationEngine;

import javax.swing.*;
import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

public class MainFrame extends JFrame {
    private GlobalInputPanel    globalInputPanel;
    private FlightTablePanel    flightTablePanel;
    private TicketCounterPanel  ticketCounterPanel;
    private CheckpointPanel     checkpointPanel;
    private HoldRoomSetupPanel  holdRoomSetupPanel;

    // ✅ NEW (Step 6)
    private ArrivalCurveEditorPanel arrivalCurvePanel;

    // ✅ NEW (Milestone 4 integration)
    private FloorplanEditorPanel floorplanEditorPanel;
    private JButton validateFloorplanButton;

    private JButton startSimulationButton;
    private JTabbedPane tabs;

    public MainFrame() {
        super("Airport Setup");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));
        setContentPane(root);

        initializeComponents();
        pack();
        setLocationRelativeTo(null);
    }

    private void initializeComponents() {
        globalInputPanel   = new GlobalInputPanel();
        flightTablePanel   = new FlightTablePanel();
        ticketCounterPanel = new TicketCounterPanel(flightTablePanel.getFlights());
        checkpointPanel    = new CheckpointPanel();
        holdRoomSetupPanel = new HoldRoomSetupPanel(flightTablePanel.getFlights());

        // ✅ NEW (Step 6)
        arrivalCurvePanel  = new ArrivalCurveEditorPanel(ArrivalCurveConfig.legacyDefault());

        // ✅ NEW (Milestone 4)
        floorplanEditorPanel = new FloorplanEditorPanel();

        validateFloorplanButton = new JButton("Validate Floorplan");
        startSimulationButton = new JButton("Start Simulation");

        startSimulationButton.setForeground(Color.WHITE);
        startSimulationButton.setOpaque(true);
        startSimulationButton.setContentAreaFilled(true);

        startSimulationButton.setEnabled(false);

        validateFloorplanButton.addActionListener(e -> {
            if (tabs != null && floorplanEditorPanel != null) {
                tabs.setSelectedComponent(floorplanEditorPanel);
            }
            boolean ok = floorplanEditorPanel.validateAndLock();
            startSimulationButton.setEnabled(ok);
        });

        startSimulationButton.addActionListener(e -> onStartSimulation());

        add(globalInputPanel, BorderLayout.NORTH);

        tabs = new JTabbedPane();

        tabs.addTab("Floorplan", floorplanEditorPanel);
        tabs.addTab("Flights", flightTablePanel);
        tabs.addTab("Ticket Counters", ticketCounterPanel);
        tabs.addTab("Checkpoints", checkpointPanel);
        tabs.addTab("Hold Rooms", holdRoomSetupPanel);
        tabs.addTab("Arrivals Curve", arrivalCurvePanel);

        add(tabs, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        bottom.add(validateFloorplanButton);
        bottom.add(startSimulationButton);
        add(bottom, BorderLayout.SOUTH);
    }

    private void onStartSimulation() {
        if (floorplanEditorPanel != null && !floorplanEditorPanel.isLocked()) {
            JOptionPane.showMessageDialog(this,
                    "Please validate & lock the floorplan before starting the simulation.\n\n" +
                            "Go to the Floorplan tab and click “Validate & Lock”.",
                    "Floorplan Not Validated",
                    JOptionPane.WARNING_MESSAGE);

            if (tabs != null) tabs.setSelectedComponent(floorplanEditorPanel);
            startSimulationButton.setEnabled(false);
            return;
        }

        if (flightTablePanel.getFlights().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please add at least one flight before starting simulation.",
                    "No Flights Defined",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<TicketCounterConfig> counters = ticketCounterPanel.getCounters();
        if (counters.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please add at least one ticket counter before starting simulation.",
                    "No Counters Defined",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<CheckpointConfig> checkpoints = checkpointPanel.getCheckpoints();
        if (checkpoints.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please add at least one checkpoint before starting simulation.",
                    "No Checkpoints Defined",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<HoldRoomConfig> holdRooms = holdRoomSetupPanel.getHoldRooms();
        if (holdRooms.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please add at least one hold room before starting simulation.",
                    "No Hold Rooms Defined",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            double percentInPerson = globalInputPanel.getPercentInPerson();
            if (percentInPerson < 0 || percentInPerson > 1) {
                throw new IllegalArgumentException("Percent in person must be between 0 and 1");
            }

            int baseArrivalSpan  = globalInputPanel.getArrivalSpanMinutes();
            int interval         = globalInputPanel.getIntervalMinutes();
            int transitDelay     = globalInputPanel.getTransitDelayMinutes();

            int holdDelay = resolveHoldDelayMinutes();

            List<Flight> flights = flightTablePanel.getFlights();

            ArrivalCurveConfig curveCfg = arrivalCurvePanel.getConfigCopy();
            curveCfg.validateAndClamp();

            int curveStart = curveCfg.isLegacyMode()
                    ? ArrivalCurveConfig.DEFAULT_WINDOW_START
                    : curveCfg.getWindowStartMinutesBeforeDeparture();

            int effectiveArrivalSpan = Math.max(baseArrivalSpan, curveStart);

            // ✅ Grab a frozen copy of the validated floorplan project for the simulation frame
            FloorplanProject floorplanCopy = (floorplanEditorPanel == null) ? null : floorplanEditorPanel.getProjectCopy();

            // Build the pre-run engine for the data table
            SimulationEngine tableEngine = createEngine(
                    percentInPerson,
                    counters,
                    checkpoints,
                    effectiveArrivalSpan,
                    interval,
                    transitDelay,
                    holdDelay,
                    flights,
                    holdRooms
            );

            tableEngine.setArrivalCurveConfig(curveCfg);

            // ✅ Set legacy-compatible provider (no behavioral change; matches existing delays)
            tableEngine.setTravelTimeProvider(buildLegacyProvider(transitDelay, holdDelay, holdRooms));

            tableEngine.runAllIntervals();

            // Build the fresh engine for live animation
            SimulationEngine simEngine = createEngine(
                    percentInPerson,
                    counters,
                    checkpoints,
                    effectiveArrivalSpan,
                    interval,
                    transitDelay,
                    holdDelay,
                    flights,
                    holdRooms
            );

            simEngine.setArrivalCurveConfig(curveCfg);

            // ✅ Set legacy-compatible provider (same as above)
            simEngine.setTravelTimeProvider(buildLegacyProvider(transitDelay, holdDelay, holdRooms));

            new DataTableFrame(tableEngine).setVisible(true);

            // ✅ If we have a floorplan, show it in SimulationFrame as a tab
            if (floorplanCopy != null && floorplanCopy.getFloorplanImage() != null) {
                new SimulationFrame(simEngine, floorplanCopy).setVisible(true);
            } else {
                new SimulationFrame(simEngine).setVisible(true);
            }

        } catch (Exception ex) {
            ex.printStackTrace();
            StringWriter sw = new StringWriter();
            ex.printStackTrace(new PrintWriter(sw));
            JTextArea area = new JTextArea(sw.toString(), 20, 60);
            area.setEditable(false);
            JOptionPane.showMessageDialog(this,
                    new JScrollPane(area),
                    "Simulation Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Legacy-compatible TravelTimeProvider:
     * - ticket->checkpoint uses transitDelayMinutes
     * - checkpoint->hold uses each holdroom's walkSecondsFromCheckpoint (ceil to minutes),
     *   falling back to holdDelayMinutes if needed.
     */
    private static TravelTimeProvider buildLegacyProvider(int transitDelayMinutes, int holdDelayMinutes, List<HoldRoomConfig> holdRooms) {
        final int td = Math.max(0, transitDelayMinutes);
        final int hd = Math.max(0, holdDelayMinutes);

        return new TravelTimeProvider() {
            @Override
            public int minutesTicketToCheckpoint(int ticketCounterIndex, int checkpointIndex) {
                return td;
            }

            @Override
            public int minutesCheckpointToHold(int checkpointIndex, int holdroomIndex) {
                try {
                    if (holdRooms != null && holdroomIndex >= 0 && holdroomIndex < holdRooms.size()) {
                        HoldRoomConfig cfg = holdRooms.get(holdroomIndex);
                        if (cfg != null) {
                            int sec = Math.max(0, cfg.getWalkSecondsFromCheckpoint());
                            int min = (sec / 60) + ((sec % 60) > 0 ? 1 : 0);
                            return Math.max(0, min);
                        }
                    }
                } catch (Throwable ignored) {
                    // fall back
                }
                return hd;
            }
        };
    }

    private int resolveHoldDelayMinutes() {
        Integer fromPanel = tryInvokeInt(holdRoomSetupPanel,
                "getHoldDelayMinutes",
                "getDefaultHoldDelayMinutes",
                "getHoldroomDelayMinutes",
                "getHoldRoomDelayMinutes",
                "getCheckpointToHoldDelayMinutes"
        );
        if (fromPanel != null && fromPanel >= 0) return fromPanel;

        try {
            List<HoldRoomConfig> rooms = holdRoomSetupPanel.getHoldRooms();
            if (rooms != null && !rooms.isEmpty()) {
                for (HoldRoomConfig cfg : rooms) {
                    Integer v = tryInvokeInt(cfg,
                            "getHoldDelayMinutes",
                            "getDelayMinutes",
                            "getHoldroomDelayMinutes",
                            "getHoldRoomDelayMinutes",
                            "getCheckpointToHoldDelayMinutes"
                    );
                    if (v != null && v >= 0) return v;

                    Integer sec = tryInvokeInt(cfg,
                            "getWalkSeconds",
                            "getCheckpointToHoldSeconds",
                            "getSecondsFromCheckpoint"
                    );
                    if (sec != null && sec > 0) {
                        return (sec + 59) / 60;
                    }
                }
            }
        } catch (Exception ignored) { }

        return 5;
    }

    private Integer tryInvokeInt(Object target, String... methodNames) {
        if (target == null) return null;
        for (String name : methodNames) {
            try {
                Method m = target.getClass().getMethod(name);
                Class<?> rt = m.getReturnType();
                if (rt == int.class || rt == Integer.class) {
                    Object out = m.invoke(target);
                    return (out == null) ? null : ((Number) out).intValue();
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private SimulationEngine createEngine(
            double percentInPerson,
            List<TicketCounterConfig> counters,
            List<CheckpointConfig> checkpoints,
            int arrivalSpan,
            int interval,
            int transitDelay,
            int holdDelay,
            List<Flight> flights,
            List<HoldRoomConfig> holdRooms
    ) throws Exception {

        for (Constructor<?> c : SimulationEngine.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 9
                    && p[0] == double.class
                    && List.class.isAssignableFrom(p[1])
                    && List.class.isAssignableFrom(p[2])
                    && p[3] == int.class
                    && p[4] == int.class
                    && p[5] == int.class
                    && p[6] == int.class
                    && List.class.isAssignableFrom(p[7])
                    && List.class.isAssignableFrom(p[8])) {
                return (SimulationEngine) c.newInstance(
                        percentInPerson,
                        counters,
                        checkpoints,
                        arrivalSpan,
                        interval,
                        transitDelay,
                        holdDelay,
                        flights,
                        holdRooms
                );
            }
        }

        for (Constructor<?> c : SimulationEngine.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 8
                    && p[0] == double.class
                    && List.class.isAssignableFrom(p[1])
                    && List.class.isAssignableFrom(p[2])
                    && p[3] == int.class
                    && p[4] == int.class
                    && p[5] == int.class
                    && List.class.isAssignableFrom(p[6])
                    && List.class.isAssignableFrom(p[7])) {
                return (SimulationEngine) c.newInstance(
                        percentInPerson,
                        counters,
                        checkpoints,
                        arrivalSpan,
                        interval,
                        transitDelay,
                        flights,
                        holdRooms
                );
            }
        }

        int numCheckpoints = (checkpoints == null) ? 0 : checkpoints.size();
        double avgRatePerMin = 0.0;
        if (checkpoints != null && !checkpoints.isEmpty()) {
            double sum = 0.0;
            for (CheckpointConfig cfg : checkpoints) sum += cfg.getRatePerMinute();
            avgRatePerMin = sum / checkpoints.size();
        }

        for (Constructor<?> c : SimulationEngine.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 10
                    && p[0] == double.class
                    && List.class.isAssignableFrom(p[1])
                    && p[2] == int.class
                    && p[3] == double.class
                    && p[4] == int.class
                    && p[5] == int.class
                    && p[6] == int.class
                    && p[7] == int.class
                    && List.class.isAssignableFrom(p[8])
                    && List.class.isAssignableFrom(p[9])) {

                return (SimulationEngine) c.newInstance(
                        percentInPerson,
                        counters,
                        numCheckpoints,
                        avgRatePerMin,
                        arrivalSpan,
                        interval,
                        transitDelay,
                        holdDelay,
                        flights,
                        holdRooms
                );
            }
        }

        for (Constructor<?> c : SimulationEngine.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 9
                    && p[0] == double.class
                    && List.class.isAssignableFrom(p[1])
                    && p[2] == int.class
                    && p[3] == double.class
                    && p[4] == int.class
                    && p[5] == int.class
                    && p[6] == int.class
                    && List.class.isAssignableFrom(p[7])
                    && List.class.isAssignableFrom(p[8])) {

                return (SimulationEngine) c.newInstance(
                        percentInPerson,
                        counters,
                        numCheckpoints,
                        avgRatePerMin,
                        arrivalSpan,
                        interval,
                        transitDelay,
                        flights,
                        holdRooms
                );
            }
        }

        return new SimulationEngine(
                percentInPerson,
                counters,
                numCheckpoints,
                avgRatePerMin,
                arrivalSpan,
                interval,
                transitDelay,
                holdDelay,
                flights
        );
    }
}