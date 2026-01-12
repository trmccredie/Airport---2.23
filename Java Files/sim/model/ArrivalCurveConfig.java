package sim.model;

import java.io.Serializable;

/**
 * Arrival curve configuration used by the engine + UI editor.
 *
 * Minutes are expressed as "minutes before departure".
 * Typical domain for the editor is 240 -> 20.
 */
public class ArrivalCurveConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int DEFAULT_WINDOW_START   = 120;
    public static final int DEFAULT_BOARDING_CLOSE = 20;
    public static final int MAX_WINDOW_START       = 240;

    private boolean legacyMode = true;

    private int peakMinutesBeforeDeparture;
    private int leftSigmaMinutes;
    private int rightSigmaMinutes;

    private boolean lateClampEnabled;
    private int lateClampMinutesBeforeDeparture;

    private int windowStartMinutesBeforeDeparture = DEFAULT_WINDOW_START;
    private int boardingCloseMinutesBeforeDeparture = DEFAULT_BOARDING_CLOSE;

    public static ArrivalCurveConfig legacyDefault() {
        ArrivalCurveConfig c = new ArrivalCurveConfig();
        c.legacyMode = true;
        c.windowStartMinutesBeforeDeparture = DEFAULT_WINDOW_START;
        c.boardingCloseMinutesBeforeDeparture = DEFAULT_BOARDING_CLOSE;

        // “Legacy-ish” reasonable defaults for editor display
        c.peakMinutesBeforeDeparture = 70;
        c.leftSigmaMinutes = 18;
        c.rightSigmaMinutes = 14;

        c.lateClampEnabled = false;
        c.lateClampMinutesBeforeDeparture = 30;

        c.validateAndClamp();
        return c;
    }

    public void markEdited() {
        this.legacyMode = false;
    }

    public void validateAndClamp() {
        if (boardingCloseMinutesBeforeDeparture <= 0) {
            boardingCloseMinutesBeforeDeparture = DEFAULT_BOARDING_CLOSE;
        }
        boardingCloseMinutesBeforeDeparture = clamp(boardingCloseMinutesBeforeDeparture, 0, MAX_WINDOW_START);

        if (windowStartMinutesBeforeDeparture <= 0) {
            windowStartMinutesBeforeDeparture = DEFAULT_WINDOW_START;
        }
        windowStartMinutesBeforeDeparture = clamp(windowStartMinutesBeforeDeparture, DEFAULT_WINDOW_START, MAX_WINDOW_START);

        // Peak defaults if not set
        if (peakMinutesBeforeDeparture <= 0) peakMinutesBeforeDeparture = 70;

        // Peak must be inside [close..windowStart]
        peakMinutesBeforeDeparture = clamp(peakMinutesBeforeDeparture,
                boardingCloseMinutesBeforeDeparture,
                windowStartMinutesBeforeDeparture);

        // Sigmas must be >= 1
        if (leftSigmaMinutes <= 0) leftSigmaMinutes = 18;
        if (rightSigmaMinutes <= 0) rightSigmaMinutes = 14;
        leftSigmaMinutes = Math.max(1, leftSigmaMinutes);
        rightSigmaMinutes = Math.max(1, rightSigmaMinutes);

        // Clamp minutes defaults
        if (lateClampMinutesBeforeDeparture <= 0) lateClampMinutesBeforeDeparture = 30;

        // If clamp enabled, keep it within [close..windowStart]
        lateClampMinutesBeforeDeparture = clamp(lateClampMinutesBeforeDeparture,
                boardingCloseMinutesBeforeDeparture,
                windowStartMinutesBeforeDeparture);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ===== getters/setters =====

    public boolean isLegacyMode() { return legacyMode; }
    public void setLegacyMode(boolean legacyMode) { this.legacyMode = legacyMode; }

    public int getPeakMinutesBeforeDeparture() { return peakMinutesBeforeDeparture; }
    public void setPeakMinutesBeforeDeparture(int v) { this.peakMinutesBeforeDeparture = v; }

    public int getLeftSigmaMinutes() { return leftSigmaMinutes; }
    public void setLeftSigmaMinutes(int v) { this.leftSigmaMinutes = v; }

    public int getRightSigmaMinutes() { return rightSigmaMinutes; }
    public void setRightSigmaMinutes(int v) { this.rightSigmaMinutes = v; }

    public boolean isLateClampEnabled() { return lateClampEnabled; }
    public void setLateClampEnabled(boolean v) { this.lateClampEnabled = v; }

    public int getLateClampMinutesBeforeDeparture() { return lateClampMinutesBeforeDeparture; }
    public void setLateClampMinutesBeforeDeparture(int v) { this.lateClampMinutesBeforeDeparture = v; }

    public int getWindowStartMinutesBeforeDeparture() { return windowStartMinutesBeforeDeparture; }
    public void setWindowStartMinutesBeforeDeparture(int v) { this.windowStartMinutesBeforeDeparture = v; }

    public int getBoardingCloseMinutesBeforeDeparture() { return boardingCloseMinutesBeforeDeparture; }
    public void setBoardingCloseMinutesBeforeDeparture(int v) { this.boardingCloseMinutesBeforeDeparture = v; }
}
