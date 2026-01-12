package sim.ui;


import sim.model.Flight;
import sim.model.Passenger;
import sim.service.SimulationEngine;


import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;


/**
* Handles mouse interactions for scrolling queued and served passenger grids,
* and clicking on counters/checkpoints.
*/
public abstract class ScrollMouseHandler extends MouseAdapter {
   protected static final int ROWS = 3;
   protected static final int COLS = 15;


   protected final SimulationEngine engine;
   protected final List<Rectangle> clickableAreas;
   protected final List<Passenger> clickablePassengers;
   protected final int[] queuedOffsets;
   protected final int[] servedOffsets;
   protected final Flight filterFlight;
   protected final List<Rectangle> counterAreas;  // ← new field


   // drag state
   protected boolean dragging = false;
   protected boolean draggingQueued;
   protected int dragLine = -1;
   protected int initialMouseX;
   protected int initialOffset;


   public ScrollMouseHandler(SimulationEngine engine,
                             List<Rectangle> clickableAreas,
                             List<Passenger> clickablePassengers,
                             int[] queuedOffsets,
                             int[] servedOffsets,
                             Flight filterFlight,
                             List<Rectangle> counterAreas) {  // ← added param
       this.engine = engine;
       this.clickableAreas = clickableAreas;
       this.clickablePassengers = clickablePassengers;
       this.queuedOffsets = queuedOffsets;
       this.servedOffsets = servedOffsets;
       this.filterFlight = filterFlight;
       this.counterAreas = counterAreas;  // ← assign
   }


   @Override public void mousePressed(MouseEvent e)  { handlePress(e); }
   @Override public void mouseReleased(MouseEvent e) { dragging = false; }
   @Override public void mouseDragged(MouseEvent e)  { handleDrag(e); }
   @Override public void mouseClicked(MouseEvent e)  { handleClick(e); }


   protected abstract void handlePress(MouseEvent e);
   protected abstract void handleDrag(MouseEvent e);


   /**
    * Click on a passenger “dot” shows its info.
    */
   protected void handleClick(MouseEvent e) {
       Point pt = e.getPoint();
       for (int i = 0; i < clickableAreas.size(); i++) {
           if (clickableAreas.get(i).contains(pt)) {
               Passenger p = clickablePassengers.get(i);
               LocalTime simStart = p.getFlight()
                                      .getDepartureTime()
                                      .minusMinutes(engine.getArrivalSpan());
               String flightNum = p.getFlight().getFlightNumber();
               String arrivalTime = simStart.plusMinutes(p.getArrivalMinute())
                                            .format(DateTimeFormatter.ofPattern("HH:mm"));
               String purchase = p.isInPerson() ? "In Person" : "Online";


               StringBuilder msg = new StringBuilder();
               msg.append("Flight: ").append(flightNum)
                  .append("\nArrived at: ").append(arrivalTime)
                  .append("\nPurchase Type: ").append(purchase);


               if (p.isInPerson() && p.getTicketCompletionMinute() > 0) {
                   String ticketTime = simStart.plusMinutes(p.getTicketCompletionMinute())
                                               .format(DateTimeFormatter.ofPattern("HH:mm"));
                   msg.append("\nTicketed at: ").append(ticketTime);
               }
               if (p.getCheckpointEntryMinute() > 0) {
                   String ckptEntry = simStart.plusMinutes(p.getCheckpointEntryMinute())
                                              .format(DateTimeFormatter.ofPattern("HH:mm"));
                   msg.append("\nCheckpoint Entry: ").append(ckptEntry);
               }
               if (p.getCheckpointCompletionMinute() > 0) {
                   String ckptDone = simStart.plusMinutes(p.getCheckpointCompletionMinute())
                                             .format(DateTimeFormatter.ofPattern("HH:mm"));
                   msg.append("\nCheckpoint Completion: ").append(ckptDone);
               }


               JOptionPane.showMessageDialog(
                   (Component)e.getComponent(),
                   msg.toString(),
                   "Passenger Info",
                   JOptionPane.INFORMATION_MESSAGE
               );
               return;
           }
       }
   }


