package sim.ui;


import sim.model.Flight;
import sim.model.Passenger;
import sim.service.SimulationEngine;


import javax.swing.JComponent;
import javax.swing.JViewport;
import java.awt.*;
import java.util.List;
import java.util.stream.Collectors;


public class GridRenderer {
   private static final int ROWS = 3;
   public static final int COLS = 15;
   public static final int MIN_LINE_SPACING = 80;  // minimum pixels between lines


   // --- hold-room specific constants ---
   public static final int HOLD_BOX_SIZE   = 60 * 3;  // 3× the ticket‐counter box
   public static final int HOLD_ROWS       = 9;       // legacy (no longer used for sizing)
   public static final int MIN_CELL_SIZE   = 3;       // never shrink below 3px
   public static final int HOLD_GAP        = 10;      // horizontal gap between hold rooms


   /**
    * Draws both the queued and served ticket‐counter grids,
    * including scrollbars and click‐to‐inspect hit rectangles.
    */
   public static void renderTicketLines(JComponent panel,
                                        Graphics g,
                                        SimulationEngine engine,
                                        int[] queuedOffsets,
                                        int[] servedOffsets,
                                        List<Rectangle> clickableAreas,
                                        List<Passenger> clickablePassengers,
                                        List<Rectangle> counterAreas,
                                        Flight filterFlight) {
       clickableAreas.clear();
       clickablePassengers.clear();
       counterAreas.clear();


       int w = panel.getWidth();
       int h = panel.getHeight();


       int leftX = w / 2;
       int top = 50, bottom = h - 50;
       int boxSize = 60;
       int cellW = boxSize / ROWS;
       int gridHeight = ROWS * cellW;
       int gridWidth = COLS * cellW;
       int trackH = cellW / 2;


       int step = engine.getCurrentInterval() - 1;
       if (step < 0) return;


       int lines = engine.getTicketLines().size();
       int rawSpace = lines > 1 ? (bottom - top) / (lines - 1) : 0;
       int space = Math.max(rawSpace, MIN_LINE_SPACING);


       for (int i = 0; i < lines; i++) {
           int centerY = space > 0 ? top + i * space : h / 2;
           int boxX = leftX - boxSize / 2, boxY = centerY - boxSize / 2;


           g.setColor(Color.BLACK);
           g.drawRect(boxX, boxY, boxSize, boxSize);
           counterAreas.add(new Rectangle(boxX, boxY, boxSize, boxSize));
           String label = String.valueOf(engine.getCounterConfigs().get(i).getId());
           FontMetrics fm = g.getFontMetrics();
           int tw = fm.stringWidth(label), th = fm.getAscent();
           int tx = boxX + (boxSize - tw) / 2, ty = boxY + (boxSize + th) / 2;
           g.drawString(label, tx, ty);


           g.setColor(Color.YELLOW);
           List<Passenger> fullQ = engine.getHistoryQueuedTicket().get(step).get(i);
           List<Passenger> queued = filterFlight == null
                   ? fullQ
                   : fullQ.stream().filter(p -> p.getFlight() == filterFlight).collect(Collectors.toList());
           int startXq = boxX - cellW;


           int fullColsQ = (queued.size() + ROWS - 1) / ROWS;
           queuedOffsets[i] = Math.max(0, Math.min(queuedOffsets[i], Math.max(0, fullColsQ - COLS)));
           drawGridPartial(g, queued, startXq, boxY + (boxSize - gridHeight) / 2, cellW, cellW, ROWS,
                   queuedOffsets[i], clickableAreas, clickablePassengers);


           if (fullColsQ > COLS) {
               int trackXq = startXq - (COLS - 1) * cellW;
               int trackYq = boxY + (boxSize - gridHeight) / 2 + gridHeight + 2;
               int trackWq = COLS * cellW;
               g.setColor(Color.LIGHT_GRAY);
               g.fillRect(trackXq, trackYq, trackWq, trackH);
               int knobWq = (int) ((COLS / (double) fullColsQ) * trackWq);
               int knobXq = trackXq + (int) (queuedOffsets[i] / (double) (fullColsQ - COLS) * (trackWq - knobWq));
               g.setColor(Color.DARK_GRAY);
               g.fillRect(knobXq, trackYq, knobWq, trackH);
           }


           g.setColor(Color.GREEN);
           List<Passenger> fullS;
           if (filterFlight == null) {
               fullS = engine.getVisibleCompletedTicketLine(i);
           } else {
               int delay = engine.getTransitDelayMinutes();
               fullS = engine.getHistoryServedTicket().get(step).get(i).stream()
                       .filter(p -> p.getFlight() == filterFlight)
                       .filter(p -> p.getTicketCompletionMinute() + delay > step)
                       .collect(Collectors.toList());
           }
           int startXs = boxX + boxSize + (COLS - 1) * cellW;


           int fullColsS = (fullS.size() + ROWS - 1) / ROWS;
           servedOffsets[i] = Math.max(0, Math.min(servedOffsets[i], Math.max(0, fullColsS - COLS)));
           drawGridPartial(g, fullS, startXs, boxY + (boxSize - gridHeight) / 2, cellW, cellW, ROWS,
                   servedOffsets[i], clickableAreas, clickablePassengers);


           if (fullColsS > COLS) {
               int trackXs = startXs - gridWidth + cellW;
               int trackYs = boxY + (boxSize - gridHeight) / 2 + gridHeight + 2;
               int trackWs = COLS * cellW;
               g.setColor(Color.LIGHT_GRAY);
               g.fillRect(trackXs, trackYs, trackWs, trackH);
               int knobWs = (int) ((COLS / (double) fullColsS) * trackWs);
               int knobXs = trackXs + (int) (servedOffsets[i] / (double) (fullColsS - COLS) * (trackWs - knobWs));
               g.setColor(Color.DARK_GRAY);
               g.fillRect(knobXs, trackYs, knobWs, trackH);
           }
       }
   }


