package sim.floorplan.io;

import sim.floorplan.model.FloorplanProject;
import sim.floorplan.model.WalkMask;
import sim.floorplan.model.Zone;
import sim.floorplan.model.ZoneType;

import javax.imageio.ImageIO;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class FloorplanProjectIO {

    private static final String ENTRY_PROPS = "project.properties";
    private static final String ENTRY_IMAGE = "floorplan.png";
    private static final String ENTRY_MASK  = "mask.png";
    private static final String ENTRY_ZONES = "zones.tsv";

    private FloorplanProjectIO() {}

    public static void saveToFile(FloorplanProject p, File file) throws IOException {
        if (p == null) throw new IllegalArgumentException("project is null");
        if (file == null) throw new IllegalArgumentException("file is null");

        if (!file.getName().toLowerCase().endsWith(".fsp")) {
            file = new File(file.getParentFile(), file.getName() + ".fsp");
        }

        BufferedImage img = p.getFloorplanImage();
        WalkMask mask = p.getMask();
        if (img == null) throw new IllegalStateException("Project has no floorplan image.");
        if (mask == null) throw new IllegalStateException("Project has no mask.");

        Properties props = new Properties();
        props.setProperty("version", "1");
        props.setProperty("pageIndex", Integer.toString(p.getPageIndex()));
        props.setProperty("dpi", p.getDpi() == null ? "" : p.getDpi().toString());
        props.setProperty("pdfPath", p.getPdfFile() == null ? "" : p.getPdfFile().getAbsolutePath());
        props.setProperty("imageWidth", Integer.toString(img.getWidth()));
        props.setProperty("imageHeight", Integer.toString(img.getHeight()));

        byte[] propsBytes = propsToBytes(props);
        byte[] imgBytes = imageToPngBytes(img);
        byte[] maskBytes = imageToPngBytes(mask.toBinaryImage());
        byte[] zonesBytes = zonesToTsvBytes(p.getZones());

        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            writeEntry(zos, ENTRY_PROPS, propsBytes);
            writeEntry(zos, ENTRY_IMAGE, imgBytes);
            writeEntry(zos, ENTRY_MASK, maskBytes);
            writeEntry(zos, ENTRY_ZONES, zonesBytes);
        }
    }

    public static FloorplanProject loadFromFile(File file) throws IOException {
        if (file == null) throw new IllegalArgumentException("file is null");
        if (!file.exists()) throw new FileNotFoundException(file.getAbsolutePath());

        byte[] propsBytes = null;
        byte[] imgBytes = null;
        byte[] maskBytes = null;
        byte[] zonesBytes = null;

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                byte[] data = readAllBytes(zis);
                if (ENTRY_PROPS.equals(e.getName())) propsBytes = data;
                else if (ENTRY_IMAGE.equals(e.getName())) imgBytes = data;
                else if (ENTRY_MASK.equals(e.getName())) maskBytes = data;
                else if (ENTRY_ZONES.equals(e.getName())) zonesBytes = data;
                zis.closeEntry();
            }
        }

        if (propsBytes == null || imgBytes == null || maskBytes == null) {
            throw new IOException("Invalid .fsp file: missing required entries.");
        }

        Properties props = bytesToProps(propsBytes);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(imgBytes));
        BufferedImage maskImg = ImageIO.read(new ByteArrayInputStream(maskBytes));
        if (img == null) throw new IOException("Failed to decode floorplan.png");
        if (maskImg == null) throw new IOException("Failed to decode mask.png");

        WalkMask mask = WalkMask.fromBinaryImage(maskImg);

        FloorplanProject p = new FloorplanProject();
        p.setFloorplanImage(img);
        p.setMask(mask);

        // optional metadata
        p.setPageIndex(parseIntSafe(props.getProperty("pageIndex"), 0));
        String dpiStr = props.getProperty("dpi");
        p.setDpi((dpiStr == null || dpiStr.isBlank()) ? null : parseIntSafe(dpiStr, 200));

        String pdfPath = props.getProperty("pdfPath");
        if (pdfPath != null && !pdfPath.isBlank()) {
            File pdf = new File(pdfPath);
            if (pdf.exists()) p.setPdfFile(pdf); // only set if present
        }

        if (zonesBytes != null) {
            List<Zone> zones = zonesFromTsv(new String(zonesBytes, StandardCharsets.UTF_8));
            p.setZones(zones);
        }

        return p;
    }

    // =========================
    // Zip helpers
    // =========================

    private static void writeEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    // =========================
    // Properties helpers
    // =========================

    private static byte[] propsToBytes(Properties props) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        props.store(baos, "FloorplanProject");
        return baos.toByteArray();
    }

    private static Properties bytesToProps(byte[] bytes) throws IOException {
        Properties p = new Properties();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            p.load(bais);
        }
        return p;
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception ignored) { return def; }
    }

    // =========================
    // Image helpers
    // =========================

    private static byte[] imageToPngBytes(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", baos)) {
            throw new IOException("PNG writer not available");
        }
        return baos.toByteArray();
    }

    // =========================
    // Zones TSV
    // =========================
    // type \t id \t ax \t ay \t polyPoints
    // polyPoints = "x,y;x,y;..."
    private static byte[] zonesToTsvBytes(List<Zone> zones) {
        StringBuilder sb = new StringBuilder();
        if (zones != null) {
            for (Zone z : zones) {
                if (z == null || z.getType() == null) continue;

                String type = z.getType().name();
                String id = z.getId() == null ? "" : z.getId();

                int ax = -1, ay = -1;
                if (z.getAnchor() != null) {
                    ax = z.getAnchor().x;
                    ay = z.getAnchor().y;
                }

                String poly = "";
                if (z.getArea() != null && z.getArea().npoints >= 3) {
                    StringBuilder ps = new StringBuilder();
                    Polygon p = z.getArea();
                    for (int i = 0; i < p.npoints; i++) {
                        if (i > 0) ps.append(';');
                        ps.append(p.xpoints[i]).append(',').append(p.ypoints[i]);
                    }
                    poly = ps.toString();
                }

                sb.append(type).append('\t')
                  .append(id).append('\t')
                  .append(ax).append('\t')
                  .append(ay).append('\t')
                  .append(poly)
                  .append('\n');
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<Zone> zonesFromTsv(String text) {
        List<Zone> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;

        String[] lines = text.split("\\R");
        for (String line : lines) {
            if (line == null) continue;
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\t", -1);
            if (parts.length < 5) continue;

            ZoneType type;
            try { type = ZoneType.valueOf(parts[0]); }
            catch (Exception ignored) { continue; }

            String id = parts[1];

            int ax = parseIntSafe(parts[2], -1);
            int ay = parseIntSafe(parts[3], -1);

            String polyStr = parts[4];

            Zone z = new Zone(id, type);

            if (ax >= 0 && ay >= 0) z.setAnchor(new Point(ax, ay));

            if (polyStr != null && !polyStr.isBlank()) {
                Polygon poly = new Polygon();
                String[] pts = polyStr.split(";");
                for (String pt : pts) {
                    String[] xy = pt.split(",");
                    if (xy.length != 2) continue;
                    int x = parseIntSafe(xy[0], -1);
                    int y = parseIntSafe(xy[1], -1);
                    if (x >= 0 && y >= 0) poly.addPoint(x, y);
                }
                if (poly.npoints >= 3) z.setArea(poly);
            }

            out.add(z);
        }
        return out;
    }
}