   // ----------------------------------------------------------------
   // Ticket lines handler—uses historyQueuedTicket & visibleCompletedTicket
   public static class TicketScrollHandler extends ScrollMouseHandler {
       public TicketScrollHandler(SimulationEngine engine,
                                  List<Rectangle> clickableAreas,
                                  List<Passenger> clickablePassengers,
                                  int[] queuedOffsets,
                                  int[] servedOffsets,
                                  Flight filterFlight,
                                  List<Rectangle> counterAreas) {
           super(engine, clickableAreas, clickablePassengers,
                 queuedOffsets, servedOffsets,
                 filterFlight, counterAreas);
       }


       @Override
       protected void handlePress(MouseEvent e) {
           Component c = e.getComponent();
           int mx = e.getX(), my = e.getY();
           int w = c.getWidth(), h = c.getHeight();


           int leftX = w / 2;
           int top = 50, bottom = h - 50;
           int boxSize = 60;
           int cellW = boxSize / ROWS;
           int gridWidth = COLS * cellW;
           int gridHeight = ROWS * cellW;
           int trackH = cellW / 2;
           int lines = engine.getTicketLines().size();
           int rawSpace = lines > 1 ? (bottom - top) / (lines - 1) : 0;
           int space = Math.max(rawSpace, GridRenderer.MIN_LINE_SPACING);


           // queued scroll zone
           for (int i = 0; i < lines; i++) {
               List<Passenger> fullQ = engine.getHistoryQueuedTicket()
                                             .get(engine.getCurrentInterval() - 1)
                                             .get(i);
               List<Passenger> q = filterFlight == null
                   ? fullQ
                   : fullQ.stream()
                          .filter(p -> p.getFlight() == filterFlight)
                          .collect(Collectors.toList());
               int fullCols = (q.size() + ROWS - 1) / ROWS;
               if (fullCols <= COLS) continue;


               int centerY = space > 0 ? top + i * space : h / 2;
               int boxX = leftX - boxSize / 2, boxY = centerY - boxSize / 2;
               int trackX = boxX - gridWidth;
               int trackY = boxY + (boxSize - gridHeight) / 2 + gridHeight + 2;
               int knobW = (int)((COLS / (double)fullCols) * gridWidth);
               int knobX = trackX +
                   (int)(queuedOffsets[i] / (double)(fullCols - COLS) * (gridWidth - knobW));


               if (mx >= knobX && mx <= knobX + knobW &&
                   my >= trackY && my <= trackY + trackH) {
                   dragging = true;
                   draggingQueued = true;
                   dragLine = i;
                   initialMouseX = mx;
                   initialOffset = queuedOffsets[i];
                   return;
               }
           }


           // served scroll zone (snapshot & live)
           for (int i = 0; i < lines; i++) {
               List<Passenger> fullS;
               if (filterFlight == null) {
                   // live mode
                   fullS = engine.getVisibleCompletedTicketLine(i);
               } else {
                   // snapshot mode: only those still within transit window
                   int step  = engine.getCurrentInterval() - 1;
                   int delay = engine.getTransitDelayMinutes();
                   fullS = engine.getHistoryServedTicket()
                               .get(step)
                               .get(i)
                               .stream()
                               .filter(p -> p.getFlight() == filterFlight)
                               .filter(p -> p.getTicketCompletionMinute() + delay > step)
                               .collect(Collectors.toList());
               }
               int fullCols = (fullS.size() + ROWS - 1) / ROWS;
               if (fullCols <= COLS) continue;


               int centerY = space > 0 ? top + i * space : h / 2;
               int boxX    = leftX - boxSize / 2, boxY = centerY - boxSize / 2;
               int trackX  = boxX + boxSize;
               int trackY  = boxY + (boxSize - gridHeight) / 2 + gridHeight + 2;
               int knobW   = (int)((COLS / (double)fullCols) * gridWidth);
               int knobX   = trackX +
                           (int)(servedOffsets[i] / (double)(fullCols - COLS) * (gridWidth - knobW));


               if (mx >= knobX && mx <= knobX + knobW
               && my >= trackY && my <= trackY + trackH) {
                   dragging       = true;
                   draggingQueued = false;
                   dragLine       = i;
                   initialMouseX  = mx;
                   initialOffset  = servedOffsets[i];
                   return;
               }
           }
       }


