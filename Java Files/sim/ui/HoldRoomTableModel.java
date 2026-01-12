package sim.ui;


import sim.model.Flight;


import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;


/**
* Table model for configuring PHYSICAL hold rooms:
* Columns: [Room #, Walk Min, Walk Sec, Available Flights]
*
* Available Flights is now edited as comma-separated TEXT:
*   "1, 2, 3"  => restrict to those flight numbers
*   "" or "All" => accept ALL flights
*
* Note: HoldRoomConfig stores allowed flights as flightNumber Strings.
*/
public class HoldRoomTableModel extends AbstractTableModel {
   private final String[] columns = { "Room #", "Walk Min", "Walk Sec", "Available Flights" };


   private final List<HoldRoomConfig> rooms = new ArrayList<>();


   // kept for backwards-compat with your current constructor calls
   @SuppressWarnings("unused")
   private final List<Flight> flights;


   public HoldRoomTableModel(List<Flight> flights) {
       this.flights = flights;
   }


   public HoldRoomTableModel() {
       this.flights = null;
   }


   @Override
   public int getRowCount() {
       return rooms.size();
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
           case 0: return Integer.class; // id
           case 1: return Integer.class; // walk min
           case 2: return Integer.class; // walk sec
           case 3: return String.class;  // comma-separated text
           default: return Object.class;
       }
   }


   @Override
   public Object getValueAt(int row, int col) {
       HoldRoomConfig cfg = rooms.get(row);
       switch (col) {
           case 0: return cfg.getId();
           case 1: return cfg.getWalkMinutes();
           case 2: return cfg.getWalkSecondsPart();
           case 3:
               // show "All" when no restrictions
               if (cfg.getAllowedFlightNumbers().isEmpty()) return "All";
               return String.join(", ", cfg.getAllowedFlightNumbers());
           default:
               return null;
       }
   }


   @Override
   public boolean isCellEditable(int row, int col) {
       return col == 1 || col == 2 || col == 3;
   }


   @Override
   public void setValueAt(Object val, int row, int col) {
       HoldRoomConfig cfg = rooms.get(row);


       switch (col) {
           case 1: { // minutes
               int m = toNonNegInt(val);
               int s = cfg.getWalkSecondsPart();
               cfg.setWalkTime(m, s);
               break;
           }
           case 2: { // seconds (0..59)
               int s = toNonNegInt(val);
               s = Math.max(0, Math.min(59, s));
               int m = cfg.getWalkMinutes();
               cfg.setWalkTime(m, s);
               break;
           }
           case 3: { // ✅ comma-separated flight numbers
               String text = (val == null) ? "" : val.toString();
               applyAllowedFlightsText(cfg, text);
               break;
           }
           default:
               return;
       }


       fireTableCellUpdated(row, col);
   }


   private static void applyAllowedFlightsText(HoldRoomConfig cfg, String text) {
       if (cfg == null) return;


       String raw = (text == null) ? "" : text.trim();
       if (raw.isEmpty() || raw.equalsIgnoreCase("all")) {
           cfg.clearAllowedFlights(); // empty => ALL flights
           return;
       }


       // Split on commas, trim, drop blanks
       List<String> tokens = new ArrayList<>();
       for (String part : raw.split(",")) {
           if (part == null) continue;
           String t = part.trim();
           if (!t.isEmpty()) tokens.add(t);
       }


       if (tokens.isEmpty()) cfg.clearAllowedFlights();
       else cfg.setAllowedFlightNumbers(tokens);
   }


   /** Adds a new hold room with default walk time 0:00 and "All flights". */
   public void addHoldRoom() {
       int id = rooms.size() + 1;
       rooms.add(new HoldRoomConfig(id, 0));
       fireTableRowsInserted(rooms.size() - 1, rooms.size() - 1);
   }


   /**
    * Removes a hold room and renumbers IDs to remain 1..N.
    * HoldRoomConfig.id is final, so we recreate configs to renumber safely.
    */
   public void removeHoldRoom(int idx) {
       if (idx < 0 || idx >= rooms.size()) return;


       rooms.remove(idx);


       List<HoldRoomConfig> rebuilt = new ArrayList<>();
       for (int i = 0; i < rooms.size(); i++) {
           HoldRoomConfig old = rooms.get(i);
           HoldRoomConfig cfg = new HoldRoomConfig(i + 1, old.getWalkSecondsFromCheckpoint());
           cfg.setAllowedFlightNumbers(old.getAllowedFlightNumbers());
           rebuilt.add(cfg);
       }
       rooms.clear();
       rooms.addAll(rebuilt);


       fireTableDataChanged();
   }


   /** Returns the user-configured hold rooms. */
   public List<HoldRoomConfig> getHoldRooms() {
       return new ArrayList<>(rooms);
   }


   private static int toNonNegInt(Object val) {
       if (val == null) return 0;
       if (val instanceof Integer) return Math.max(0, (Integer) val);
       if (val instanceof Number) return Math.max(0, ((Number) val).intValue());
       try {
           return Math.max(0, Integer.parseInt(val.toString().trim()));
       } catch (Exception e) {
           return 0;
       }
   }
}
