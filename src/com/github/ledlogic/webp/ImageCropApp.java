package com.github.ledlogic.webp;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * MapCrop - CLI tool to detect and crop map/floor-plan rectangles from
 * blueprint-style PNG files.
 *
 * Handles single maps (one rectangle) and side-by-side maps (two rectangles).
 * When two rectangles are found they are placed side-by-side in the output.
 *
 * Output file is named  <stem>t.png  (adds "t" before the extension).
 * An explicit output path can also be supplied as a second argument.
 *
 * Usage:
 *   java -jar ImageCropApp.jar <input.png> [output.png]
 */
public class ImageCropApp {

    // ── Orange detection thresholds ─────────────────────────────────────────
    static final int    ORANGE_R_MIN         = 150;
    static final int    ORANGE_G_MIN         =  60;
    static final int    ORANGE_G_MAX         = 175;
    static final int    ORANGE_B_MAX         = 110;

    // A border row needs at least this fraction of the HALF-width in orange
    // (works for both single and dual maps).
    static final double BORDER_DENSITY_ROW   = 0.45;
    // A border col needs at least this fraction of image height.
    static final double BORDER_DENSITY_COL   = 0.35;

    // Max pixel gap allowed inside a contiguous band.
    static final int    BAND_GAP             = 22;

    // How far above the top border to search for title text.
    static final int    TITLE_SEARCH_ROWS    = 55;

    // Padding added on bottom / sides (not top – title flush).
    static final int    PADDING              = 4;

