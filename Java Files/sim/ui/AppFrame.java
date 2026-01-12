package sim.ui;


import sim.floorplan.model.FloorplanProject;
import sim.floorplan.ui.FloorplanEditorPanel;


import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Method;


public class AppFrame extends JFrame {


   private final JTabbedPane tabs = new JTabbedPane();


   // Editor
   private final FloorplanEditorPanel floorplanEditor = new FloorplanEditorPanel();


   // ✅ Blank-canvas setup (now blank-only)
   private final AirportSetupPanel blankSetupPanel = new AirportSetupPanel();


   // ✅ Floorplan setup (independent)
   private final FloorplanSetupPanel floorplanSetupPanel = new FloorplanSetupPanel();


   // Analytics tab (still enabled only when BLANK sim starts)
   private final JPanel analyticsPanel = new JPanel(new BorderLayout());
   private final JLabel analyticsStatus = new JLabel("Run a simulation to enable analytics.", SwingConstants.CENTER);


   // Tab indices (keep stable)
   private static final int TAB_EDITOR = 0;
   private static final int TAB_BLANK_SIM = 1;
   private static final int TAB_FLOORPLAN_SIM = 2;
   private static final int TAB_ANALYTICS = 3;


   public AppFrame() {
       super("AirportSim — Blank Canvas + Floorplan");
       setDefaultCloseOperation(EXIT_ON_CLOSE);
       setLayout(new BorderLayout());


       // ----------------------------
       // Floorplan Editor tab + unlock button
       // ----------------------------
       JPanel floorplanEditorTab = new JPanel(new BorderLayout());
       floorplanEditorTab.add(floorplanEditor, BorderLayout.CENTER);


       JButton unlockFloorplanSimBtn = new JButton("Validate & Unlock Floorplan Simulation");
       unlockFloorplanSimBtn.addActionListener(e -> validateAndUnlockFloorplanSimulation());


       JPanel editorBottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
       editorBottom.add(unlockFloorplanSimBtn);
       floorplanEditorTab.add(editorBottom, BorderLayout.SOUTH);


       // ----------------------------
       // Blank-canvas Simulation tab
       // ----------------------------
       JPanel blankSimTab = new JPanel(new BorderLayout());
       blankSimTab.add(blankSetupPanel, BorderLayout.CENTER);


       // ----------------------------
       // Floorplan Simulation tab (starts disabled until unlock)
       // ----------------------------
       JPanel floorplanSimTab = new JPanel(new BorderLayout());
       floorplanSimTab.add(floorplanSetupPanel, BorderLayout.CENTER);


       // ----------------------------
       // Analytics tab
       // ----------------------------
       analyticsPanel.add(analyticsStatus, BorderLayout.CENTER);


       tabs.addTab("Floorplan Editor", floorplanEditorTab);
       tabs.addTab("Blank Canvas Simulation", blankSimTab);
       tabs.addTab("Floorplan Simulation", floorplanSimTab);
       tabs.addTab("Analytics", analyticsPanel);


       // ✅ floorplan sim gated; blank sim NOT gated
       tabs.setEnabledAt(TAB_FLOORPLAN_SIM, false);


       // Analytics enabled when BLANK sim starts (unchanged behavior)
       tabs.setEnabledAt(TAB_ANALYTICS, false);
       blankSetupPanel.setSimulationStartListener((tableEngine, simEngine) -> {
           tabs.setEnabledAt(TAB_ANALYTICS, true);
           analyticsStatus.setText("<html><div style='text-align:center;'>Analytics enabled.<br/>" +
                   "For now, analytics are still shown inside the Simulation window & Data Table window.<br/>" +
                   "Next step: embed those panels directly into this Analytics tab.</div></html>");
       });


       add(tabs, BorderLayout.CENTER);


       setSize(1120, 880);
       setLocationRelativeTo(null);
   }


   /**
    * Calls FloorplanEditorPanel.validateAndLock() + getProjectCopy() (best-effort),
    * then passes the copy into FloorplanSetupPanel and enables the Floorplan Simulation tab.
    */
   private void validateAndUnlockFloorplanSimulation() {
       boolean ok = tryInvokeBoolean(floorplanEditor,
               "validateAndLock",
               "validateAndLockFloorplan",
               "validateAndLockProject"
       );


       if (!ok) {
           JOptionPane.showMessageDialog(this,
                   "Floorplan validation failed. Please fix issues in the editor and try again.",
                   "Validation Failed",
                   JOptionPane.WARNING_MESSAGE);
           return;
       }


       FloorplanProject copy = tryInvokeProjectCopy(floorplanEditor,
               "getProjectCopy",
               "getLockedProjectCopy",
               "getValidatedProjectCopy"
       );


       if (copy == null) {
           JOptionPane.showMessageDialog(this,
                   "Validated floorplan copy could not be retrieved.\n" +
                           "Make sure your FloorplanEditorPanel exposes getProjectCopy().",
                   "Missing Project Copy",
                   JOptionPane.WARNING_MESSAGE);
           return;
       }


       floorplanSetupPanel.setFloorplanProjectCopy(copy);
       tabs.setEnabledAt(TAB_FLOORPLAN_SIM, true);
       tabs.setSelectedIndex(TAB_FLOORPLAN_SIM);
   }


   private static boolean tryInvokeBoolean(Object target, String... methodNames) {
       if (target == null) return false;
       for (String name : methodNames) {
           try {
               Method m = target.getClass().getMethod(name);
               Object out = m.invoke(target);
               if (out instanceof Boolean) return (Boolean) out;
               if (out != null && out.getClass() == boolean.class) return (boolean) out;
           } catch (Exception ignored) {}
       }
       return false;
   }


   private static FloorplanProject tryInvokeProjectCopy(Object target, String... methodNames) {
       if (target == null) return null;
       for (String name : methodNames) {
           try {
               Method m = target.getClass().getMethod(name);
               Object out = m.invoke(target);
               if (out instanceof FloorplanProject) return (FloorplanProject) out;
           } catch (Exception ignored) {}
       }
       return null;
   }


   public static void main(String[] args) {
       SwingUtilities.invokeLater(() -> new AppFrame().setVisible(true));
   }
}
