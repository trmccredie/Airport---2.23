package sim.ui;

import sim.model.Flight;
import sim.model.Passenger;
import sim.service.SimulationEngine;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel displaying ticket lines with scrollable grids.
 * Pulls data strictly from the engine's snapshot history for the current interval.
 */
public class TicketLinesPanel extends JPanel {
    private final SimulationEngine engine;
    private final int[] queuedOffsets;
    private final int[] servedOffsets;

    // click hit-testing
    private final List<Rectangle> clickableAreas;
    private final List<Passenger> clickablePassengers;
    private final List<Rectangle> counterAreas;

    private final Flight filterFlight;

    public TicketLinesPanel(SimulationEngine engine,
                            List<Rectangle> clickableAreas,
                            List<Passenger> clickablePassengers,
                            Flight filterFlight) {
        this.engine = engine;
        this.clickableAreas = clickableAreas;
        this.clickablePassengers = clickablePassengers;
        this.counterAreas = new ArrayList<>();
        this.filterFlight = filterFlight;

        int lineCount = 0;
        try { lineCount = Math.max(0, engine.getTicketLines().size()); } catch (Throwable ignored) {}
        this.queuedOffsets = new int[lineCount];
        this.servedOffsets = new int[lineCount];

        setFocusable(true);

        // shared scroll handler (no mutation of engine; just offsets)
        ScrollMouseHandler handler = new ScrollMouseHandler.TicketScrollHandler(
                engine, this.clickableAreas, this.clickablePassengers,
                this.queuedOffsets, this.servedOffsets,
                this.filterFlight,
                this.counterAreas
        );
        addMouseListener(handler);
        addMouseMotionListener(handler);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (!hasSnapshotForCurrentInterval()) {
            drawNoDataMessage(g, "Compute at least one interval to view ticket lines.");
            return;
        }

        GridRenderer.renderTicketLines(
                this,
                g,
                engine,
                queuedOffsets,
                servedOffsets,
                clickableAreas,
                clickablePassengers,
                counterAreas,
                filterFlight
        );
    }

    /**
     * @return the maximum queued size that line #lineIdx ever reached across all history intervals.
     */
    public int getMaxQueuedForLine(int lineIdx) {
        int max = 0;
        List<List<List<Passenger>>> history;
        try {
            history = engine.getHistoryQueuedTicket();
        } catch (Throwable t) {
            return 0;
        }
        if (history == null) return 0;

        for (List<List<Passenger>> interval : history) {
            if (interval == null || lineIdx < 0 || lineIdx >= interval.size()) continue;
            int sz = interval.get(lineIdx) == null ? 0 : interval.get(lineIdx).size();
            if (sz > max) max = sz;
        }
        return max;
    }

    /**
     * Ensure a minimum vertical spacing per line; enables vertical scrolling when there are many lines.
     */
    @Override
    public Dimension getPreferredSize() {
        int width = Math.max(200, super.getPreferredSize().width);
        int lines = 0;
        try { lines = Math.max(0, engine.getTicketLines().size()); } catch (Throwable ignored) {}
        int height = 50 + lines * GridRenderer.MIN_LINE_SPACING + 50;
        return new Dimension(width, height);
    }

    // -------- helpers --------

    private boolean hasSnapshotForCurrentInterval() {
        try {
            List<List<List<Passenger>>> hist = engine.getHistoryQueuedTicket();
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
