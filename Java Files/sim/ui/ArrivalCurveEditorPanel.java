package sim.ui;


import sim.model.ArrivalCurveConfig;
import sim.service.ArrivalGenerator;
import sim.service.arrivals.EditedSplitGaussianArrivalGenerator;


import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;


/**
* Arrival curve editor panel (preview + drag handles).
*
* X axis is fixed to 240 -> 20 (minutes before departure).
*
* Default preview behavior:
*  - 240->120 shows zeros
*  - 120->20 shows the LEGACY curve (uses sim.service.ArrivalGenerator with arrivalSpan=120)
*
* After first user edit:
*  - config.markEdited()
*  - preview uses EditedSplitGaussianArrivalGenerator over arrivalSpan=240, with windowStart controlling
*    whether earlier minutes (240->120) can get mass.
*/
public class ArrivalCurveEditorPanel extends JPanel {


   // Fixed preview domain
   private static final int PREVIEW_ARRIVAL_SPAN = 240;
   private static final int PREVIEW_CLOSE = 20;


   // Rendering
   private static final int PAD_L = 50;
   private static final int PAD_R = 20;
   private static final int PAD_T = 20;
   private static final int PAD_B = 40;


   private static final int HANDLE_HIT_PX = 10;


   private ArrivalCurveConfig config;


   private enum Handle { NONE, PEAK, LEFT_SIGMA, RIGHT_SIGMA, CLAMP }
   private Handle activeHandle = Handle.NONE;


   private final EditedSplitGaussianArrivalGenerator editedGen = new EditedSplitGaussianArrivalGenerator();


   // Preview passengers (just to scale the curve)
   private static final int PREVIEW_TOTAL_PAX = 1000;


   public ArrivalCurveEditorPanel() {
       this(ArrivalCurveConfig.legacyDefault());
   }


   public ArrivalCurveEditorPanel(ArrivalCurveConfig cfg) {
       this.config = (cfg == null) ? ArrivalCurveConfig.legacyDefault() : cfg;
       ensureSaneDefaults();
       setPreferredSize(new Dimension(800, 260));
       setBackground(Color.WHITE);


       MouseAdapter ma = new MouseAdapter() {
           @Override
           public void mousePressed(MouseEvent e) {
               activeHandle = pickHandle(e.getX(), e.getY());
           }


           @Override
           public void mouseReleased(MouseEvent e) {
               activeHandle = Handle.NONE;
           }


           @Override
           public void mouseDragged(MouseEvent e) {
               if (activeHandle == Handle.NONE) return;


               // First edit flips legacyMode off
               if (config.isLegacyMode()) {
                   config.markEdited();
               }


               int minutes = xToMinutesBeforeDeparture(e.getX());
               applyDrag(activeHandle, minutes);


               // Keep values sensible
               config.validateAndClamp();


               // Auto expand windowStart to allow earlier mass if user pulls left side earlier
               autoExpandWindowStartIfNeeded();


               // Keep values sensible again after auto-expand
               config.validateAndClamp();


               repaint();
           }
       };
       addMouseListener(ma);
       addMouseMotionListener(ma);
   }


   /**
    * Required public API.
    */
   public ArrivalCurveConfig getConfigCopy() {
       ArrivalCurveConfig copy = ArrivalCurveConfig.legacyDefault();


       copy.setLegacyMode(config.isLegacyMode());
       copy.setPeakMinutesBeforeDeparture(config.getPeakMinutesBeforeDeparture());
       copy.setLeftSigmaMinutes(config.getLeftSigmaMinutes());
       copy.setRightSigmaMinutes(config.getRightSigmaMinutes());


       copy.setLateClampEnabled(config.isLateClampEnabled());
       copy.setLateClampMinutesBeforeDeparture(config.getLateClampMinutesBeforeDeparture());


       copy.setWindowStartMinutesBeforeDeparture(config.getWindowStartMinutesBeforeDeparture());
       copy.setBoardingCloseMinutesBeforeDeparture(config.getBoardingCloseMinutesBeforeDeparture());


       copy.validateAndClamp();
       return copy;
   }


