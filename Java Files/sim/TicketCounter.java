// TicketCounter.java
package sim;


import sim.ui.UIUtils;
import javax.swing.SwingUtilities;


import javax.swing.UIManager;






import java.awt.Dimension;




public class TicketCounter {
   public static void main(String[] args) {
       UIManager.put("ScrollBar.width", 16);
       UIManager.put("ScrollBar.minimumThumbSize", new Dimension(40, 16));
               UIUtils.initUI();


       SwingUtilities.invokeLater(() -> {
           sim.ui.MainFrame frame = new sim.ui.MainFrame();
           frame.setVisible(true);
       });
   }
}
