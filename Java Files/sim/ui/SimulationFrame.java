// SimulationFrame.java
package sim.ui;

import sim.floorplan.model.FloorplanProject;
import sim.floorplan.sim.FloorplanTravelTimeProvider; // ✅ best-effort update
import sim.floorplan.ui.FloorplanSimulationPanel;
import sim.model.Flight;
import sim.model.Passenger;
import sim.service.SimulationEngine;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import java.awt.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

public class SimulationFrame extends JFrame {

    // ======== Time / clock ========
    private final JLabel timeLabel;
    private final LocalTime startTime;
    private final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ======== Autorun & playback ========
    private final JButton autoRunBtn;
    private final JButton pausePlayBtn;
    private javax.swing.Timer autoRunTimer; // Swing timer
    private boolean isPaused = false;

    // ======== Summary & graphs ========
    private final JButton summaryBtn;

    // ======== Playback Step (seconds/tick) slider ========
    private final JSlider playbackStepSlider;
    private int playbackStepSeconds = 1; // 1..300

    // ======== Next/Prev with typed mm:ss ========
    private final JButton prevBtn;
    private final JButton nextBtn;
    private final JSpinner jumpMinSpinner;
    private final JSpinner jumpSecSpinner;

    // ======== Timeline ========
    private final JSlider timelineSlider;
    private final JLabel intervalLabel;
    private boolean timelineProgrammaticUpdate = false;

    // ======== Close flight notifications ========
    private final Map<Flight, Integer> closeSteps = new LinkedHashMap<>();
    private boolean simulationCompleted = false;

    // ======== Tabs / views ========
    private JTabbedPane viewTabs;
    private FloorplanSimulationPanel floorplanPanel;
    private final FloorplanProject floorplanProjectRef;
    private GraphWindow graphsWindow;

    // ======== Engine ========
    private final SimulationEngine engineRef;

    // ======== Frame-local fallback display seconds (used only if panel lacks the helpers) ========
    private int frameSecondsIntoInterval = 0;

    // ==========================================================
    // Constructors that build engine
    // ==========================================================
    public SimulationFrame(double percentInPerson,
                           List<TicketCounterConfig> counterConfigs,
                           int numCheckpoints,
                           double checkpointRate,
                           int arrivalSpanMinutes,
                           int intervalMinutes,
                           int transitDelayMinutes,
                           int holdDelayMinutes,
                           List<Flight> flights) {
        this(buildEngineWithHoldRooms(
                percentInPerson, counterConfigs, numCheckpoints, checkpointRate,
                arrivalSpanMinutes, intervalMinutes, transitDelayMinutes, holdDelayMinutes, flights
        ));
    }

    public SimulationFrame(double percentInPerson,
                           List<TicketCounterConfig> counterConfigs,
                           List<CheckpointConfig> checkpointConfigs,
                           int arrivalSpanMinutes,
                           int intervalMinutes,
                           int transitDelayMinutes,
                           int holdDelayMinutes,
                           List<Flight> flights) {
        this(buildEngineWithHoldRooms(
                percentInPerson, counterConfigs, checkpointConfigs,
                arrivalSpanMinutes, intervalMinutes, transitDelayMinutes, holdDelayMinutes, flights
        ));
    }

    private static SimulationEngine buildEngineWithHoldRooms(double percentInPerson,
                                                             List<TicketCounterConfig> counterConfigs,
                                                             int numCheckpoints,
                                                             double checkpointRate,
                                                             int arrivalSpanMinutes,
                                                             int intervalMinutes,
                                                             int transitDelayMinutes,
                                                             int holdDelayMinutes,
                                                             List<Flight> flights) {
        List<HoldRoomConfig> holdRooms = buildDefaultHoldRoomConfigs(flights, holdDelayMinutes);
        return new SimulationEngine(
                percentInPerson, counterConfigs, numCheckpoints, checkpointRate,
                arrivalSpanMinutes, intervalMinutes, transitDelayMinutes, holdDelayMinutes, flights, holdRooms
        );
    }

    private static SimulationEngine buildEngineWithHoldRooms(double percentInPerson,
                                                             List<TicketCounterConfig> counterConfigs,
                                                             List<CheckpointConfig> checkpointConfigs,
                                                             int arrivalSpanMinutes,
                                                             int intervalMinutes,
                                                             int transitDelayMinutes,
                                                             int holdDelayMinutes,
                                                             List<Flight> flights) {
        List<HoldRoomConfig> holdRooms = buildDefaultHoldRoomConfigs(flights, holdDelayMinutes);
        return new SimulationEngine(
                percentInPerson, counterConfigs, checkpointConfigs,
                arrivalSpanMinutes, intervalMinutes, transitDelayMinutes, holdDelayMinutes, flights, holdRooms
        );
    }

    private static List<HoldRoomConfig> buildDefaultHoldRoomConfigs(List<Flight> flights, int holdDelayMinutes) {
        List<HoldRoomConfig> list = new ArrayList<>();
        int n = (flights == null) ? 0 : flights.size();
        for (int i = 0; i < n; i++) {
            Flight f = flights.get(i);
            HoldRoomConfig cfg = tryInstantiateHoldRoomConfig(i + 1, "Hold Room " + (i + 1));
            if (cfg == null) {
                cfg = new HoldRoomConfig(i + 1);
            }
            bestEffortSetWalkTime(cfg, holdDelayMinutes, 0);
            bestEffortAssignSingleFlight(cfg, f);
            list.add(cfg);
        }
        return list;
    }