   public void setConfig(ArrivalCurveConfig cfg) {
       this.config = (cfg == null) ? ArrivalCurveConfig.legacyDefault() : cfg;
       ensureSaneDefaults();
       repaint();
   }


   private void ensureSaneDefaults() {
       // If fields were never initialized in config, set reasonable edited defaults.
       if (config.getBoardingCloseMinutesBeforeDeparture() <= 0) {
           config.setBoardingCloseMinutesBeforeDeparture(PREVIEW_CLOSE);
       }
       if (config.getWindowStartMinutesBeforeDeparture() <= 0) {
           config.setWindowStartMinutesBeforeDeparture(120);
       }
       if (config.getPeakMinutesBeforeDeparture() <= 0) {
           // Legacy-ish peak (center of 120->20 window) ~70 mins before departure
           config.setPeakMinutesBeforeDeparture(70);
       }
       if (config.getLeftSigmaMinutes() <= 0) config.setLeftSigmaMinutes(18);
       if (config.getRightSigmaMinutes() <= 0) config.setRightSigmaMinutes(14);


       if (config.getLateClampMinutesBeforeDeparture() <= 0) {
           config.setLateClampMinutesBeforeDeparture(30);
       }


       config.validateAndClamp();
   }


   private void autoExpandWindowStartIfNeeded() {
       // Heuristic: ensure windowStart covers about 3 sigmas on the early side
       int peak = config.getPeakMinutesBeforeDeparture();
       int leftSigma = Math.max(1, config.getLeftSigmaMinutes());


       int needed = peak + (3 * leftSigma);
       int current = config.getWindowStartMinutesBeforeDeparture();


       int expanded = clamp(Math.max(current, needed), 120, 240);
       config.setWindowStartMinutesBeforeDeparture(expanded);


       // If clamp is enabled, keep clamp >= boardingClose and <= windowStart
       if (config.isLateClampEnabled()) {
           int close = config.getBoardingCloseMinutesBeforeDeparture();
           int lc = config.getLateClampMinutesBeforeDeparture();
           lc = clamp(lc, close, config.getWindowStartMinutesBeforeDeparture());
           config.setLateClampMinutesBeforeDeparture(lc);
       }


       // Keep peak within [close..windowStart]
       int close = config.getBoardingCloseMinutesBeforeDeparture();
       int window = config.getWindowStartMinutesBeforeDeparture();
       config.setPeakMinutesBeforeDeparture(clamp(config.getPeakMinutesBeforeDeparture(), close, window));
   }


   private void applyDrag(Handle h, int minutesBeforeDeparture) {
       int close = config.getBoardingCloseMinutesBeforeDeparture();
       int window = config.getWindowStartMinutesBeforeDeparture();


       minutesBeforeDeparture = clamp(minutesBeforeDeparture, close, PREVIEW_ARRIVAL_SPAN);


       switch (h) {
           case PEAK: {
               int newPeak = clamp(minutesBeforeDeparture, close, window);
               config.setPeakMinutesBeforeDeparture(newPeak);
               break;
           }
           case LEFT_SIGMA: {
               int peak = config.getPeakMinutesBeforeDeparture();
               int newLeft = Math.max(1, minutesBeforeDeparture - peak); // earlier side => bigger minutes
               config.setLeftSigmaMinutes(newLeft);
               break;
           }
           case RIGHT_SIGMA: {
               int peak = config.getPeakMinutesBeforeDeparture();
               int newRight = Math.max(1, peak - minutesBeforeDeparture); // later side => smaller minutes
               config.setRightSigmaMinutes(newRight);
               break;
           }
           case CLAMP: {
               // Dragging clamp also enables it (nice UX)
               config.setLateClampEnabled(true);
               int newClamp = clamp(minutesBeforeDeparture, close, window);
               config.setLateClampMinutesBeforeDeparture(newClamp);
               break;
           }
           default:
               break;
       }
   }