       @Override
       protected void handleDrag(MouseEvent e) {
           if (!dragging) return;
           int dx = e.getX() - initialMouseX;
           int cellW = 60 / ROWS;


           if (draggingQueued) {
               List<Passenger> fullQ = engine.getHistoryQueuedTicket()
                                             .get(engine.getCurrentInterval() - 1)
                                             .get(dragLine);
               List<Passenger> q = filterFlight == null
                   ? fullQ
                   : fullQ.stream()
                          .filter(p -> p.getFlight() == filterFlight)
                          .collect(Collectors.toList());
               int fullCols = (q.size() + ROWS - 1) / ROWS;


               int off = initialOffset + dx / cellW;
               queuedOffsets[dragLine] = Math.max(0, Math.min(off, fullCols - COLS));
           } else {
               List<Passenger> fullS;
               if (filterFlight == null) {
                   fullS = engine.getVisibleCompletedTicketLine(dragLine);
               } else {
                   int step  = engine.getCurrentInterval() - 1;
                   int delay = engine.getTransitDelayMinutes();
                   fullS = engine.getHistoryServedTicket()
                               .get(step)
                               .get(dragLine)
                               .stream()
                               .filter(p -> p.getFlight() == filterFlight)
                               .filter(p -> p.getTicketCompletionMinute() + delay > step)
                               .collect(Collectors.toList());
               }
               int fullCols = (fullS.size() + ROWS - 1) / ROWS;
               int off = initialOffset + dx / cellW;
               servedOffsets[dragLine] = Math.max(0, Math.min(off, fullCols - COLS));
           }


           ((Component) e.getComponent()).repaint();
       }


       @Override
       protected void handleClick(MouseEvent e) {
           super.handleClick(e);
           Point pt = e.getPoint();
           for (int i = 0; i < counterAreas.size(); i++) {
               if (counterAreas.get(i).contains(pt)) {
                   int waiting, completed;
                   if (filterFlight == null) {
                       waiting = engine.getTicketLines().get(i).size();
                       completed = engine.getCompletedTicketLines().get(i).size();
                   } else {
                       int step = engine.getCurrentInterval() - 1;
                       waiting = (int) engine.getHistoryQueuedTicket()
                                           .get(step).get(i)
                                           .stream()
                                           .filter(p -> p.getFlight() == filterFlight)
                                           .count();
                       completed = (int) engine.getHistoryServedTicket()
                                            .get(step).get(i)
                                            .stream()
                                            .filter(p -> p.getFlight() == filterFlight)
                                            .count();
                   }
                   int id = engine.getCounterConfigs().get(i).getId();
                   // build the base message
                   StringBuilder msg = new StringBuilder()
                       .append("Counter #: ").append(id)
                       .append("\nWaiting: ").append(waiting)
                       .append("\nCompleted: ").append(completed);


                   // ——— compute max queue & when ———
                   var hist     = engine.getHistoryQueuedTicket();
                   int interval = engine.getInterval();
                   LocalTime firstDep = engine.getFlights().stream()
                       .map(Flight::getDepartureTime)
                       .min(LocalTime::compareTo)
                       .orElse(LocalTime.MIDNIGHT);
                   LocalTime startTime = firstDep.minusMinutes(engine.getArrivalSpan());
                   DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");


                   int maxSize = 0, maxIdx = 0;
                   for (int j = 0; j < hist.size(); j++) {
                       int sz = hist.get(j).get(i).size();
                       if (sz > maxSize) {
                           maxSize = sz;
                           maxIdx  = j;
                       }
                   }
                   LocalTime maxTime = startTime.plusMinutes((long)(maxIdx + 1) * interval);


                   msg.append("\nMax # in line: ").append(maxSize)
                       .append("\nTime of Max passengers: ").append(maxTime.format(fmt))
                       .append("\nSquare footage needed: ").append(maxSize * 15);


                   // show the dialog
                   JOptionPane.showMessageDialog(
                       (Component)e.getComponent(),
                       msg.toString(),
                       "Counter Info",
                       JOptionPane.INFORMATION_MESSAGE
                   );
               }
           }
       }
   }