    // ────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -jar ImageCropApp.jar <input.png> [output.png]");
            System.exit(1);
        }

        String inputPath  = args[0];
        // outputPath is used only for single-map mode; dual-map derives names automatically
        String outputPath = (args.length >= 2) ? args[1] : tSuffix(inputPath);

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.err.println("Error: file not found – " + inputPath);
            System.exit(1);
        }

        System.out.println("Reading: " + inputPath);
        BufferedImage img = ImageIO.read(inputFile);
        if (img == null) { System.err.println("Error: unreadable image"); System.exit(1); }

        int W = img.getWidth(), H = img.getHeight();
        System.out.printf("Image size: %d × %d px%n", W, H);

        // ── Build orange mask ────────────────────────────────────────────────
        boolean[][] orange = buildOrangeMask(img, W, H);

        int[] rowCount = new int[H];
        int[] colCount = new int[W];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                if (orange[y][x]) { rowCount[y]++; colCount[x]++; }

        // ── Detect map rectangles ───────────────────────────────────────────
        // Split into left / right halves so dual maps are found independently.
        int half = W / 2;

        int[] colCountLeft  = new int[half];
        int[] colCountRight = new int[W - half];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                if (orange[y][x]) {
                    if (x < half) colCountLeft[x]++;
                    else          colCountRight[x - half]++;
                }

        int[] rowCountLeft  = halfRowCount(orange, H, 0,    half);
        int[] rowCountRight = halfRowCount(orange, H, half, W);

        int rowThresh = (int)(half * BORDER_DENSITY_ROW);
        int colThresh = (int)(H    * BORDER_DENSITY_COL);

        List<int[]> rowBandsL = denseBands(rowCountLeft,  rowThresh, BAND_GAP);
        List<int[]> rowBandsR = denseBands(rowCountRight, rowThresh, BAND_GAP);
        List<int[]> colBandsL = denseBands(colCountLeft,  colThresh, BAND_GAP);
        List<int[]> colBandsR = denseBands(colCountRight, colThresh, BAND_GAP);

        // Offset right-half column indices back to full-image coords
        colBandsR.forEach(b -> { b[0] += half; b[1] += half; });

        System.out.println("Left  row bands: " + bandsStr(rowBandsL));
        System.out.println("Right row bands: " + bandsStr(rowBandsR));
        System.out.println("Left  col bands: " + bandsStr(colBandsL));
        System.out.println("Right col bands: " + bandsStr(colBandsR));

        // Decide: dual map or single map
        boolean dualMap = colBandsL.size() >= 2 && colBandsR.size() >= 2
                       && rowBandsL.size() >= 2 && rowBandsR.size() >= 2;
        boolean singleMap;
        List<int[]> colBandsAll, rowBandsAll;
        if (!dualMap) {
            // Fall back to full-width analysis
            colBandsAll = denseBands(colCount, (int)(H * 0.12), BAND_GAP);
            rowBandsAll = denseBands(rowCount, (int)(W * 0.55), BAND_GAP);
            singleMap = colBandsAll.size() >= 2 && rowBandsAll.size() >= 2;
        } else {
            colBandsAll = null; rowBandsAll = null; singleMap = false;
        }

        if (!dualMap && !singleMap) {
            System.err.println("Error: could not detect map rectangle(s).");
            System.exit(1);
        }

        if (dualMap) {
            System.out.println("Detected: DUAL MAP layout — saving two files");
            int titleTop = findTitleTop(rowCount, rowBandsL.get(0)[0]);

            int[] rectL = rect(rowBandsL, colBandsL, H, W, titleTop);
            int[] rectR = rect(rowBandsR, colBandsR, H, W, titleTop);
            printRect("Left map",  rectL);
            printRect("Right map", rectR);

            BufferedImage left  = crop(img, rectL);
            BufferedImage right = crop(img, rectR);

            // Derive two output paths: stem_1t.png and stem_2t.png
            String outL = dualOutPath(outputPath, 1);
            String outR = dualOutPath(outputPath, 2);
            ImageIO.write(left,  "PNG", new File(outL));
            ImageIO.write(right, "PNG", new File(outR));
            System.out.println("Saved [1]: " + outL + "  (" + left.getWidth()  + "×" + left.getHeight()  + ")");
            System.out.println("Saved [2]: " + outR + "  (" + right.getWidth() + "×" + right.getHeight() + ")");
        } else {
            System.out.println("Detected: SINGLE MAP layout");
            int titleTop = findTitleTop(rowCount, rowBandsAll.get(0)[0]);
            int[] rectS = rect(rowBandsAll, colBandsAll, H, W, titleTop);
            printRect("Map", rectS);
            BufferedImage output = crop(img, rectS);
            ImageIO.write(output, "PNG", new File(outputPath));
            System.out.println("Saved: " + outputPath + "  (" + output.getWidth() + "×" + output.getHeight() + ")");
        }
    }

    // ── Geometry helpers ─────────────────────────────────────────────────────

    /** Returns {x, y, w, h} crop rectangle from band lists. */
    static int[] rect(List<int[]> rowBands, List<int[]> colBands,
                      int H, int W, int titleTop) {
        int topRow    = rowBands.get(0)[0];
        int bottomRow = rowBands.get(rowBands.size() - 1)[1];
        int leftCol   = colBands.get(0)[0];
        int rightCol  = colBands.get(colBands.size() - 1)[1];

        int cropX = Math.max(0, leftCol  - PADDING);
        int cropY = Math.max(0, titleTop);
        int cropW = Math.min(W, rightCol  + PADDING + 1) - cropX;
        int cropH = Math.min(H, bottomRow + PADDING + 1) - cropY;
        return new int[]{cropX, cropY, cropW, cropH};
    }

    static BufferedImage crop(BufferedImage img, int[] r) {
        return img.getSubimage(r[0], r[1], r[2], r[3]);
    }

    /** stem_1t.png / stem_2t.png  (or  stem_1t.png if input already ends in t) */
    static String dualOutPath(String input, int n) {
        int dot = input.lastIndexOf('.');
        String stem = (dot >= 0) ? input.substring(0, dot) : input;
        String ext  = (dot >= 0) ? input.substring(dot)    : ".png";
        return stem + "_" + n + "t" + ext;
    }

    // ── Orange mask ──────────────────────────────────────────────────────────

    static boolean[][] buildOrangeMask(BufferedImage img, int W, int H) {
        boolean[][] mask = new boolean[H][W];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                mask[y][x] = r > ORANGE_R_MIN && g > ORANGE_G_MIN && g < ORANGE_G_MAX
                          && b < ORANGE_B_MAX  && r > g && r > b;
            }
        return mask;
    }

    static int[] halfRowCount(boolean[][] orange, int H, int xStart, int xEnd) {
        int[] rc = new int[H];
        for (int y = 0; y < H; y++)
            for (int x = xStart; x < xEnd; x++)
                if (orange[y][x]) rc[y]++;
        return rc;
    }

    // ── Band detection ───────────────────────────────────────────────────────

    static List<int[]> denseBands(int[] counts, int threshold, int maxGap) {
        List<int[]> bands = new ArrayList<>();
        int start = -1, last = -1;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] >= threshold) {
                if (start == -1) start = i;
                last = i;
            } else if (start != -1 && i - last > maxGap) {
                bands.add(new int[]{start, last});
                start = -1;
            }
        }
        if (start != -1) bands.add(new int[]{start, last});
        return bands;
    }

    static int findTitleTop(int[] rowCount, int topBorder) {
        int titleTop = topBorder;
        int blank = 0;
        for (int y = topBorder - 1; y >= 0; y--) {
            if (rowCount[y] > 0) { titleTop = y; blank = 0; }
            else if (++blank >= TITLE_SEARCH_ROWS) break;
        }
        return titleTop;
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    static String tSuffix(String path) {
        int dot = path.lastIndexOf('.');
        return (dot >= 0) ? path.substring(0, dot) + "t" + path.substring(dot)
                          : path + "t.png";
    }

    static String bandsStr(List<int[]> bands) {
        StringBuilder sb = new StringBuilder();
        bands.forEach(b -> sb.append(String.format("[%d-%d] ", b[0], b[1])));
        return sb.toString();
    }

    static void printRect(String label, int[] r) {
        System.out.printf("%s: x=%d y=%d w=%d h=%d%n", label, r[0], r[1], r[2], r[3]);
    }
}