    private static HoldRoomConfig tryInstantiateHoldRoomConfig(int id, String name) {
        try {
            Constructor<HoldRoomConfig> c = HoldRoomConfig.class.getConstructor(int.class);
            HoldRoomConfig cfg = c.newInstance(id);
            bestEffortInvoke(cfg, "setName", new Class<?>[]{String.class}, new Object[]{name});
            return cfg;
        } catch (Exception ignored) { }
        try {
            Constructor<HoldRoomConfig> c = HoldRoomConfig.class.getConstructor(String.class);
            return c.newInstance(name);
        } catch (Exception ignored) { }
        try {
            Constructor<HoldRoomConfig> c0 = HoldRoomConfig.class.getConstructor();
            HoldRoomConfig cfg = c0.newInstance();
            bestEffortInvoke(cfg, "setName", new Class<?>[]{String.class}, new Object[]{name});
            return cfg;
        } catch (Exception ignored) { }
        return null;
    }

    private static void bestEffortSetWalkTime(HoldRoomConfig cfg, int minutes, int seconds) {
        if (cfg == null) return;
        if (bestEffortInvoke(cfg, "setWalkTime", new Class<?>[]{int.class, int.class}, new Object[]{minutes, seconds})) return;
        if (bestEffortInvoke(cfg, "setWalkMinutesSeconds", new Class<?>[]{int.class, int.class}, new Object[]{minutes, seconds})) return;
        if (bestEffortInvoke(cfg, "setTravelTime", new Class<?>[]{int.class, int.class}, new Object[]{minutes, seconds})) return;
        int totalSeconds = Math.max(0, minutes) * 60 + Math.max(0, seconds);
        bestEffortInvoke(cfg, "setWalkSeconds", new Class<?>[]{int.class}, new Object[]{totalSeconds});
    }

    private static void bestEffortAssignSingleFlight(HoldRoomConfig cfg, Flight f) {
        if (cfg == null || f == null) return;
        if (bestEffortInvoke(cfg, "setAllowedFlights", new Class<?>[]{java.util.Collection.class}, new Object[]{Collections.singletonList(f)})) return;
        if (bestEffortInvoke(cfg, "addAllowedFlight", new Class<?>[]{Flight.class}, new Object[]{f})) return;
        bestEffortInvoke(cfg, "setAllowedFlightNumbers", new Class<?>[]{java.util.Collection.class}, new Object[]{Collections.singletonList(f.getFlightNumber())});
        bestEffortInvoke(cfg, "addAllowedFlightNumber", new Class<?>[]{String.class}, new Object[]{f.getFlightNumber()});
    }

