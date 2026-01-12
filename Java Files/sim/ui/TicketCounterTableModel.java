package sim.ui;


import sim.model.Flight;
import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;


/**
* Table model for configuring ticket counters:
* Columns: [Counter #, Rate (passengers/hr), Available Flights]
*
* Internally TicketCounterConfig rate is still stored as passengers/minute.
* This table converts for display/editing.
*/
public class TicketCounterTableModel extends AbstractTableModel {
   private final String[] columns = {
           "Counter #", "Rate (passengers/hr)", "Available Flights"
   };
   private final List<TicketCounterConfig> counters;
   private final List<Flight> flights;


   public TicketCounterTableModel(List<Flight> flights) {
       this.flights = flights;
       this.counters = new ArrayList<>();
   }


   @Override
   public int getRowCount() {
       return counters.size();
   }


   @Override
   public int getColumnCount() {
       return columns.length;
   }


   @Override
   public String getColumnName(int col) {
       return columns[col];
   }


   @Override
   public Class<?> getColumnClass(int col) {
       switch (col) {
           case 0: return Integer.class;
           case 1: return Double.class;  // passengers/hr
           case 2: return String.class;
           default: return Object.class;
       }
   }


   @Override
   public Object getValueAt(int row, int col) {
       TicketCounterConfig cfg = counters.get(row);
       switch (col) {
           case 0: return cfg.getId();


           case 1:
               // UI shows passengers/hr, internal stored passengers/min
               return cfg.getRate() * 60.0;


           case 2:
               return cfg.isAllFlights()
                       ? "All"
                       : String.join(", ",
                       cfg.getAllowedFlights().stream()
                               .map(Flight::getFlightNumber)
                               .toArray(String[]::new)
               );
           default:
               return null;
       }
   }


   @Override
   public boolean isCellEditable(int row, int col) {
       return col == 1 || col == 2;
   }


   @Override
   @SuppressWarnings("unchecked")
   public void setValueAt(Object val, int row, int col) {
       TicketCounterConfig cfg = counters.get(row);


       switch (col) {
           case 1: {
               // UI provides passengers/hr -> convert to passengers/min
               double perHour;
               if (val instanceof Number) {
                   perHour = ((Number) val).doubleValue();
               } else if (val != null) {
                   try { perHour = Double.parseDouble(val.toString().trim()); }
                   catch (Exception ignored) { return; }
               } else {
                   return;
               }


               cfg.setRate(Math.max(0.0, perHour) / 60.0);
               break;
           }


           case 2:
               if (val instanceof Set) {
                   cfg.setAllowedFlights((Set<Flight>) val);
               }
               break;


           default:
               return;
       }


       fireTableCellUpdated(row, col);
   }


   public void addCounter() {
       int id = counters.size() + 1;
       counters.add(new TicketCounterConfig(id));
       fireTableRowsInserted(counters.size() - 1, counters.size() - 1);
   }


   public void removeCounter(int idx) {
       counters.remove(idx);
       for (int i = 0; i < counters.size(); i++) {
           counters.get(i).setId(i + 1);
       }
       fireTableDataChanged();
   }


   public List<TicketCounterConfig> getCounters() {
       return new ArrayList<>(counters);
   }
}
