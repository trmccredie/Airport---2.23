package sim.ui;


import sim.model.Flight;
import sim.model.Passenger;
import sim.service.SimulationEngine;


import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;


public class DataTableFrame extends JFrame {
   private final JTabbedPane tabbedPane;


   public DataTableFrame(SimulationEngine engine) {
       super("Interval Data");
       setDefaultCloseOperation(DISPOSE_ON_CLOSE);


       // Run full simulation to populate history and arrivals
       engine.runAllIntervals();


       // Prepare time headers
       List<Flight> flights = engine.getFlights();
       LocalTime firstDep = flights.stream()
           .map(Flight::getDepartureTime)
           .min(LocalTime::compareTo)
           .orElse(LocalTime.MIDNIGHT);
       LocalTime startTime = firstDep.minusMinutes(engine.getArrivalSpan());
       int interval = engine.getInterval();
       int totalIntervals = engine.getTotalIntervals();
       DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");


       // Create tabbed pane
       tabbedPane = new JTabbedPane();


       // Arrivals tab
       JTable arrivalsTable = new JTable(
           new ArrivalsTableModel(engine, startTime, fmt)
       );
       configureTable(arrivalsTable);
       tabbedPane.addTab("Arrivals", new JScrollPane(arrivalsTable));


       // Ticket Queue tab
       JTable ticketQueueTable = new JTable(
           new BaseHistoryTableModel(
               engine.getHistoryQueuedTicket(),
               "Ticket Queue",
               startTime,
               interval,
               totalIntervals,
               fmt
           )
       );
       configureTable(ticketQueueTable);
       tabbedPane.addTab("Ticket Queue", new JScrollPane(ticketQueueTable));


       // Ticket Completed tab
       JTable ticketCompletedTable = new JTable(
           new BaseHistoryTableModel(
               engine.getHistoryServedTicket(),
               "Ticket Completed",
               startTime,
               interval,
               totalIntervals,
               fmt
           )
       );
       configureTable(ticketCompletedTable);
       tabbedPane.addTab("Ticket Completed", new JScrollPane(ticketCompletedTable));


       // Checkpoint Queue tab
       JTable checkpointQueueTable = new JTable(
           new BaseHistoryTableModel(
               engine.getHistoryQueuedCheckpoint(),
               "Checkpoint Queue",
               startTime,
               interval,
               totalIntervals,
               fmt
           )
       );
       configureTable(checkpointQueueTable);
       tabbedPane.addTab("Checkpoint Queue", new JScrollPane(checkpointQueueTable));


       // Checkpoint Completed tab
       JTable checkpointCompletedTable = new JTable(
           new BaseHistoryTableModel(
               engine.getHistoryServedCheckpoint(),
               "Checkpoint Completed",
               startTime,
               interval,
               totalIntervals,
               fmt
           )
       );
       configureTable(checkpointCompletedTable);
       tabbedPane.addTab("Checkpoint Completed", new JScrollPane(checkpointCompletedTable));


       add(tabbedPane, BorderLayout.CENTER);


       // Export all tabs as CSV
       JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
       JButton exportBtn = new JButton("Save All as CSV");
       exportBtn.addActionListener(e -> {
           JFileChooser chooser = new JFileChooser();
           chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
           chooser.setDialogTitle("Select Directory to Save CSVs");
           if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
               File dir = chooser.getSelectedFile();
               try {
                   writeTableAsCsv(arrivalsTable, new File(dir, "Arrivals.csv"));
                   writeTableAsCsv(ticketQueueTable, new File(dir, "TicketQueue.csv"));
                   writeTableAsCsv(ticketCompletedTable, new File(dir, "TicketCompleted.csv"));
                   writeTableAsCsv(checkpointQueueTable, new File(dir, "CheckpointQueue.csv"));
                   writeTableAsCsv(checkpointCompletedTable, new File(dir, "CheckpointCompleted.csv"));
                   JOptionPane.showMessageDialog(
                       this,
                       "All tables saved to: " + dir.getAbsolutePath()
                   );
               } catch (IOException ex) {
                   JOptionPane.showMessageDialog(
                       this,
                       "Error saving CSVs: " + ex.getMessage(),
                       "Save Error",
                       JOptionPane.ERROR_MESSAGE
                   );
               }
           }
       });
       bottomPanel.add(exportBtn);
       add(bottomPanel, BorderLayout.SOUTH);