    private static boolean bestEffortInvoke(Object target, String methodName, Class<?>[] sig, Object[] args) {
        if (target == null) return false;
        try {
            Method m = target.getClass().getMethod(methodName, sig);
            m.invoke(target, args);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    // ==========================================================
    // App constructors
    // ==========================================================
    public SimulationFrame(SimulationEngine engine) {
        this(engine, null);
    }

    public SimulationFrame(SimulationEngine engine, FloorplanProject floorplanProjectCopy) {
        super(floorplanProjectCopy != null ? "Simulation View (Floorplan)" : "Simulation View");
        this.engineRef = engine;
        this.floorplanProjectRef = floorplanProjectCopy;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        LocalTime firstDep = engineRef.getFlights().stream()
                .map(Flight::getDepartureTime)
                .min(LocalTime::compareTo)
                .orElse(LocalTime.MIDNIGHT);
        startTime = firstDep.minusMinutes(engineRef.getArrivalSpan());

        // ==========================================================
        // Top header: legend + time
        // ==========================================================
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.X_AXIS));

        JPanel legendPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        legendPanel.setBorder(BorderFactory.createTitledBorder("Legend"));
        for (Flight f : engineRef.getFlights()) {
            legendPanel.add(new JLabel(f.getShape().name() + " = " + f.getFlightNumber()));
        }
        topPanel.add(legendPanel);
        topPanel.add(Box.createHorizontalGlue());

        timeLabel = new JLabel(formatClockWithSeconds());
        timeLabel.setFont(timeLabel.getFont().deriveFont(Font.BOLD, 16f));
        timeLabel.setBorder(BorderFactory.createTitledBorder("Current Time"));
        timeLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel timePanel = new JPanel(new BorderLayout());
        timePanel.setPreferredSize(new Dimension(200, 50));
        timePanel.setMaximumSize(new Dimension(200, 50));
        timePanel.add(timeLabel, BorderLayout.CENTER);
        topPanel.add(timePanel);
        topPanel.add(Box.createRigidArea(new Dimension(20, 0)));

        add(topPanel, BorderLayout.NORTH);

        // ==========================================================
        // Queues view
        // ==========================================================
        JPanel split = new JPanel();
        split.setLayout(new BoxLayout(split, BoxLayout.X_AXIS));

        int cellW = 60 / 3, boxSize = 60, gutter = 30, padding = 100;
        int queuedW = GridRenderer.COLS * cellW;
        int servedW = GridRenderer.COLS * cellW;
        int panelW = queuedW + boxSize + servedW + padding;

        TicketLinesPanel ticketPanel = new TicketLinesPanel(engineRef, new ArrayList<>(), new ArrayList<>(), null);
        Dimension tPref = ticketPanel.getPreferredSize();
        ticketPanel.setPreferredSize(new Dimension(panelW, tPref.height));
        ticketPanel.setMinimumSize(ticketPanel.getPreferredSize());
        ticketPanel.setMaximumSize(ticketPanel.getPreferredSize());
        split.add(Box.createHorizontalStrut(gutter));
        split.add(ticketPanel);
        split.add(Box.createHorizontalStrut(gutter));

        CheckpointLinesPanel cpPanel = new CheckpointLinesPanel(engineRef, new ArrayList<>(), new ArrayList<>(), null);
        Dimension cPref = cpPanel.getPreferredSize();
        cpPanel.setPreferredSize(new Dimension(panelW, cPref.height));
        cpPanel.setMinimumSize(cpPanel.getPreferredSize());
        cpPanel.setMaximumSize(cpPanel.getPreferredSize());
        split.add(cpPanel);
        split.add(Box.createHorizontalStrut(gutter));

        HoldRoomsPanel holdPanel = new HoldRoomsPanel(engineRef, new ArrayList<>(), new ArrayList<>(), null);
        split.add(holdPanel);

        JScrollPane centerScroll = new JScrollPane(
                split, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS
        );
        centerScroll.getHorizontalScrollBar().setUnitIncrement(16);

        // ==========================================================
        // Tabs
        // ==========================================================
        viewTabs = new JTabbedPane();
        viewTabs.addTab("Queues", centerScroll);

        if (floorplanProjectCopy != null) {
            Object img = null;
            try { img = floorplanProjectCopy.getFloorplanImage(); } catch (Throwable ignored) {}
            if (img != null) {
                floorplanPanel = new FloorplanSimulationPanel(floorplanProjectCopy, engineRef);

                JPanel fpWrap = new JPanel(new BorderLayout());
                JToolBar fpBar = new JToolBar();
                fpBar.setFloatable(false);

                JButton resetViewBtn = new JButton("Reset View");
                resetViewBtn.addActionListener(e -> bestEffortInvoke(floorplanPanel, "resetView", new Class<?>[]{}, new Object[]{}));
                fpBar.add(resetViewBtn);

                fpBar.addSeparator();
                fpBar.add(new JLabel("Walk speed (m/s):"));
                JSpinner walkSpeedSpinner = new JSpinner(new SpinnerNumberModel(1.34, 0.20, 3.50, 0.05));
                walkSpeedSpinner.setMaximumSize(new Dimension(90, 28));
                fpBar.add(walkSpeedSpinner);
                JButton applyWalkBtn = new JButton("Apply");
                applyWalkBtn.addActionListener(e -> applyWalkSpeedBestEffort(walkSpeedSpinner));
                fpBar.add(applyWalkBtn);

                fpBar.addSeparator();
                JCheckBox showFootprintsBox = new JCheckBox("Show Personal Space");
                showFootprintsBox.setSelected(false);
                showFootprintsBox.addActionListener(e -> bestEffortInvoke(floorplanPanel, "setShowPassengerFootprints", new Class<?>[]{boolean.class}, new Object[]{showFootprintsBox.isSelected()}));
                fpBar.add(showFootprintsBox);

                // Lines/transit sizes
                fpBar.addSeparator();
                fpBar.add(new JLabel("Lines/Transit L(ft):"));
                double initLineLenFt = safeProjectDouble(floorplanProjectCopy, "getPassengerLengthFeet", "passengerLengthFeet", 2.0);
                double initLineWidFt = safeProjectDouble(floorplanProjectCopy, "getPassengerWidthFeet", "passengerWidthFeet", 1.5);
                JSpinner lineLenSpinner = new JSpinner(new SpinnerNumberModel(initLineLenFt, 0.25, 30.0, 0.1));
                lineLenSpinner.setMaximumSize(new Dimension(70, 28));
                fpBar.add(lineLenSpinner);
                fpBar.add(Box.createHorizontalStrut(6));
                fpBar.add(new JLabel("W(ft):"));
                JSpinner lineWidSpinner = new JSpinner(new SpinnerNumberModel(initLineWidFt, 0.25, 30.0, 0.1));
                lineWidSpinner.setMaximumSize(new Dimension(70, 28));
                fpBar.add(lineWidSpinner);
                JButton applyLinesBtn = new JButton("Apply Lines/Transit");
                applyLinesBtn.addActionListener(e -> applyLineFootprintBestEffort(lineLenSpinner, lineWidSpinner));
                fpBar.add(applyLinesBtn);

                // Holdroom sizes
                fpBar.addSeparator();
                fpBar.add(new JLabel("Holdrooms L(ft):"));
                double initHoldLenFt = safeProjectDouble(floorplanProjectCopy, "getHoldPassengerLengthFeet", "holdPassengerLengthFeet",
                        safeProjectDouble(floorplanProjectCopy, "getHoldroomPassengerLengthFeet", "holdroomPassengerLengthFeet", initLineLenFt));
                double initHoldWidFt = safeProjectDouble(floorplanProjectCopy, "getHoldPassengerWidthFeet", "holdPassengerWidthFeet",
                        safeProjectDouble(floorplanProjectCopy, "getHoldroomPassengerWidthFeet", "holdroomPassengerWidthFeet", initLineWidFt));
                JSpinner holdLenSpinner = new JSpinner(new SpinnerNumberModel(initHoldLenFt, 0.25, 30.0, 0.1));
                holdLenSpinner.setMaximumSize(new Dimension(70, 28));
                fpBar.add(holdLenSpinner);
                fpBar.add(Box.createHorizontalStrut(6));
                fpBar.add(new JLabel("W(ft):"));
                JSpinner holdWidSpinner = new JSpinner(new SpinnerNumberModel(initHoldWidFt, 0.25, 30.0, 0.1));
                holdWidSpinner.setMaximumSize(new Dimension(70, 28));
                fpBar.add(holdWidSpinner);
                JButton applyHoldBtn = new JButton("Apply Holdrooms");
                applyHoldBtn.addActionListener(e -> applyHoldFootprintBestEffort(holdLenSpinner, holdWidSpinner));
                fpBar.add(applyHoldBtn);

                fpWrap.add(fpBar, BorderLayout.NORTH);
                fpWrap.add(floorplanPanel, BorderLayout.CENTER);
                viewTabs.addTab("Floorplan", fpWrap);

                // Initialize panel playback step to match slider at start
                trySetPanelPlaybackStepSeconds(playbackStepSeconds);
            }
        }

        viewTabs.addChangeListener(e -> {
            if (floorplanPanel != null) {
                floorplanPanel.repaint();
                floorplanPanel.requestFocusInWindow();
            }
        });

        // ==========================================================
        // Bottom control panel
        // ==========================================================
        JPanel control = new JPanel();
        control.setLayout(new BoxLayout(control, BoxLayout.Y_AXIS));
        control.setPreferredSize(new Dimension(800, 190));
        control.setMinimumSize(new Dimension(0, 160));

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, viewTabs, control);
        mainSplit.setResizeWeight(0.88);
        mainSplit.setContinuousLayout(true);
        mainSplit.setOneTouchExpandable(true);
        add(mainSplit, BorderLayout.CENTER);
        SwingUtilities.invokeLater(() -> mainSplit.setDividerLocation(0.86));