   /**
    * Draws both the queued and served checkpoint‐grid, same pattern
    * but right‐aligned.
    */
   public static void renderCheckpointLines(JComponent panel,
                                            Graphics g,
                                            SimulationEngine engine,
                                            int[] queuedOffsets,
                                            int[] servedOffsets,
                                            List<Rectangle> clickableAreas,
                                            List<Passenger> clickablePassengers,
                                            List<Rectangle> counterAreas,
                                            Flight filterFlight) {
       clickableAreas.clear();
       clickablePassengers.clear();
       counterAreas.clear();


       int w = panel.getWidth();
       int h = panel.getHeight();


       int rightX = w / 2;
       int top = 50, bottom = h - 50;
       int boxSize = 60;
       int cellW = boxSize / ROWS;
       int gridH = ROWS * cellW;
       int gridW = COLS * cellW;
       int trackH = cellW / 2;


       int step = engine.getCurrentInterval() - 1;
       if (step < 0) return;


       int lines = engine.getCheckpointLines().size();
       int rawSpace = lines > 1 ? (bottom - top) / (lines - 1) : 0;
       int space = Math.max(rawSpace, MIN_LINE_SPACING);


       for (int i = 0; i < lines; i++) {
           int centerY = space > 0 ? top + i * space : h / 2;
           int boxX = rightX - boxSize / 2;
           int boxY = centerY - boxSize / 2;


           g.setColor(Color.BLACK);
           g.drawRect(boxX, boxY, boxSize, boxSize);
           counterAreas.add(new Rectangle(boxX, boxY, boxSize, boxSize));


           String lbl = String.valueOf(i + 1);
           FontMetrics fm2 = g.getFontMetrics();
           int w2 = fm2.stringWidth(lbl), h2 = fm2.getAscent();
           int x2 = boxX + (boxSize - w2) / 2, y2 = boxY + (boxSize + h2) / 2;
           g.drawString(lbl, x2, y2);


           g.setColor(Color.YELLOW);
           List<Passenger> fullQC = engine.getHistoryQueuedCheckpoint().get(step).get(i);
           List<Passenger> queuedC = filterFlight == null
                   ? fullQC
                   : fullQC.stream().filter(p -> p.getFlight() == filterFlight).collect(Collectors.toList());
           int startXc = boxX - cellW;
           drawGridPartial(g, queuedC, startXc,
                   boxY + (boxSize - gridH) / 2,
                   cellW, cellW, ROWS,
                   queuedOffsets[i],
                   clickableAreas, clickablePassengers);


           int fullColsQC = (queuedC.size() + ROWS - 1) / ROWS;
           if (fullColsQC > COLS) {
               int trackXqc = startXc - (COLS - 1) * cellW;
               int trackYqc = boxY + (boxSize - gridH) / 2 + gridH + 2;
               int trackWqc = COLS * cellW;
               g.setColor(Color.LIGHT_GRAY);
               g.fillRect(trackXqc, trackYqc, trackWqc, trackH);
               int knobWqc = (int) ((COLS / (double) fullColsQC) * trackWqc);
               int knobXqc = trackXqc +
                       (int) (queuedOffsets[i] / (double) (fullColsQC - COLS) * (trackWqc - knobWqc));
               g.setColor(Color.DARK_GRAY);
               g.fillRect(knobXqc, trackYqc, knobWqc, trackH);
           }


           g.setColor(Color.GREEN);
           List<Passenger> fullSC = engine.getHistoryServedCheckpoint().get(step).get(i);
           List<Passenger> servedC = filterFlight == null
                   ? fullSC
                   : fullSC.stream().filter(p -> p.getFlight() == filterFlight).collect(Collectors.toList());
           int startXsc = boxX + boxSize + (COLS - 1) * cellW;
           drawGridPartial(g, servedC, startXsc,
                   boxY + (boxSize - gridH) / 2,
                   cellW, cellW, ROWS,
                   servedOffsets[i],
                   clickableAreas, clickablePassengers);


           int fullColsSC = (servedC.size() + ROWS - 1) / ROWS;
           if (fullColsSC > COLS) {
               int trackXsc = startXsc - gridW + cellW;
               int trackYsc = boxY + (boxSize - gridH) / 2 + gridH + 2;
               int trackWsc = COLS * cellW;
               g.setColor(Color.LIGHT_GRAY);
               g.fillRect(trackXsc, trackYsc, trackWsc, trackH);
               int knobWsc = (int) ((COLS / (double) fullColsSC) * trackWsc);
               int knobXsc = trackXsc +
                       (int) (servedOffsets[i] / (double) (fullColsSC - COLS) * (trackWsc - knobWsc));
               g.setColor(Color.DARK_GRAY);
               g.fillRect(knobXsc, trackYsc, knobWsc, trackH);
           }
       }
   }