   private Handle pickHandle(int mx, int my) {
       int w = getWidth();
       int h = getHeight();
       if (w <= 0 || h <= 0) return Handle.NONE;


       // prefer selecting the nearest handle in x
       int peakX = minutesToX(config.getPeakMinutesBeforeDeparture());
       int leftX = minutesToX(config.getPeakMinutesBeforeDeparture() + config.getLeftSigmaMinutes());
       int rightX = minutesToX(config.getPeakMinutesBeforeDeparture() - config.getRightSigmaMinutes());
       int clampX = minutesToX(config.getLateClampMinutesBeforeDeparture());


       // Only count as "hit" if mouse is in plot area height
       int plotTop = PAD_T;
       int plotBot = h - PAD_B;
       if (my < plotTop || my > plotBot) return Handle.NONE;


       int dPeak = Math.abs(mx - peakX);
       int dLeft = Math.abs(mx - leftX);
       int dRight = Math.abs(mx - rightX);
       int dClamp = Math.abs(mx - clampX);


       int best = Math.min(Math.min(dPeak, dLeft), Math.min(dRight, dClamp));
       if (best > HANDLE_HIT_PX) return Handle.NONE;


       if (best == dPeak) return Handle.PEAK;
       if (best == dLeft) return Handle.LEFT_SIGMA;
       if (best == dRight) return Handle.RIGHT_SIGMA;
       return Handle.CLAMP;
   }


   @Override
   protected void paintComponent(Graphics g) {
       super.paintComponent(g);


       int w = getWidth();
       int h = getHeight();
       if (w <= 0 || h <= 0) return;


       Graphics2D g2 = (Graphics2D) g.create();
       g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);


       int plotL = PAD_L;
       int plotR = w - PAD_R;
       int plotT = PAD_T;
       int plotB = h - PAD_B;


       // Axis
       g2.setColor(new Color(220, 220, 220));
       g2.drawRect(plotL, plotT, plotR - plotL, plotB - plotT);


       // Title / status
       g2.setColor(Color.DARK_GRAY);
       String mode = config.isLegacyMode() ? "LEGACY (no edits)" : "EDITED";
       g2.drawString("Arrival Curve Editor (" + mode + ") — drag lines: peak / σ / clamp",
               plotL, 14);


       // Build preview curve
       int[] counts = buildPreviewCounts();


       // Find max for scaling
       int max = 1;
       for (int v : counts) max = Math.max(max, v);


       // Draw curve polyline
       g2.setColor(new Color(40, 120, 200));
       int prevX = -1, prevY = -1;


       for (int i = 0; i < counts.length; i++) {
           // i=0 corresponds to ~240->239 minute (earliest); x axis is 240 (left) to 20 (right)
           double minutesBeforeDeparture = PREVIEW_ARRIVAL_SPAN - (i + 0.5);
           int x = minutesToX((int) Math.round(minutesBeforeDeparture));
           double frac = counts[i] / (double) max;
           int y = plotB - (int) Math.round(frac * (plotB - plotT));


           if (prevX >= 0) g2.drawLine(prevX, prevY, x, y);
           prevX = x;
           prevY = y;
       }


       // Draw default guide: windowStart and boarding close
       g2.setColor(new Color(140, 140, 140));
       int windowX = minutesToX(config.getWindowStartMinutesBeforeDeparture());
       g2.drawLine(windowX, plotT, windowX, plotB);
       g2.drawString("windowStart=" + config.getWindowStartMinutesBeforeDeparture(), windowX + 4, plotT + 14);


       int closeX = minutesToX(config.getBoardingCloseMinutesBeforeDeparture());
       g2.drawLine(closeX, plotT, closeX, plotB);
       g2.drawString("close=" + config.getBoardingCloseMinutesBeforeDeparture(), closeX + 4, plotT + 28);


