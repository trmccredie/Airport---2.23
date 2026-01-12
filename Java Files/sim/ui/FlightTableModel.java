package sim.ui;




// FlightTableModel.java








import sim.model.Flight;
import javax.swing.table.AbstractTableModel;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;




public class FlightTableModel extends AbstractTableModel {
   private final String[] columns = {"Flight #","Dep Time","Seats","Fill%","Shape"};
   private final List<Flight> flights = new ArrayList<>();




   @Override public int getRowCount() { return flights.size(); }
   @Override public int getColumnCount() { return columns.length; }
   @Override public String getColumnName(int col) { return columns[col]; }
   @Override public Class<?> getColumnClass(int col) {
       switch (col) {
           case 2: return Integer.class;
           case 3: return Double.class;
           case 4: return Flight.ShapeType.class;
           default: return String.class;
       }
   }




   @Override
   public Object getValueAt(int row, int col) {
       Flight f = flights.get(row);
       switch (col) {
           case 0: return f.getFlightNumber();
           case 1:
               LocalTime t = f.getDepartureTime();
               return String.format("%d.%02d", t.getHour(), t.getMinute());
           case 2: return f.getSeats();
           case 3: return f.getFillPercent();
           case 4: return f.getShape();
           default: return null;
       }
   }




   @Override public boolean isCellEditable(int row, int col) { return true; }




   @Override
   public void setValueAt(Object val, int row, int col) {
       Flight f = flights.get(row);
       try {
           switch (col) {
               case 0:
                   f.setFlightNumber(val.toString());
                   break;
               case 1:
                   String s = val.toString();
                   if (s.contains(".")) {
                       String[] p = s.split("\\.");
                       f.setDepartureTime(LocalTime.of(
                           Integer.parseInt(p[0]), Integer.parseInt(p[1])
                       ));
                   } else {
                       f.setDepartureTime(LocalTime.parse(s));
                   }
                   break;
               case 2:
                   f.setSeats((Integer)val);
                   break;
               case 3:
                   f.setFillPercent((Double)val);
                   break;
               case 4:
                   f.setShape((Flight.ShapeType)val);
                   break;
           }
       } catch (Exception ex) {
           // ignore invalid input
       }
       fireTableCellUpdated(row, col);
   }




   public void addFlight(Flight f) {
       flights.add(f);
       fireTableRowsInserted(flights.size()-1, flights.size()-1);
   }




   public void removeFlight(int idx) {
       flights.remove(idx);
       fireTableRowsDeleted(idx, idx);
   }




   public List<Flight> getFlights() { return flights; }
}