   // shared helper
   private static void drawGridPartial(Graphics g,
                                       List<Passenger> list,
                                       int startX,
                                       int startY,
                                       int cellW,
                                       int cellH,
                                       int rows,
                                       int offset,
                                       List<Rectangle> clickableAreas,
                                       List<Passenger> clickablePassengers) {
       int size     = list.size();
       int fullCols = (size + rows - 1) / rows;
       int total    = Math.min(size, fullCols * rows);


       for (int idx = 0; idx < total; idx++) {
           int row = idx % rows;
           int col = idx / rows;
           int rel = col - offset;
           if (rel < 0 || rel >= COLS) continue;


           int x = startX - rel * cellW;
           int y = startY + row * cellH;


           Passenger p = list.get(idx);
           Color darkerOrange = new Color(200, 100, 0);
           boolean completedCkpt = p.getCheckpointCompletionMinute() >= 0;
           Color borderColor = (p.isMissed() && !completedCkpt)
                   ? Color.RED
                   : (p.isInPerson() ? darkerOrange : Color.BLUE);


           ShapePainter.paintShape(
                   g,
                   p.getFlight().getShape(),
                   x, y, cellW, cellH,
                   borderColor
           );


           clickableAreas.add(new Rectangle(x, y, cellW, cellH));
           clickablePassengers.add(p);
       }
   }