       // Handles
       drawHandleLine(g2, plotT, plotB, config.getPeakMinutesBeforeDeparture(), "peak=" + config.getPeakMinutesBeforeDeparture(), new Color(220, 80, 80));
       drawHandleLine(g2, plotT, plotB, config.getPeakMinutesBeforeDeparture() + config.getLeftSigmaMinutes(), "Lσ=" + config.getLeftSigmaMinutes(), new Color(80, 170, 90));
       drawHandleLine(g2, plotT, plotB, config.getPeakMinutesBeforeDeparture() - config.getRightSigmaMinutes(), "Rσ=" + config.getRightSigmaMinutes(), new Color(80, 170, 90));


       // Clamp handle (only “active” if enabled, but always drawn so user can drag it on)
       Color clampColor = config.isLateClampEnabled() ? new Color(160, 80, 200) : new Color(190, 190, 190);
       drawHandleLine(g2, plotT, plotB, config.getLateClampMinutesBeforeDeparture(),
               (config.isLateClampEnabled() ? "clamp=" : "clamp(off) ") + config.getLateClampMinutesBeforeDeparture(),
               clampColor);


       // X axis labels (few ticks)
       g2.setColor(Color.DARK_GRAY);
       drawXTicks(g2, plotL, plotR, plotB);


       // Bottom config summary
       g2.setColor(Color.DARK_GRAY);
       g2.drawString(summaryText(), plotL, h - 10);


