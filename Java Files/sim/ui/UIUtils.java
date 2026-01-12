package sim.ui;


import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.FontUIResource;
import java.awt.*;


/**
* Utility class to initialize global UI settings for a modern, professional look.
*/
public class UIUtils {
   /**
    * Apply Nimbus look-and-feel, custom theme colors, anti-aliasing, fonts, and padding.
    * Call this at application startup before creating any Swing components.
    */
   public static void initUI() {
       // 1) Nimbus Look & Feel for a clean, modern style
       try {
           for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
               if ("Nimbus".equals(info.getName())) {
                   UIManager.setLookAndFeel(info.getClassName());
                   break;
               }
           }
       } catch (Exception e) {
           try {
               UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
           } catch (Exception ex) {
               ex.printStackTrace();
           }
       }


       // 2) Define primary theme colors
       Color primary   = new Color(60, 120, 180);
       Color secondary = primary.darker();


       // 3) Override Nimbus defaults for a cohesive palette
       UIManager.put("nimbusBase",          primary);
       UIManager.put("nimbusBlueGrey",     primary);
       UIManager.put("control",            Color.WHITE);
       UIManager.put("text",               Color.DARK_GRAY);


       // 4) Set button background & text color for contrast
       UIManager.put("Button.background",       primary);
       UIManager.put("Button[Enabled].background", primary);
       UIManager.put("Button[Disabled].background", secondary);
       UIManager.put("Button.foreground",       Color.WHITE);


       // 5) Enable anti-aliased text
       System.setProperty("awt.useSystemAAFontSettings", "on");
       System.setProperty("swing.aatext", "true");


       // 6) Global default font
       FontUIResource defaultFont = new FontUIResource("Segoe UI", Font.PLAIN, 14);
       UIDefaults defaults = UIManager.getDefaults();
       for (Object key : defaults.keySet()) {
           String strKey = key.toString().toLowerCase();
           if (strKey.contains("font") || strKey.contains("textfont")) {
               UIManager.put(key, defaultFont);
           }
       }


       // 7) Component padding and sizing
       UIManager.put("Button.margin",        new Insets(8, 16, 8, 16));
       UIManager.put("TextField.margin",     new Insets(6,  8, 6,  8));
       UIManager.put("Table.rowHeight",      28);
       UIManager.put("Table.gridColor",      secondary.brighter());


       // 8) Table header styling
       UIManager.put("TableHeader.background", primary);
       UIManager.put("TableHeader.foreground", Color.WHITE);
       UIManager.put("TableHeader.font",       new FontUIResource("Segoe UI", Font.BOLD, 14));


       // 9) ScrollPane border
       Border scrollBorder = BorderFactory.createLineBorder(secondary);
       UIManager.put("ScrollPane.border", scrollBorder);


       // 10) TextField and FormattedTextField background overrides to avoid Nimbus blue tint
       UIManager.put("TextField.background",            Color.WHITE);
       UIManager.put("TextField[Enabled].background",  Color.WHITE);
       UIManager.put("TextField[Disabled].background", Color.LIGHT_GRAY);
       UIManager.put("FormattedTextField.background",           Color.WHITE);
       UIManager.put("FormattedTextField[Enabled].background", Color.WHITE);


       // 11) Ensure focus/popup backgrounds are also neutral
       UIManager.put("TextField.focusedBackground",   Color.WHITE);
       UIManager.put("FormattedTextField.focusedBackground", Color.WHITE);
   }


   /**
    * Usage in MainFrame:
    * public static void main(String[] args) {
    *     UIUtils.initUI();
    *     SwingUtilities.invokeLater(() -> new MainFrame().setVisible(true));
    * }
    */
}