        // ==========================================================
        // Buttons row (+ Next/Prev with typed mm:ss)
        // ==========================================================
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        prevBtn = new JButton("Prev");
        nextBtn = new JButton("Next");

        btnPanel.add(new JLabel("Jump:"));
        jumpMinSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 999, 1));
        jumpMinSpinner.setPreferredSize(new Dimension(60, 24));
        btnPanel.add(jumpMinSpinner);
        btnPanel.add(new JLabel("min"));

        jumpSecSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));
        jumpSecSpinner.setPreferredSize(new Dimension(50, 24));
        btnPanel.add(jumpSecSpinner);
        btnPanel.add(new JLabel("sec"));

        btnPanel.add(prevBtn);
        btnPanel.add(nextBtn);

        autoRunBtn = new JButton("AutoRun");
        pausePlayBtn = new JButton("Pause");
        summaryBtn = new JButton("Summary");
        summaryBtn.setEnabled(false);
        pausePlayBtn.setVisible(false);
        btnPanel.add(autoRunBtn);
        btnPanel.add(pausePlayBtn);

        JButton graphsBtn = new JButton("Graphs...");
        graphsBtn.addActionListener(e -> {
            if (graphsWindow == null || !graphsWindow.isDisplayable()) {
                graphsWindow = new GraphWindow(engineRef);
            }
            graphsWindow.setViewedInterval(engineRef.getCurrentInterval());
            graphsWindow.updateFromEngine();
            graphsWindow.setVisible(true);
            graphsWindow.toFront();
            graphsWindow.requestFocus();
        });
        btnPanel.add(graphsBtn);
        btnPanel.add(summaryBtn);

        control.add(btnPanel);

        // ==========================================================
        // Timeline
        // ==========================================================
        JPanel timelinePanel = new JPanel(new BorderLayout(8, 6));
        timelinePanel.setBorder(BorderFactory.createTitledBorder("Timeline (rewind / review computed intervals)"));
        intervalLabel = new JLabel();
        intervalLabel.setPreferredSize(new Dimension(260, 20));
        intervalLabel.setHorizontalAlignment(SwingConstants.LEFT);

        timelineSlider = new JSlider(0, Math.max(0, engineRef.getMaxComputedInterval()), 0);
        timelineSlider.setPaintTicks(true);
        timelineSlider.setPaintLabels(true);
        int initialMajor = computeMajorTickSpacing(timelineSlider.getMaximum());
        timelineSlider.setMajorTickSpacing(initialMajor);
        timelineSlider.setMinorTickSpacing(1);
        rebuildTimelineLabels(timelineSlider);
        timelinePanel.add(intervalLabel, BorderLayout.NORTH);
        timelinePanel.add(timelineSlider, BorderLayout.CENTER);
        control.add(timelinePanel);

        // ==========================================================
        // Playback Step (seconds per tick)
        // ==========================================================
        JPanel sliderPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        sliderPanel.setBorder(BorderFactory.createTitledBorder("Playback Step (seconds per tick)"));
        playbackStepSlider = new JSlider(1, 300, 1); // 1s .. 300s
        playbackStepSlider.setMajorTickSpacing(60);
        playbackStepSlider.setMinorTickSpacing(5);
        playbackStepSlider.setPaintTicks(true);
        playbackStepSlider.setPaintLabels(true);
        Hashtable<Integer, JLabel> labels = new Hashtable<>();
        labels.put(1, new JLabel("1s"));
        labels.put(5, new JLabel("5s"));
        labels.put(15, new JLabel("15s"));
        labels.put(60, new JLabel("1m"));
        labels.put(120, new JLabel("2m"));
        labels.put(300, new JLabel("5m"));
        playbackStepSlider.setLabelTable(labels);
        sliderPanel.add(playbackStepSlider);
        control.add(sliderPanel);

        // ==========================================================
        // Summary window
        // ==========================================================
        summaryBtn.addActionListener(e -> new FlightsSummaryFrame(engineRef).setVisible(true));

        // ==========================================================
        // UI refresh helper
        // ==========================================================
        Runnable refreshUI = () -> {
            timeLabel.setText(formatClockWithSeconds());
            split.repaint();
            if (floorplanPanel != null) floorplanPanel.repaint();

            int maxComputed = engineRef.getMaxComputedInterval();
            timelineProgrammaticUpdate = true;
            try {
                if (timelineSlider.getMaximum() != maxComputed) {
                    timelineSlider.setMaximum(maxComputed);
                    int major = computeMajorTickSpacing(maxComputed);
                    timelineSlider.setMajorTickSpacing(major);
                    timelineSlider.setMinorTickSpacing(1);
                    rebuildTimelineLabels(timelineSlider);
                }
                int ci = engineRef.getCurrentInterval();
                timelineSlider.setValue(Math.min(ci, timelineSlider.getMaximum()));
            } finally {
                timelineProgrammaticUpdate = false;
            }

            intervalLabel.setText("Interval: " + engineRef.getCurrentInterval() + " / " + engineRef.getTotalIntervals());

            prevBtn.setEnabled(engineRef.canRewind());
            boolean canAdvance = engineRef.getCurrentInterval() < engineRef.getTotalIntervals();
            nextBtn.setEnabled(canAdvance);
            if (autoRunTimer == null || !autoRunTimer.isRunning()) {
                autoRunBtn.setEnabled(canAdvance);
            }
            if (simulationCompleted) summaryBtn.setEnabled(true);

            if (graphsWindow != null && graphsWindow.isDisplayable()) {
                graphsWindow.setViewedInterval(engineRef.getCurrentInterval());
                graphsWindow.updateFromEngine();
            }
        };

        Consumer<List<Flight>> handleClosures = (closed) -> {
            if (closed == null || closed.isEmpty()) return;
            int step = engineRef.getCurrentInterval() - 1;
            List<Flight> newlyClosed = new ArrayList<>();
            for (Flight f : closed) {
                if (!closeSteps.containsKey(f)) {
                    closeSteps.put(f, step);
                    newlyClosed.add(f);
                }
            }
            if (newlyClosed.isEmpty()) return;

            if (autoRunTimer != null && autoRunTimer.isRunning()) {
                autoRunTimer.stop();
                pausePlayBtn.setText("Play");
                isPaused = true;
            }

            for (Flight f : newlyClosed) {
                int total = (int) Math.round(f.getSeats() * f.getFillPercent());
                int made = 0;
                for (LinkedList<Passenger> room : engineRef.getHoldRoomLines()) {
                    for (Passenger p : room) {
                        if (p != null && p.getFlight() == f) made++;
                    }
                }
                JOptionPane.showMessageDialog(
                        SimulationFrame.this,
                        String.format("%s: %d of %d made their flight.", f.getFlightNumber(), made, total),
                        "Flight Closed",
                        JOptionPane.INFORMATION_MESSAGE
                );
            }
        };

        // ==========================================================
        // Swing timer — fixed UI tick; playbackStepSeconds controls "display seconds/tick"
        // ==========================================================
        autoRunTimer = new javax.swing.Timer(200, ev -> {
            if (engineRef.getCurrentInterval() >= engineRef.getTotalIntervals()) {
                simulationCompleted = true;
                ((javax.swing.Timer) ev.getSource()).stop();
                autoRunBtn.setEnabled(false);
                pausePlayBtn.setEnabled(false);
                summaryBtn.setEnabled(true);
                return;
            }

            // Prefer the new panel API (tickPlaybackStep) if present; else use advanceBySeconds; else fallback.
            boolean panelHandled = false;
            if (floorplanPanel != null) {
                panelHandled = tryTickPanel() || tryAdvancePanelBySeconds(playbackStepSeconds);
            }
            if (!panelHandled) {
                // Fallback path: accumulate frame-local seconds and step engine per full interval
                int intervalSeconds = getEngineIntervalSecondsSafe();
                frameSecondsIntoInterval += playbackStepSeconds;
                while (frameSecondsIntoInterval >= intervalSeconds &&
                        engineRef.getCurrentInterval() < engineRef.getTotalIntervals()) {
                    frameSecondsIntoInterval -= intervalSeconds;
                    engineRef.computeNextInterval();
                }
            }

            refreshUI.run();

            // Check closures after whatever step(s) occurred
            List<Flight> closed = engineRef.getFlightsJustClosed();
            handleClosures.accept(closed);

            if (engineRef.getCurrentInterval() >= engineRef.getTotalIntervals()) {
                simulationCompleted = true;
                ((javax.swing.Timer) ev.getSource()).stop();
                autoRunBtn.setEnabled(false);
                pausePlayBtn.setEnabled(false);
                summaryBtn.setEnabled(true);
            }
        });

        playbackStepSlider.addChangeListener((ChangeEvent e) -> {
            playbackStepSeconds = Math.max(1, Math.min(300, playbackStepSlider.getValue()));
            // Keep the panel in sync if it exposes setPlaybackStepSeconds
            trySetPanelPlaybackStepSeconds(playbackStepSeconds);
        });

        prevBtn.addActionListener(ev -> {
            stopTimerIfRunning();
            int totalSec = getJumpSeconds();
            if (totalSec <= 0) return;

            if (floorplanPanel != null && tryRewindPanelBySeconds(totalSec)) {
                // handled by panel (rewind by seconds)
            } else {
                // fallback by whole intervals
                int intervalSeconds = getEngineIntervalSecondsSafe();
                int toRewindIntervals = totalSec / intervalSeconds;
                int remSeconds = totalSec % intervalSeconds;

                for (int i = 0; i < toRewindIntervals && engineRef.canRewind(); i++) {
                    engineRef.rewindOneInterval();
                }
                // set display offset inside interval (frame-local fallback)
                frameSecondsIntoInterval = Math.max(0, getDisplaySecondsFromPanelIfAny());
                frameSecondsIntoInterval = Math.max(0, frameSecondsIntoInterval - remSeconds);
                if (frameSecondsIntoInterval < 0) frameSecondsIntoInterval = 0;
            }
            refreshUI.run();
        });

        nextBtn.addActionListener(ev -> {
            stopTimerIfRunning();
            int totalSec = getJumpSeconds();
            if (totalSec <= 0) return;

            if (floorplanPanel != null && tryAdvancePanelBySeconds(totalSec)) {
                // handled by panel (advance by seconds)
            } else {
                int intervalSeconds = getEngineIntervalSecondsSafe();
                frameSecondsIntoInterval += totalSec;
                while (frameSecondsIntoInterval >= intervalSeconds &&
                        engineRef.getCurrentInterval() < engineRef.getTotalIntervals()) {
                    frameSecondsIntoInterval -= intervalSeconds;
                    engineRef.computeNextInterval();
                }
            }

            refreshUI.run();

            List<Flight> closed = engineRef.getFlightsJustClosed();
            handleClosures.accept(closed);
            if (engineRef.getCurrentInterval() >= engineRef.getTotalIntervals()) {
                simulationCompleted = true;
                nextBtn.setEnabled(false);
                autoRunBtn.setEnabled(false);
                summaryBtn.setEnabled(true);
            }
        });

        timelineSlider.addChangeListener((ChangeEvent e) -> {
            if (timelineProgrammaticUpdate) return;
            if (timelineSlider.getValueIsAdjusting()) {
                intervalLabel.setText("Interval: " + timelineSlider.getValue() + " / " + engineRef.getTotalIntervals());
                int v = timelineSlider.getValue();
                if (graphsWindow != null && graphsWindow.isDisplayable()) {
                    graphsWindow.setViewedInterval(v);
                    graphsWindow.updateFromEngine();
                }
                if (floorplanPanel != null) floorplanPanel.repaint();
                return;
            }
            int target = timelineSlider.getValue();
            stopTimerIfRunning();
            engineRef.goToInterval(target);
            // Reset frame-local seconds when scrubbing (panel manages its own seconds)
            frameSecondsIntoInterval = 0;
            refreshUI.run();
        });

        autoRunBtn.addActionListener(e -> {
            autoRunBtn.setEnabled(false);
            pausePlayBtn.setVisible(true);
            pausePlayBtn.setText("Pause");
            isPaused = false;
            // Keep panel step in sync at run start
            trySetPanelPlaybackStepSeconds(playbackStepSeconds);
            if (autoRunTimer != null) autoRunTimer.start();
        });

        pausePlayBtn.addActionListener(e -> {
            if (autoRunTimer == null) return;
            if (isPaused) {
                autoRunTimer.start();
                pausePlayBtn.setText("Pause");
            } else {
                autoRunTimer.stop();
                pausePlayBtn.setText("Play");
            }
            isPaused = !isPaused;
            refreshUI.run();
        });

        // Initial sync of panel step with slider value (in case Floorplan tab was added)
        trySetPanelPlaybackStepSeconds(playbackStepSeconds);

        refreshUI.run();
        setSize(1000, 930);
        setLocationRelativeTo(null);
    }

    // ==========================================================
    // Floorplan controls (best-effort)
    // ==========================================================
    private void applyWalkSpeedBestEffort(JSpinner walkSpeedSpinner) {
        if (floorplanPanel == null && engineRef == null) return;
        double v;
        try { v = ((Number) walkSpeedSpinner.getValue()).doubleValue(); }
        catch (Exception ex) { return; }

        // Panel (no compile dependency)
        bestEffortInvoke(floorplanPanel, "setWalkSpeedMps", new Class<?>[]{double.class}, new Object[]{v});

        // Engine unified knob (future-proof)
        bestEffortInvoke(engineRef, "setWalkSpeedMps", new Class<?>[]{double.class}, new Object[]{v});

        // Provider (if reachable)
        Object provider = null;
        try {
            Method gm = engineRef.getClass().getMethod("getTravelTimeProvider");
            provider = gm.invoke(engineRef);
        } catch (Exception ignored) { }
        if (provider == null) provider = tryGetField(engineRef, "travelTimeProvider");
        if (provider instanceof FloorplanTravelTimeProvider) {
            try { ((FloorplanTravelTimeProvider) provider).setWalkSpeedMps(v); } catch (Exception ignored) { }
        } else if (provider != null) {
            bestEffortInvoke(provider, "setWalkSpeedMps", new Class<?>[]{double.class}, new Object[]{v});
        }

        if (floorplanPanel != null) floorplanPanel.repaint();
    }

    private void applyLineFootprintBestEffort(JSpinner lenFtSpinner, JSpinner widFtSpinner) {
        if (floorplanPanel == null) return;
        double lenFt, widFt;
        try {
            lenFt = ((Number) lenFtSpinner.getValue()).doubleValue();
            widFt = ((Number) widFtSpinner.getValue()).doubleValue();
        } catch (Exception ex) { return; }

        if (floorplanProjectRef != null) {
            bestEffortInvoke(floorplanProjectRef, "setPassengerLengthFeet", new Class<?>[]{double.class}, new Object[]{lenFt});
            bestEffortInvoke(floorplanProjectRef, "setPassengerWidthFeet", new Class<?>[]{double.class}, new Object[]{widFt});
        }
        bestEffortInvoke(floorplanPanel, "setLineFootprintFeet", new Class<?>[]{double.class, double.class}, new Object[]{lenFt, widFt});
        bestEffortInvoke(floorplanPanel, "setPassengerFootprintFeet", new Class<?>[]{double.class, double.class}, new Object[]{lenFt, widFt});
        floorplanPanel.repaint();
    }

    private void applyHoldFootprintBestEffort(JSpinner lenFtSpinner, JSpinner widFtSpinner) {
        if (floorplanPanel == null) return;
        double lenFt, widFt;
        try {
            lenFt = ((Number) lenFtSpinner.getValue()).doubleValue();
            widFt = ((Number) widFtSpinner.getValue()).doubleValue();
        } catch (Exception ex) { return; }

        if (floorplanProjectRef != null) {
            bestEffortInvoke(floorplanProjectRef, "setHoldPassengerLengthFeet", new Class<?>[]{double.class}, new Object[]{lenFt});
            bestEffortInvoke(floorplanProjectRef, "setHoldPassengerWidthFeet", new Class<?>[]{double.class}, new Object[]{widFt});
            bestEffortInvoke(floorplanProjectRef, "setHoldroomPassengerLengthFeet", new Class<?>[]{double.class}, new Object[]{lenFt});
            bestEffortInvoke(floorplanProjectRef, "setHoldroomPassengerWidthFeet", new Class<?>[]{double.class}, new Object[]{widFt});
        }
        bestEffortInvoke(floorplanPanel, "setHoldFootprintFeet", new Class<?>[]{double.class, double.class}, new Object[]{lenFt, widFt});
        bestEffortInvoke(floorplanPanel, "setHoldroomFootprintFeet", new Class<?>[]{double.class, double.class}, new Object[]{lenFt, widFt});
        floorplanPanel.repaint();
    }

    // ==========================================================
    // Reflection utilities
    // ==========================================================
    private static Object tryGetField(Object target, String fieldName) {
        if (target == null) return null;
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (Exception ignored) { }
            c = c.getSuperclass();
        }
        return null;
    }

    private static double safeProjectDouble(Object target, String getterName, String fieldName, double fallback) {
        if (target == null) return fallback;
        if (getterName != null && !getterName.trim().isEmpty()) {
            try {
                Method m = target.getClass().getMethod(getterName);
                Object out = m.invoke(target);
                if (out instanceof Number) return ((Number) out).doubleValue();
            } catch (Throwable ignored) { }
        }
        if (fieldName != null && !fieldName.trim().isEmpty()) {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    Object out = f.get(target);
                    if (out instanceof Number) return ((Number) out).doubleValue();
                    break;
                } catch (Throwable ignored) {
                    c = c.getSuperclass();
                }
            }
        }
        return fallback;
    }

    private static int computeMajorTickSpacing(int maxIntervals) {
        if (maxIntervals >= 1000) return 500;
        if (maxIntervals >= 500) return 100;
        if (maxIntervals >= 150) return 50;
        if (maxIntervals >= 100) return 20;
        if (maxIntervals >= 50) return 10;
        if (maxIntervals >= 20) return 5;
        return 1;
    }

    private static void rebuildTimelineLabels(JSlider slider) {
        if (slider == null) return;
        int max = slider.getMaximum();
        int major = slider.getMajorTickSpacing();
        if (major <= 0) major = 1;
        Hashtable<Integer, JLabel> table = new Hashtable<>();
        table.put(0, new JLabel("0"));
        for (int v = major; v < max; v += major) {
            table.put(v, new JLabel(String.valueOf(v)));
        }
        if (max != 0) {
            int lastMajor = (max / major) * major;
            if (lastMajor == max) {
                table.put(max, new JLabel(String.valueOf(max)));
            } else {
                if (lastMajor > 0 && (max - lastMajor) < Math.max(1, major / 2)) {
                    table.remove(lastMajor);
                }
                table.put(max, new JLabel(String.valueOf(max)));
            }
        }
        slider.setLabelTable(table);
        slider.setPaintLabels(true);
        slider.setPaintTicks(true);
        slider.repaint();
    }

    // ==========================================================
    // Helpers for new playback behavior
    // ==========================================================
    private int getEngineIntervalSecondsSafe() {
        // Try engine.getIntervalSeconds()
        try {
            Method m = engineRef.getClass().getMethod("getIntervalSeconds");
            Object o = m.invoke(engineRef);
            if (o instanceof Number) return Math.max(1, ((Number) o).intValue());
        } catch (Exception ignored) {}

        // Fallback: intervalMinutes * 60
        try {
            Method m = engineRef.getClass().getMethod("getIntervalMinutes");
            Object o = m.invoke(engineRef);
            if (o instanceof Number) {
                int minutes = Math.max(1, ((Number) o).intValue());
                return minutes * 60;
            }
        } catch (Exception ignored) {}

        // Conservative default
        return 60;
    }

    private boolean trySetPanelPlaybackStepSeconds(int seconds) {
        if (floorplanPanel == null) return false;
        try {
            Method m = floorplanPanel.getClass().getMethod("setPlaybackStepSeconds", int.class);
            m.invoke(floorplanPanel, Math.max(1, Math.min(300, seconds)));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean tryTickPanel() {
        if (floorplanPanel == null) return false;
        try {
            Method m = floorplanPanel.getClass().getMethod("tickPlaybackStep");
            m.invoke(floorplanPanel);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean tryAdvancePanelBySeconds(int seconds) {
        try {
            Method m = floorplanPanel.getClass().getMethod("advanceBySeconds", int.class);
            m.invoke(floorplanPanel, seconds);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean tryRewindPanelBySeconds(int seconds) {
        try {
            Method m = floorplanPanel.getClass().getMethod("rewindBySeconds", int.class);
            m.invoke(floorplanPanel, seconds);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private int getDisplaySecondsFromPanelIfAny() {
        if (floorplanPanel == null) return frameSecondsIntoInterval;
        try {
            Method m = floorplanPanel.getClass().getMethod("getSecondsIntoInterval");
            Object o = m.invoke(floorplanPanel);
            if (o instanceof Number) return Math.max(0, ((Number) o).intValue());
        } catch (Exception ignored) { }
        try {
            Method m = floorplanPanel.getClass().getMethod("getDisplayClockSeconds");
            Object o = m.invoke(floorplanPanel);
            if (o instanceof Number) return Math.max(0, ((Number) o).intValue());
        } catch (Exception ignored) { }
        return frameSecondsIntoInterval;
    }

    private int getJumpSeconds() {
        int min = 0, sec = 0;
        try { min = ((Number) jumpMinSpinner.getValue()).intValue(); } catch (Exception ignored) { }
        try { sec = ((Number) jumpSecSpinner.getValue()).intValue(); } catch (Exception ignored) { }
        min = Math.max(0, min);
        sec = Math.max(0, Math.min(59, sec));
        return min * 60 + sec;
    }

    private void stopTimerIfRunning() {
        if (autoRunTimer != null && autoRunTimer.isRunning()) {
            autoRunTimer.stop();
            pausePlayBtn.setText("Play");
            isPaused = true;
        }
    }

    // ✅ FIXED: intervalMinutes != 1 now displays correctly (uses intervalSeconds * currentInterval)
    private String formatClockWithSeconds() {
        int intervalSeconds = getEngineIntervalSecondsSafe();
        int secondsIntoInterval = getDisplaySecondsFromPanelIfAny();
        if (intervalSeconds > 0) {
            secondsIntoInterval = Math.max(0, Math.min(intervalSeconds - 1, secondsIntoInterval));
        } else {
            secondsIntoInterval = Math.max(0, secondsIntoInterval);
        }

        long baseSeconds = (long) Math.max(0, engineRef.getCurrentInterval()) * (long) intervalSeconds;
        long simSeconds = baseSeconds + (long) secondsIntoInterval;

        LocalTime now = startTime.plusSeconds(simSeconds);
        return now.format(TIME_FMT);
    }
}