   // ----------------------------------------------------------------
   // Checkpoint lines handler—uses historyQueuedCheckpoint & historyServedCheckpoint
   public static class CheckpointScrollHandler extends ScrollMouseHandler {
       public CheckpointScrollHandler(SimulationEngine engine,
                                      List<Rectangle> clickableAreas,
                                      List<Passenger> clickablePassengers,
                                      int[] queuedOffsets,
                                      int[] servedOffsets,
                                      Flight filterFlight,
                                      List<Rectangle> counterAreas) {
           super(engine, clickableAreas, clickablePassengers,
                 queuedOffsets, servedOffsets,
                 filterFlight, counterAreas);
       }


       @Override
       protected void handlePress(MouseEvent e) {
           Component c = e.getComponent();
           int mx = e.getX(), my = e.getY();
           int w = c.getWidth(), h = c.getHeight();


           int rightX = w / 2;
           int top = 50, bottom = h - 50;
           int boxSize = 60;
           int cellW = boxSize / ROWS;
           int gridWidth = COLS * cellW;
           int gridHeight = ROWS * cellW;
           int trackH = cellW / 2;
           int lines = engine.getCheckpointLines().size();
           int rawSpace = lines > 1 ? (bottom - top) / (lines - 1) : 0;
           int space = Math.max(rawSpace, GridRenderer.MIN_LINE_SPACING);


           // queued scroll zone
           for (int i = 0; i < lines; i++) {
               List<Passenger> fullQ = engine.getHistoryQueuedCheckpoint()
                                             .get(engine.getCurrentInterval() - 1)
                                             .get(i);
               List<Passenger> q = filterFlight == null
                   ? fullQ
                   : fullQ.stream()
                          .filter(p -> p.getFlight() == filterFlight)
                          .collect(Collectors.toList());
               int fullCols = (q.size() + ROWS - 1) / ROWS;
               if (fullCols <= COLS) continue;


               int centerY = space > 0 ? top + i * space : h / 2;
               int boxX = rightX - boxSize / 2, boxY = centerY - boxSize / 2;
               int trackX = boxX - gridWidth;
               int trackY = boxY + (boxSize - gridHeight) / 2 + gridHeight + 2;
               int knobW = (int)((COLS / (double)fullCols) * gridWidth);
               int knobX = trackX +
                   (int)(queuedOffsets[i] / (double)(fullCols - COLS) * (gridWidth - knobW));


               if (mx >= knobX && mx <= knobX + knobW &&
                   my >= trackY && my <= trackY + trackH) {
                   dragging = true;
                   draggingQueued = true;
                   dragLine = i;
                   initialMouseX = mx;
                   initialOffset = queuedOffsets[i];
                   return;
               }
           }


           // served scroll zone
           for (int i = 0; i < lines; i++) {
               List<Passenger> fullS = engine.getHistoryServedCheckpoint()
                                             .get(engine.getCurrentInterval() - 1)
                                             .get(i);
               List<Passenger> s = filterFlight == null
                   ? fullS
                   : fullS.stream()
                          .filter(p -> p.getFlight() == filterFlight)
                          .collect(Collectors.toList());
               int fullCols = (s.size() + ROWS - 1) / ROWS;
               if (fullCols <= COLS) continue;


               int centerY = space > 0 ? top + i * space : h / 2;
               int boxX = rightX - boxSize / 2, boxY = centerY - boxSize / 2;
               int trackX = boxX + boxSize;
               int trackY = boxY + (boxSize - gridHeight) / 2 + gridHeight + 2;
               int knobW = (int)((COLS / (double)fullCols) * gridWidth);
               int knobX = trackX +
                   (int)(servedOffsets[i] / (double)(fullCols - COLS) * (gridWidth - knobW));


               if (mx >= knobX && mx <= knobX + knobW &&
                   my >= trackY && my <= trackY + trackH) {
                   dragging = true;
                   draggingQueued = false;
                   dragLine = i;
                   initialMouseX = mx;
                   initialOffset = servedOffsets[i];
                   return;
               }
           }
       }