   /**
    * Draws the hold-room boxes, one per PHYSICAL room.
    * Rooms may contain multiple flights.
    */
   public static void renderHoldRooms(JComponent panel,
                                      Graphics g,
                                      SimulationEngine engine,
                                      List<Rectangle> clickableAreas,
                                      List<Passenger> clickablePassengers,
                                      Flight filterFlight) {
       clickableAreas.clear();
       clickablePassengers.clear();


       int step = engine.getCurrentInterval() - 1;
       if (step < 0) return;
       if (engine.getHistoryHoldRooms() == null || step >= engine.getHistoryHoldRooms().size()) return;


       List<List<Passenger>> snapshot = engine.getHistoryHoldRooms().get(step);
       List<HoldRoomConfig> configs = engine.getHoldRoomConfigs();


       int roomCount = Math.min(snapshot.size(), configs.size());
       if (roomCount <= 0) return;


       // layout: wrap into columns based on visible height
       int availableHeight;
       Container parent = panel.getParent();
       if (parent instanceof JViewport) {
           availableHeight = ((JViewport) parent).getExtentSize().height - 2 * HOLD_GAP;
       } else {
           availableHeight = panel.getHeight() - 2 * HOLD_GAP;
       }


       int labelH = 16;
       int rowHeight = HOLD_BOX_SIZE + labelH + 14; // box + label + breathing room
       int maxRows = Math.max(1, availableHeight / rowHeight);


       for (int i = 0; i < roomCount; i++) {
           int col = i / maxRows;
           int row = i % maxRows;


           int boxX = HOLD_GAP + col * (HOLD_BOX_SIZE + 20);
           int boxY = HOLD_GAP + row * rowHeight;


           HoldRoomConfig cfg = configs.get(i);


           // label
           g.setColor(Color.BLACK);
           String label = formatHoldRoomLabel(cfg);
           g.drawString(label, boxX + 2, boxY + labelH);


           int roomTopY = boxY + labelH + 4;


           // box outline
           g.setColor(Color.BLACK);
           g.drawRect(boxX, roomTopY, HOLD_BOX_SIZE, HOLD_BOX_SIZE);


           List<Passenger> full = snapshot.get(i);
           if (full == null) full = List.of();


           List<Passenger> visible = (filterFlight == null)
                   ? full
                   : full.stream().filter(p -> p.getFlight() == filterFlight).collect(Collectors.toList());


           int n = visible.size();
           if (n <= 0) continue;


           int cellSize = bestCellSizeForBox(n);
           int rowsFit  = Math.max(1, HOLD_BOX_SIZE / cellSize);


           for (int idx = 0; idx < n; idx++) {
               int r = idx % rowsFit;
               int c = idx / rowsFit;


               int x = boxX + c * cellSize;
               int y = roomTopY + r * cellSize;


               // safety: never draw outside the room box
               if (x + cellSize > boxX + HOLD_BOX_SIZE) break;
               if (y + cellSize > roomTopY + HOLD_BOX_SIZE) continue;


               Passenger p = visible.get(idx);


               Color borderColor = p.isMissed()
                       ? Color.RED
                       : (p.isInPerson() ? new Color(200, 100, 0) : Color.BLUE);


               ShapePainter.paintShape(
                       g,
                       p.getFlight().getShape(),
                       x, y, cellSize, cellSize,
                       borderColor
               );


               clickableAreas.add(new Rectangle(x, y, cellSize, cellSize));
               clickablePassengers.add(p);
           }
       }
   }


   private static String formatHoldRoomLabel(HoldRoomConfig cfg) {
       if (cfg == null) return "Hold Room";
       int m = cfg.getWalkMinutes();
       int s = cfg.getWalkSecondsPart();
       String ss = (s < 10 ? "0" : "") + s;
       return "Hold Room " + cfg.getId() + "  (" + m + ":" + ss + ")";
   }


   /**
    * Dynamic sizing so mixed-flight rooms still fit in the box.
    * We maximize cell size while keeping grid within HOLD_BOX_SIZE x HOLD_BOX_SIZE.
    */
   private static int bestCellSizeForBox(int passengerCount) {
       if (passengerCount <= 0) return HOLD_BOX_SIZE;
       int best = MIN_CELL_SIZE;


       int maxRowsPossible = Math.max(1, HOLD_BOX_SIZE / MIN_CELL_SIZE);
       for (int rows = 1; rows <= maxRowsPossible; rows++) {
           int cols = (passengerCount + rows - 1) / rows;
           if (cols <= 0) continue;


           int cellByRows = HOLD_BOX_SIZE / rows;
           int cellByCols = HOLD_BOX_SIZE / cols;
           int cell = Math.min(cellByRows, cellByCols);


           if (cell > best) best = cell;
       }
       return Math.max(MIN_CELL_SIZE, best);
   }
}