       setSize(1000, 600);
       setLocationRelativeTo(null);
   }


   private void configureTable(JTable table) {
       table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
       table.setDefaultRenderer(
           Object.class,
           new DefaultTableCellRenderer() {
               @Override
               public Component getTableCellRendererComponent(
                       JTable table,
                       Object value,
                       boolean isSelected,
                       boolean hasFocus,
                       int row,
                       int column) {
                   Component c = super.getTableCellRendererComponent(
                       table, value, isSelected, hasFocus, row, column
                   );
                   c.setBackground(Color.WHITE);
                   return c;
               }
           }
       );
   }


   private void writeTableAsCsv(JTable table, File file) throws IOException {
       try (
           Writer out = new BufferedWriter(
               new OutputStreamWriter(
                   new FileOutputStream(file),
                   StandardCharsets.UTF_8
               )
           )
       ) {
           // Header
           for (int c = 0; c < table.getColumnCount(); c++) {
               if (c > 0) out.write(',');
               out.write(escapeCsv(table.getColumnName(c)));
           }
           out.write("\n");
           // Data rows
           for (int r = 0; r < table.getRowCount(); r++) {
               for (int c = 0; c < table.getColumnCount(); c++) {
                   if (c > 0) out.write(',');
                   Object v = table.getValueAt(r, c);
                   out.write(escapeCsv(v == null ? "" : v.toString()));
               }
               out.write("\n");
           }
       }
   }


   private String escapeCsv(String s) {
       boolean need =
           s.contains(",") ||
           s.contains("\"") ||
           s.contains("\n") ||
           s.contains("\r");
       String t = s.replace("\"", "\"\"");
       return need ? "\"" + t + "\"" : t;
   }


   private static class ArrivalsTableModel extends AbstractTableModel {
       private final String[] columnNames;
       private final Object[][] data;


       public ArrivalsTableModel(
           SimulationEngine engine,
           LocalTime startTime,
           DateTimeFormatter fmt
       ) {
           List<Flight> flights = engine.getFlights();
           int totalMinutes = Math.max(engine.getArrivalSpan() - 20, 0);
           columnNames = new String[totalMinutes + 1];
           columnNames[0] = "Time";
           for (int m = 0; m < totalMinutes; m++) {
               columnNames[m + 1] = startTime.plusMinutes(m + 1).format(fmt);
           }
           data = new Object[flights.size() + 1][totalMinutes + 1];
           Map<Flight, int[]> perMin = engine.getMinuteArrivalsMap();
           int row = 0;
           for (Flight f : flights) {
               data[row][0] = "Arrivals - " + f.getFlightNumber();
               int[] arr = perMin.getOrDefault(f, new int[totalMinutes]);
               for (int m = 0; m < totalMinutes; m++) {
                   data[row][m + 1] = arr[m];
               }
               row++;
           }
           data[row][0] = "Total Arrivals";
           for (int m = 0; m < totalMinutes; m++) {
               int sum = 0;
               for (Flight f : flights) {
                   sum += perMin.getOrDefault(f, new int[totalMinutes])[m];
               }
               data[row][m + 1] = sum;
           }
       }


       @Override public int getRowCount() { return data.length; }
       @Override public int getColumnCount() { return columnNames.length; }
       @Override public String getColumnName(int col) { return columnNames[col]; }
       @Override public Object getValueAt(int row, int col) { return data[row][col]; }
   }


   private static class BaseHistoryTableModel extends AbstractTableModel {
       private final String[] columnNames;
       private final Object[][] data;


       public BaseHistoryTableModel(
           List<List<List<Passenger>>> history,
           String label,
           LocalTime startTime,
           int interval,
           int totalIntervals,
           DateTimeFormatter fmt
       ) {
           // clamp negative totalIntervals to zero
           int ti = Math.max(totalIntervals, 0);


           columnNames = new String[ti + 1];
           columnNames[0] = "Time";
           for (int i = 0; i < ti; i++) {
               columnNames[i + 1] =
                   startTime.plusMinutes((long)(i + 1) * interval).format(fmt);
           }


           int lines = history.isEmpty() ? 0 : history.get(0).size();
           data = new Object[lines + 1][ti + 1];


           // first column labels
           data[0][0] = "Total " + label;
           for (int r = 1; r <= lines; r++) {
               data[r][0] = label + " " + r;
           }


           // fill counts
           for (int c = 0; c < ti; c++) {
               int sum = 0;
               for (int r = 1; r <= lines; r++) {
                   int count = history.get(c).get(r - 1).size();
                   data[r][c + 1] = count;
                   sum += count;
               }
               data[0][c + 1] = sum;
           }
       }


       @Override public int getRowCount() { return data.length; }
       @Override public int getColumnCount() { return columnNames.length; }
       @Override public String getColumnName(int col) { return columnNames[col]; }
       @Override public Object getValueAt(int row, int col) { return data[row][col]; }
   }
}