       @Override
       protected void handleDrag(MouseEvent e) {
           if (!dragging) return;
           int dx = e.getX() - initialMouseX;
           int cellW = 60 / ROWS;


           if (draggingQueued) {
               List<Passenger> fullQ = engine.getHistoryQueuedCheckpoint()
                                             .get(engine.getCurrentInterval() - 1)
                                             .get(dragLine);
               List<Passenger> q = filterFlight == null
                   ? fullQ
                   : fullQ.stream()
                          .filter(p -> p.getFlight() == filterFlight)
                          .collect(Collectors.toList());
               int fullCols = (q.size() + ROWS - 1) / ROWS;
               int off = initialOffset + dx / cellW;
               queuedOffsets[dragLine] = Math.max(0, Math.min(off, fullCols - COLS));
           } else {
               List<Passenger> fullS = engine.getHistoryServedCheckpoint()
                                             .get(engine.getCurrentInterval() - 1)
                                             .get(dragLine);
               List<Passenger> s = filterFlight == null
                   ? fullS
                   : fullS.stream()
                          .filter(p -> p.getFlight() == filterFlight)
                          .collect(Collectors.toList());
               int fullCols = (s.size() + ROWS - 1) / ROWS;
               int off = initialOffset + dx / cellW;
               servedOffsets[dragLine] = Math.max(0, Math.min(off, fullCols - COLS));
           }
           ((Component) e.getComponent()).repaint();
       }


       @Override
       protected void handleClick(MouseEvent e) {
           super.handleClick(e);
           Point pt = e.getPoint();
           for (int i = 0; i < counterAreas.size(); i++) {
               if (counterAreas.get(i).contains(pt)) {
                   int waiting, completed;
                   if (filterFlight == null) {
                       waiting = engine.getCheckpointLines().get(i).size();
                       completed = engine.getCompletedCheckpointLines().get(i).size();
                   } else {
                       int step = engine.getCurrentInterval() - 1;
                       waiting = (int) engine.getHistoryQueuedCheckpoint()
                                            .get(step).get(i)
                                            .stream()
                                            .filter(p -> p.getFlight() == filterFlight)
                                            .count();
                       completed = (int) engine.getHistoryServedCheckpoint()
                                              .get(step).get(i)
                                              .stream()
                                              .filter(p -> p.getFlight() == filterFlight)
                                              .count();
                   }
                   int id = i + 1;
           // build the base message with StringBuilder
           StringBuilder msg = new StringBuilder()
               .append("Checkpoint #: ").append(id)
               .append("\nWaiting: ").append(waiting)
               .append("\nCompleted: ").append(completed);


           // ——— compute max queue & when ———
           var hist     = engine.getHistoryQueuedCheckpoint();
           int interval = engine.getInterval();
           // reconstruct sim start time
           LocalTime firstDep = engine.getFlights().stream()
               .map(Flight::getDepartureTime)
               .min(LocalTime::compareTo)
               .orElse(LocalTime.MIDNIGHT);
           LocalTime startTime = firstDep.minusMinutes(engine.getArrivalSpan());
           DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");


           int maxSize = 0, maxIdx = 0;
           for (int j = 0; j < hist.size(); j++) {
               int sz = hist.get(j).get(i).size();
               if (sz > maxSize) {
                   maxSize = sz;
                   maxIdx  = j;
               }
           }
           LocalTime maxTime = startTime.plusMinutes((long)(maxIdx + 1) * interval);


           msg.append("\nMax # in line: ").append(maxSize)
           .append("\nTime of Max passengers: ").append(maxTime.format(fmt))
           .append("\nSquare footage needed: ").append(maxSize * 15);


           // finally show the dialog with the full message
           JOptionPane.showMessageDialog(
               (Component)e.getComponent(),
               msg.toString(),
               "Checkpoint Info",
               JOptionPane.INFORMATION_MESSAGE
           );
                   return;
               }
           }
       }
   }
}