       g2.dispose();
   }


   private String summaryText() {
       return "peak=" + config.getPeakMinutesBeforeDeparture()
               + "  Lσ=" + config.getLeftSigmaMinutes()
               + "  Rσ=" + config.getRightSigmaMinutes()
               + "  windowStart=" + config.getWindowStartMinutesBeforeDeparture()
               + "  close=" + config.getBoardingCloseMinutesBeforeDeparture()
               + "  clamp=" + (config.isLateClampEnabled() ? config.getLateClampMinutesBeforeDeparture() : "OFF");
   }


   private void drawHandleLine(Graphics2D g2, int plotT, int plotB, int minutesBeforeDeparture, String label, Color c) {
       int x = minutesToX(minutesBeforeDeparture);
       g2.setColor(c);
       g2.drawLine(x, plotT, x, plotB);
       g2.fillOval(x - 3, plotT - 3, 6, 6);
       g2.drawString(label, x + 4, plotT + 42);
   }


   private void drawXTicks(Graphics2D g2, int plotL, int plotR, int plotB) {
       int[] ticks = new int[]{240, 210, 180, 150, 120, 90, 60, 30, 20};
       for (int t : ticks) {
           int x = minutesToX(t);
           g2.drawLine(x, plotB, x, plotB + 6);
           g2.drawString(String.valueOf(t), x - 8, plotB + 20);
       }
       g2.drawString("minutes before departure (240 → 20)", plotL, plotB + 32);
   }


   /**
    * Preview curve:
    * - If legacyMode: show zeros for 240->120 then legacy curve for 120->20
    * - If edited: show edited curve over 240->20, with windowStart controlling early zeros
    */
   private int[] buildPreviewCounts() {
       int totalMinutes = PREVIEW_ARRIVAL_SPAN - PREVIEW_CLOSE; // 220
       int[] out = new int[totalMinutes];


       if (config.isLegacyMode()) {
           // Legacy generator is defined for arrivalSpan=120, close=20 => length 100
           ArrivalGenerator legacy = new ArrivalGenerator(120, 1);
           // We need a Flight to call it, but the shape is independent of flight in your code
           // (it uses seats/fillPercent). We'll fake pax by creating a temporary Flight is not possible here.
           // So instead we compute the legacy shape by calling edited preview with config still legacy?
           //
           // BETTER: use the exact legacy math as probabilities (rebuild with totalPassengers=PREVIEW_TOTAL_PAX)
           // by reusing the same ArrivalGenerator distribution indirectly:
           //
           // Because ArrivalGenerator needs a Flight to compute passenger count,
           // we approximate by constructing a "counts shape" from its known PDF formula (same as your file).
           int[] legacyCounts = legacyShapeCounts(PREVIEW_TOTAL_PAX);


           // Place legacy 100-minute curve into the last 100 bins (i from 120..219)
           int offset = PREVIEW_ARRIVAL_SPAN - 120; // 120 minutes of zeros
           for (int i = 0; i < legacyCounts.length && (i + offset) < out.length; i++) {
               out[i + offset] = legacyCounts[i];
           }
           return out;
       }


       // Edited mode: use the edited generator directly (windowStart will decide early zeros)
       int[] edited = editedGen.buildArrivalsPerMinute(null, PREVIEW_TOTAL_PAX, config, PREVIEW_ARRIVAL_SPAN);
       if (edited == null) return out;


       // edited length should be 220; if not, copy what fits.
       int n = Math.min(out.length, edited.length);
       System.arraycopy(edited, 0, out, 0, n);
       return out;
   }


   /**
    * Rebuild the same legacy curve shape your ArrivalGenerator produces for (arrivalSpan=120, close=20),
    * but without needing a Flight instance.
    *
    * This mirrors your ArrivalGenerator constructor math:
    * totalMinutes = 120 - 20 = 100
    * mean = totalMinutes/2
    * sigma = totalMinutes/6
    * probability[m] proportional to exp(-0.5 * x^2), x=(m+0.5-mean)/sigma
    */
   private int[] legacyShapeCounts(int totalPassengers) {
       int totalMinutes = 120 - 20; // 100
       double[] prob = new double[totalMinutes];


       double mean = totalMinutes / 2.0;
       double sigma = totalMinutes / 6.0;


       double sum = 0.0;
       for (int m = 0; m < totalMinutes; m++) {
           double x = (m + 0.5 - mean) / sigma;
           double pdf = Math.exp(-0.5 * x * x);
           prob[m] = pdf;
           sum += pdf;
       }
       for (int m = 0; m < totalMinutes; m++) prob[m] /= sum;


       int[] out = new int[totalMinutes];
       double[] raw = new double[totalMinutes];
       int floorSum = 0;


       for (int m = 0; m < totalMinutes; m++) {
           raw[m] = prob[m] * totalPassengers;
           out[m] = (int) Math.floor(raw[m]);
           floorSum += out[m];
       }


       int remainder = totalPassengers - floorSum;
       while (remainder > 0) {
           int bestIdx = 0;
           double bestFrac = -1.0;
           for (int i = 0; i < totalMinutes; i++) {
               double frac = raw[i] - out[i];
               if (frac > bestFrac) {
                   bestFrac = frac;
                   bestIdx = i;
               }
           }
           out[bestIdx]++;
           remainder--;
       }


       return out;
   }


   // Coordinate transforms


   private int minutesToX(int minutesBeforeDeparture) {
       minutesBeforeDeparture = clamp(minutesBeforeDeparture, PREVIEW_CLOSE, PREVIEW_ARRIVAL_SPAN);


       int plotL = PAD_L;
       int plotR = getWidth() - PAD_R;


       double range = (PREVIEW_ARRIVAL_SPAN - PREVIEW_CLOSE);
       double t = (PREVIEW_ARRIVAL_SPAN - minutesBeforeDeparture) / range; // 240->0.0, 20->1.0
       return plotL + (int) Math.round(t * (plotR - plotL));
   }


   private int xToMinutesBeforeDeparture(int x) {
       int plotL = PAD_L;
       int plotR = getWidth() - PAD_R;


       if (plotR <= plotL) return 120;


       x = clamp(x, plotL, plotR);
       double t = (x - plotL) / (double) (plotR - plotL);
       double minutes = PREVIEW_ARRIVAL_SPAN - t * (PREVIEW_ARRIVAL_SPAN - PREVIEW_CLOSE);
       return (int) Math.round(minutes);
   }


   private static int clamp(int v, int lo, int hi) {
       return Math.max(lo, Math.min(hi, v));
   }
}
