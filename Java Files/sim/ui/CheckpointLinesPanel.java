package sim.ui;

import sim.model.Flight;
import sim.model.Passenger;
import sim.service.SimulationEngine;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel displaying checkpoint lines with scrollable grids.
 * Reads from engine snapshots only (current interval).
 */
public class CheckpointLinesPanel extends JPanel {
    private final SimulationEngine engine;
    private final int[] checkpointQueuedOffsets;
    private final int[] checkpointServedOffsets;

    private final List<Rectangle> clickableAreas;
    private final List<Passenger> clickablePassengers;
    private final List<Rectangle> counterAreas;

    private final Flight filterFlight;

    public CheckpointLinesPanel(SimulationEngine engine,
                                List<Rectangle> clickableAreas,
                                List<Passenger> clickablePassengers,
                                Flight filterFlight) {
        this.engine = engine;
        this.clickableAreas = clickableAreas;
        this.clickablePassengers = clickablePassengers;
        this.counterAreas = new ArrayList<>();
        this.filterFlight = filterFlight;

        int lineCount = 0;
        try { lineCount = Math.max(0, engine.getCheckpointLines().size()); } catch (Throwable ignored) {}
        this.checkpointQueuedOffsets = new int[lineCount];
        this.checkpointServedOffsets = new int[lineCount];

        setFocusable(true);

        ScrollMouseHandler handler = new ScrollMouseHandler.CheckpointScrollHandler(
                engine, this.clickableAreas, this.clickablePassengers,
                this.checkpointQueuedOffsets, this.checkpointServedOffsets,
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
            drawNoDataMessage(g, "Compute at least one interval to view checkpoint lines.");
            return;
        }

        GridRenderer.renderCheckpointLines(
                this,
                g,
                engine,
                checkpointQueuedOffsets,
                checkpointServedOffsets,
                clickableAreas,
                clickablePassengers,
                counterAreas,
                filterFlight
        );
    }

    /**
     * @return the maximum queued size that checkpoint line #lineIdx ever reached across all intervals.
     */
    public int getMaxQueuedForLine(int lineIdx) {
        int max = 0;
        List<List<List<Passenger>>> history;
        try {
            history = engine.getHistoryQueuedCheckpoint();
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

    @Override
    public Dimension getPreferredSize() {
        int width = Math.max(200, super.getPreferredSize().width);
        int lines = 0;
        try { lines = Math.max(0, engine.getCheckpointLines().size()); } catch (Throwable ignored) {}
        int height = 50 + lines * GridRenderer.MIN_LINE_SPACING + 50;
        return new Dimension(width, height);
    }

    // -------- helpers --------

    private boolean hasSnapshotForCurrentInterval() {
        try {
            List<List<List<Passenger>>> hist = engine.getHistoryQueuedCheckpoint();
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
