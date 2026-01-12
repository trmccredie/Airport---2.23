package sim.ui;

import sim.model.Flight;
import sim.model.Passenger;
import sim.service.SimulationEngine;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class FlightsSummaryFrame extends JFrame {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public FlightsSummaryFrame(SimulationEngine engine) {
        super("All Flights Summary");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        List<Flight> flights = (engine != null) ? engine.getFlights() : null;
        if (flights == null || flights.isEmpty()) {
            add(new JLabel("No flights."), BorderLayout.CENTER);
            pack();
            setLocationRelativeTo(null);
            setVisible(true);
            return;
        }

        // Recompute global simulation start time (same logic as other UIs)
        LocalTime firstDep = flights.stream()
                .map(Flight::getDepartureTime)
                .filter(t -> t != null)
                .min(LocalTime::compareTo)
                .orElse(LocalTime.MIDNIGHT);
        int arrivalSpan = (engine != null) ? engine.getArrivalSpan() : 0;
        LocalTime globalStart = firstDep.minusMinutes(Math.max(0, arrivalSpan));

        int cols = Math.min(4, Math.max(1, flights.size())); // up to 4 per row
        JPanel grid = new JPanel(new GridLayout(0, cols, 10, 10));

        int maxHistoryStep = getMaxHistoryStep(engine);
        int boardingCloseMinutes = resolveBoardingCloseMinutes(engine, 20);

        for (Flight f : flights) {
            if (f == null || f.getDepartureTime() == null) continue;

            LocalTime closeTime = f.getDepartureTime().minusMinutes(Math.max(0, boardingCloseMinutes));

            // closeStep is the minute index used by the UI clock label
            int closeStep = (int) Duration.between(globalStart, closeTime).toMinutes();

            // history index for "state at closeTime" is closeStep - 1 (because history[0] == time 1)
            int closeHistoryIndex = closeStep - 1;
            int step = clamp(closeHistoryIndex, 0, maxHistoryStep);

            String madeText = "";
            String tooltipExtra = "";
            try {
                int total = Math.max(0, (int) Math.round(f.getSeats() * f.getFillPercent()));
                int made = countMadeAtHistoryStep(engine, f, step);
                int missed = Math.max(0, total - made);

                madeText = String.format("  (%d/%d)", made, total);
                tooltipExtra = " • made=" + made + " missed=" + missed + " total=" + total;
            } catch (Exception ignored) { }

            String label = f.getFlightNumber() + " @ " + closeTime.format(TIME_FMT) + madeText;
            JButton btn = new JButton(label);

            String tip = "Close minute index: " + closeStep
                    + " | history index used: " + step
                    + (step != closeHistoryIndex ? (" (clamped from " + closeHistoryIndex + ")") : "")
                    + tooltipExtra;
            btn.setToolTipText(tip);

            // Step is a HISTORY INDEX (not a clock minute index)
            btn.addActionListener(e -> new FlightSnapshotFrame(engine, f, step).setVisible(true));
            grid.add(btn);
        }

        JScrollPane scroll = new JScrollPane(
                grid,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );
        add(scroll, BorderLayout.CENTER);

        pack();
        setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);
        setVisible(true);
    }

    private int getMaxHistoryStep(SimulationEngine engine) {
        try {
            int a = engine.getHistoryQueuedTicket() != null ? engine.getHistoryQueuedTicket().size() : 0;
            int b = engine.getHistoryQueuedCheckpoint() != null ? engine.getHistoryQueuedCheckpoint().size() : 0;
            int c = engine.getHistoryHoldRooms() != null ? engine.getHistoryHoldRooms().size() : 0;

            int min = Math.min(a, Math.min(b, c));
            return Math.max(0, min - 1);
        } catch (Exception ex) {
            return 0;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Count passengers of flight f present in ANY physical hold room at the given history step.
     */
    private static int countMadeAtHistoryStep(SimulationEngine engine, Flight f, int step) {
        if (engine == null || f == null || engine.getHistoryHoldRooms() == null) return 0;
        if (step < 0 || step >= engine.getHistoryHoldRooms().size()) return 0;

        int made = 0;
        List<List<Passenger>> holdAtStep = engine.getHistoryHoldRooms().get(step);
        if (holdAtStep == null) return 0;

        for (List<Passenger> room : holdAtStep) {
            if (room == null) continue;
            for (Passenger p : room) {
                if (p != null && p.getFlight() == f) made++;
            }
        }
        return made;
    }

    /**
     * Try to read the boarding-close minutes from the engine via common names; default if absent.
     */
    private static int resolveBoardingCloseMinutes(SimulationEngine engine, int fallback) {
        if (engine == null) return fallback;

        // Try getters first
        Integer viaGetter = tryInvokeInt(engine, "getBoardingCloseMinutes", "getBoardingCloseOffsetMinutes");
        if (viaGetter != null && viaGetter >= 0) return viaGetter;

        // Try fields as a last resort
        Integer viaField = tryFieldInt(engine, "boardingCloseMinutes", "boardingCloseOffsetMinutes");
        if (viaField != null && viaField >= 0) return viaField;

        return fallback;
    }

    // --- tiny reflection helpers (kept local to avoid pulling other utils) ---

    private static Integer tryInvokeInt(Object target, String... methodNames) {
        if (target == null || methodNames == null) return null;
        for (String name : methodNames) {
            try {
                Method m = target.getClass().getMethod(name);
                Object out = m.invoke(target);
                if (out instanceof Number) return ((Number) out).intValue();
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static Integer tryFieldInt(Object target, String... fieldNames) {
        if (target == null || fieldNames == null) return null;
        for (String fn : fieldNames) {
            try {
                Field f = target.getClass().getDeclaredField(fn);
                f.setAccessible(true);
                Object out = f.get(target);
                if (out instanceof Number) return ((Number) out).intValue();
            } catch (Throwable ignored) { }
        }
        return null;
    }
}
