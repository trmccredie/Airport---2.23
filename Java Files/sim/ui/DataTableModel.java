package sim.ui;




import sim.model.Flight;
import sim.service.SimulationEngine;




import javax.swing.table.AbstractTableModel;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Collections;
import java.util.Map;








/**
* Displays a minute‑by‑minute arrivals table:
* - One column per minute from (firstDeparture – arrivalSpan) up to
*   20 minutes before the last departure.
* - One row per flight showing how many passengers arrived at each minute.
* - A final row showing the total arrivals across all flights each minute.
*/
public class DataTableModel extends AbstractTableModel {
   private final String[] columnNames;
   private final Object[][] data;




   public DataTableModel(SimulationEngine engine) {
       List<Flight> flights = engine.getFlights();
       // If no flights, show empty
       if (flights.isEmpty()) {
           columnNames = new String[] {"Minute"};
           data = new Object[0][1];
           return;
       }




       // Compute absolute time window
       LocalTime firstDep = flights.stream()
           .map(Flight::getDepartureTime)
           .min(LocalTime::compareTo)
           .orElse(LocalTime.MIDNIGHT);
       int arrivalSpan = engine.getArrivalSpan();          // e.g. 120 minutes
       int totalMinutes = arrivalSpan - 20;                // as per ArrivalGenerator




       // Build column headers: one for each minute
       columnNames = new String[totalMinutes + 1];
       columnNames[0] = "Minute";
       DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
       LocalTime startTime = firstDep.minusMinutes(arrivalSpan);
       for (int m = 0; m < totalMinutes; m++) {
           columnNames[m + 1] = startTime.plusMinutes(m).format(fmt);
       }




       // Prepare data rows: one per flight + one "Total Arrivals" row
       int nRows = flights.size() + 1;
       data = new Object[nRows][totalMinutes + 1];




       // Fetch per‑minute arrivals map
       Map<Flight,int[]> perMinMap = engine.getMinuteArrivalsMap();




       // Fill flight rows
       int row = 0;
       for (Flight f : flights) {
           data[row][0] = "Arrivals - " + f.getFlightNumber();
           int[] arr = perMinMap.getOrDefault(f, new int[totalMinutes]);
           for (int m = 0; m < totalMinutes; m++) {
               data[row][m + 1] = arr[m];
           }
           row++;
       }




       // Fill total row
       data[row][0] = "Total Arrivals";
       for (int m = 0; m < totalMinutes; m++) {
           int sum = 0;
           for (Flight f : flights) {
               sum += perMinMap.getOrDefault(f, new int[totalMinutes])[m];
           }
           data[row][m + 1] = sum;
       }
   }




   @Override
   public int getRowCount() {
       return data.length;
   }




   @Override
   public int getColumnCount() {
       return columnNames.length;
   }




   @Override
   public String getColumnName(int col) {
       return columnNames[col];
   }




   @Override
   public Object getValueAt(int row, int col) {
       return data[row][col];
   }
}


