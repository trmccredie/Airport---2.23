package sim.ui;


import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;


import sim.model.Flight.ShapeType;


/**
* Utility class responsible for rendering passenger shapes with borders.
*/
public class ShapePainter {
   private ShapePainter() {}


   /**
    * Draws a filled shape with a thicker colored border.
    *
    * NOTE:
    * - Uses the CURRENT Graphics color for the fill.
    * - Uses borderColor for the outline.
    */
   public static void paintShape(Graphics g,
                                 ShapeType type,
                                 int x, int y,
                                 int w, int h,
                                 Color borderColor) {
       Graphics2D g2 = (Graphics2D) g;
       Color originalColor = g2.getColor();
       java.awt.Stroke originalStroke = g2.getStroke();


       // =========================
       // Fill
       // =========================
       switch (type) {
           case CIRCLE:
               g2.fillOval(x, y, w, h);
               break;


           case TRIANGLE: {
               int[] xs = { x + w / 2, x, x + w };
               int[] ys = { y, y + h, y + h };
               g2.fillPolygon(xs, ys, 3);
               break;
           }


           case SQUARE:
               g2.fillRect(x, y, w, h);
               break;


           case DIAMOND: {
               int[] xs = { x + w / 2, x + w, x + w / 2, x };
               int[] ys = { y, y + h / 2, y + h, y + h / 2 };
               g2.fillPolygon(xs, ys, 4);
               break;
           }


           case HEXAGON: {
               int cx = x + w / 2;
               int cy = y + h / 2;
               int r = Math.min(w, h) / 2;


               int[] xs = new int[6];
               int[] ys = new int[6];
               for (int i = 0; i < 6; i++) {
                   double angle = Math.PI / 6 + i * (Math.PI / 3); // flat-top
                   xs[i] = cx + (int) Math.round(Math.cos(angle) * r);
                   ys[i] = cy + (int) Math.round(Math.sin(angle) * r);
               }
               g2.fillPolygon(xs, ys, 6);
               break;
           }


           case STAR: {
               int cx = x + w / 2;
               int cy = y + h / 2;
               int rOuter = Math.min(w, h) / 2;
               int rInner = Math.max(1, rOuter / 2);


               int[] xs = new int[10];
               int[] ys = new int[10];
               for (int i = 0; i < 10; i++) {
                   double angle = -Math.PI / 2 + i * (Math.PI / 5); // start at top
                   int r = (i % 2 == 0) ? rOuter : rInner;
                   xs[i] = cx + (int) Math.round(Math.cos(angle) * r);
                   ys[i] = cy + (int) Math.round(Math.sin(angle) * r);
               }
               g2.fillPolygon(xs, ys, 10);
               break;
           }


           default:
               g2.fillOval(x, y, w, h);
       }


       // =========================
       // Border
       // =========================
       g2.setColor(borderColor);
       g2.setStroke(new BasicStroke(2.5f));


       switch (type) {
           case CIRCLE:
               g2.drawOval(x, y, w, h);
               break;


           case TRIANGLE: {
               int[] xs = { x + w / 2, x, x + w };
               int[] ys = { y, y + h, y + h };
               g2.drawPolygon(xs, ys, 3);
               break;
           }


           case SQUARE:
               g2.drawRect(x, y, w, h);
               break;


           case DIAMOND: {
               int[] xs = { x + w / 2, x + w, x + w / 2, x };
               int[] ys = { y, y + h / 2, y + h, y + h / 2 };
               g2.drawPolygon(xs, ys, 4);
               break;
           }


           case HEXAGON: {
               int cx = x + w / 2;
               int cy = y + h / 2;
               int r = Math.min(w, h) / 2;


               int[] xs = new int[6];
               int[] ys = new int[6];
               for (int i = 0; i < 6; i++) {
                   double angle = Math.PI / 6 + i * (Math.PI / 3);
                   xs[i] = cx + (int) Math.round(Math.cos(angle) * r);
                   ys[i] = cy + (int) Math.round(Math.sin(angle) * r);
               }
               g2.drawPolygon(xs, ys, 6);
               break;
           }


           case STAR: {
               int cx = x + w / 2;
               int cy = y + h / 2;
               int rOuter = Math.min(w, h) / 2;
               int rInner = Math.max(1, rOuter / 2);


               int[] xs = new int[10];
               int[] ys = new int[10];
               for (int i = 0; i < 10; i++) {
                   double angle = -Math.PI / 2 + i * (Math.PI / 5);
                   int r = (i % 2 == 0) ? rOuter : rInner;
                   xs[i] = cx + (int) Math.round(Math.cos(angle) * r);
                   ys[i] = cy + (int) Math.round(Math.sin(angle) * r);
               }
               g2.drawPolygon(xs, ys, 10);
               break;
           }


           default:
               g2.drawOval(x, y, w, h);
       }


       // Reset graphics state
       g2.setStroke(originalStroke);
       g2.setColor(originalColor);
   }
}
