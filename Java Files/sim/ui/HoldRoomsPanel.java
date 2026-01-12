package sim.ui;

import sim.model.Flight;
import sim.model.Passenger;
import sim.service.SimulationEngine;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Method;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Hold-room grid panel (history viewer).
 * This is a UI panel that renders snapshots. It does NOT change floorplan capacities.
 */
public class HoldRoomsPanel extends JPanel {
    private static final int HOLD_BOX_SIZE = GridRenderer.HOLD_BOX_SIZE;
    private static final int HOLD_GAP      = GridRenderer.HOLD_GAP;

    private final SimulationEngine engine;
    private final Flight           filterFlight;
    private final List<Rectangle>  clickableAreas;
    private final List<Passenger>  clickablePassengers;

    public HoldRoomsPanel(SimulationEngine engine,
                          List<Rectangle> clickableAreas,
                          List<Passenger> clickablePassengers,
                          Flight filterFlight) {
        this.engine              = engine;
        this.filterFlight        = filterFlight;
        this.clickableAreas      = clickableAreas;
        this.clickablePassengers = clickablePassengers;

        // Preferred size that matches the renderer’s "wrap into columns" behavior.
        int count = resolveHoldRoomCount(engine);
        int maxRowsPreferred = 3;
        int cols = (count + maxRowsPreferred - 1) / maxRowsPreferred;

        int labelH = 16;
        int rowHeight = HOLD_BOX_SIZE + labelH + 14;

        int width  = HOLD_GAP + cols * (HOLD_BOX_SIZE + 20) + HOLD_GAP;
        int height = HOLD_GAP + Math.min(count, maxRowsPreferred) * rowHeight + HOLD_GAP;

        setPreferredSize(new Dimension(Math.max(200, width), Math.max(200, height)));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                for (int i = 0; i < clickableAreas.size(); i++) {
                    if (clickableAreas.get(i).contains(e.getPoint())) {
                        Passenger p = clickablePassengers.get(i);
                        showPassengerDetails(p);
                        return;
                    }
                }
            }
        });
    }

    public HoldRoomsPanel(SimulationEngine engine, Flight filterFlight) {
        this(engine, new ArrayList<>(), new ArrayList<>(), filterFlight);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (!hasSnapshotForCurrentInterval()) {
            drawNoDataMessage(g, "Compute at least one interval to view hold rooms.");
            return;
        }

        GridRenderer.renderHoldRooms(
                this, g, engine,
                clickableAreas, clickablePassengers,
                filterFlight
        );
    }

    /**
     * Use engine.getHoldRoomConfigs().size() if possible, otherwise fall back to flights.size().
     */
    private static int resolveHoldRoomCount(SimulationEngine engine) {
        if (engine == null) return 0;

        try {
            Method m = engine.getClass().getMethod("getHoldRoomConfigs");
            Object configs = m.invoke(engine);
            if (configs instanceof List) {
                return ((List<?>) configs).size();
            }
        } catch (Exception ignored) { }

        try {
            return engine.getFlights() == null ? 0 : engine.getFlights().size();
        } catch (Exception e) {
            return 0;
        }
    }

    private void showPassengerDetails(Passenger p) {
        if (p == null || p.getFlight() == null || p.getFlight().getDepartureTime() == null) {
            JOptionPane.showMessageDialog(this, "No details available.", "Passenger Details", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        LocalTime simStart = p.getFlight()
                .getDepartureTime()
                .minusMinutes(engine.getArrivalSpan());
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");

        StringBuilder msg = new StringBuilder();
        msg.append("Flight: ").append(p.getFlight().getFlightNumber());

        // Arrival
        try {
            msg.append("\nArrived at: ")
               .append(simStart.plusMinutes(p.getArrivalMinute()).format(fmt));
        } catch (Throwable ignored) {}

        // Purchase type
        try {
            msg.append("\nPurchase Type: ")
               .append(p.isInPerson() ? "In Person" : "Online");
        } catch (Throwable ignored) {}

        // Ticketing times (in-person only)
        try {
            if (p.isInPerson() && p.getTicketCompletionMinute() >= 0) {
                msg.append("\nTicketed at: ")
                   .append(simStart.plusMinutes(p.getTicketCompletionMinute()).format(fmt));
            }
        } catch (Throwable ignored) {}

        // Checkpoint
        try {
            if (p.getCheckpointEntryMinute() >= 0) {
                msg.append("\nCheckpoint Entry: ")
                   .append(simStart.plusMinutes(p.getCheckpointEntryMinute()).format(fmt));
            }
        } catch (Throwable ignored) {}

        try {
            if (p.getCheckpointCompletionMinute() >= 0) {
                msg.append("\nCheckpoint Completion: ")
                   .append(simStart.plusMinutes(p.getCheckpointCompletionMinute()).format(fmt));
            }
        } catch (Throwable ignored) {}

        // Hold-room
        try {
            if (p.getHoldRoomEntryMinute() >= 0) {
                msg.append("\nHold-room Entry: ")
                   .append(simStart.plusMinutes(p.getHoldRoomEntryMinute()).format(fmt));
            }
        } catch (Throwable ignored) {}

        try {
            if (p.getHoldRoomSequence() >= 0) {
                msg.append("\nHold-room Seq #: ").append(p.getHoldRoomSequence());
            }
        } catch (Throwable ignored) {}

        // Assigned physical room (reflection-safe index)
        int assignedIdx = resolveAssignedHoldRoomIndex(p);
        if (assignedIdx >= 0) {
            msg.append("\nAssigned Hold Room Index: ").append(assignedIdx);
            try {
                List<HoldRoomConfig> cfgs = engine.getHoldRoomConfigs();
                if (cfgs != null && assignedIdx < cfgs.size()) {
                    HoldRoomConfig cfg = cfgs.get(assignedIdx);
                    msg.append("  (ID ").append(cfg.getId()).append(", walk ")
                       .append(cfg.getWalkMinutes()).append(":")
                       .append(cfg.getWalkSecondsPart() < 10 ? "0" : "")
                       .append(cfg.getWalkSecondsPart()).append(")");
                }
            } catch (Exception ignored) { }
        }

        // Missed
        try {
            msg.append("\nMissed: ").append(p.isMissed());
        } catch (Throwable ignored) {}

        JOptionPane.showMessageDialog(
                this,
                msg.toString(),
                "Passenger Details",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    private static int resolveAssignedHoldRoomIndex(Passenger p) {
        if (p == null) return -1;
        try {
            Method m = p.getClass().getMethod("getAssignedHoldRoomIndex");
            Object v = m.invoke(p);
            if (v instanceof Integer) return (Integer) v;
        } catch (Exception ignored) { }
        return -1;
    }

    // -------- helpers --------

    private boolean hasSnapshotForCurrentInterval() {
        try {
            List<List<List<Passenger>>> hist = engine.getHistoryHoldRooms();
            if (hist == null) return false;
            int step = engine.getCurrentInterval() - 1;
            return step >= 0 && step < hist.size();
        } catch (Throwable t) {
            return false;
        }
    }

    private void drawNoDataMessage(Graphics g, String msg) {
        g.setColor(Color.DARK_GRAY);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(msg);
        int x = Math.max(10, (getWidth() - tw) / 2);
        int y = Math.max(20, getHeight() / 2);
        g.drawString(msg, x, y);
    }
}
