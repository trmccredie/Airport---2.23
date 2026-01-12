package sim.floorplan.io;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class PdfFloorplanImporter {

    /**
     * Render a single PDF page to a BufferedImage at the requested DPI.
     *
     * @param pdfFile   PDF file
     * @param pageIndex 0-based page index
     * @param dpi       e.g. 150 / 200 / 300
     */
    public static BufferedImage renderPage(File pdfFile, int pageIndex, int dpi) throws IOException {
        if (pdfFile == null) throw new IllegalArgumentException("pdfFile is null");
        if (!pdfFile.exists()) throw new IllegalArgumentException("PDF does not exist: " + pdfFile.getAbsolutePath());
        if (dpi <= 0) throw new IllegalArgumentException("dpi must be > 0");

        try (PDDocument doc = PDDocument.load(pdfFile)) {
            int pageCount = doc.getNumberOfPages();
            if (pageCount <= 0) throw new IllegalArgumentException("PDF has no pages.");

            int idx = Math.max(0, Math.min(pageIndex, pageCount - 1));

            PDFRenderer renderer = new PDFRenderer(doc);

            // RGB is fine for floorplans; ARGB increases memory
            return renderer.renderImageWithDPI(idx, dpi, ImageType.RGB);
        }
    }
}