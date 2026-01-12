package sim.floorplan.sim;

/**
 * Floorplan-only passenger footprint settings.
 * Units:
 *  - lengths/widths are in feet (UI-facing)
 *  - showFootprints is visual-only (spacing still enforced when false)
 */
public class PassengerFootprintConfig {
    private double lineLengthFt = 2.0;
    private double lineWidthFt  = 2.0;

    private double holdLengthFt = 2.0;
    private double holdWidthFt  = 2.0;

    private boolean showFootprints = false;

    public double getLineLengthFt() { return lineLengthFt; }
    public double getLineWidthFt()  { return lineWidthFt; }

    public double getHoldLengthFt() { return holdLengthFt; }
    public double getHoldWidthFt()  { return holdWidthFt; }

    public boolean isShowFootprints() { return showFootprints; }

    public void setLineLengthFt(double v) { lineLengthFt = clampFt(v); }
    public void setLineWidthFt(double v)  { lineWidthFt  = clampFt(v); }

    public void setHoldLengthFt(double v) { holdLengthFt = clampFt(v); }
    public void setHoldWidthFt(double v)  { holdWidthFt  = clampFt(v); }

    public void setShowFootprints(boolean show) { showFootprints = show; }

    private static double clampFt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 2.0;
        // reasonable bounds to prevent zero/negative or absurd values
        return Math.max(0.25, Math.min(30.0, v));
    }
}
