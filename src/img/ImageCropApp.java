package img;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * ImageCropApp - CLI tool to detect and crop map/floor-plan rectangles from
 * blueprint-style PNG files.
 *
 * Handles single maps (one rectangle) and side-by-side maps (two rectangles).
 * When two rectangles are found they are saved as separate files.
 *
 * Output files are named <stem>t.png (single) or <stem>_1t.png / <stem>_2t.png (dual),
 * saved in the same directory as the source image.
 *
 * Usage:
 *   java -jar ImageCropApp.jar <input.png>          process one file
 *   java -jar ImageCropApp.jar <folder>             process all *.png in folder
 *                                                   (skips files ending in t.png)
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
            System.err.println("Usage: java -jar ImageCropApp.jar <input.png|folder>");
            System.exit(1);
        }

        File input = new File(args[0]);
        if (!input.exists()) {
            System.err.println("Error: path not found – " + args[0]);
            System.exit(1);
        }

        if (input.isDirectory()) {
            File[] pngs = input.listFiles(f ->
                f.isFile()
                && f.getName().toLowerCase().endsWith(".png")
                && !f.getName().toLowerCase().endsWith("t.png"));
            if (pngs == null || pngs.length == 0) {
                System.out.println("No eligible PNG files found in: " + input.getAbsolutePath());
                return;
            }
            java.util.Arrays.sort(pngs);
            System.out.printf("Found %d PNG file(s) to process in: %s%n",
                    pngs.length, input.getAbsolutePath());
            int ok = 0, failed = 0;
            for (File f : pngs) {
                System.out.println("\n── " + f.getName() + " ──");
                try {
                    processFile(f);
                    ok++;
                } catch (Exception e) {
                    System.err.println("  FAILED: " + e.getMessage());
                    failed++;
                }
            }
            System.out.printf("%nDone: %d succeeded, %d failed.%n", ok, failed);
        } else {
            processFile(input);
        }
    }

    static void processFile(File inputFile) throws Exception {
        String inputPath = inputFile.getAbsolutePath();
        String outputPath = tSuffix(inputPath);

        System.out.println("Reading: " + inputPath);
        BufferedImage img = readRaw(inputFile);
        if (img == null) { System.err.println("Error: unreadable image"); return; }

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

        // ── Try progressively lower thresholds until detection succeeds ────────
        // This handles ICC-profile colour shifts across JVM versions and OS platforms.
        double[] rowDensities = { BORDER_DENSITY_ROW, 0.35, 0.25, 0.15 };
        double[] colDensities = { BORDER_DENSITY_COL, 0.25, 0.15, 0.08 };

        List<int[]> rowBandsL = null, rowBandsR = null,
                    colBandsL = null, colBandsR = null,
                    colBandsAll = null, rowBandsAll = null;
        boolean dualMap = false, singleMap = false;

        outer:
        for (double rd : rowDensities) {
            for (double cd : colDensities) {
                int rowThresh = (int)(half * rd);
                int colThresh = (int)(H    * cd);

                List<int[]> rL = denseBands(rowCountLeft,  rowThresh, BAND_GAP);
                List<int[]> rR = denseBands(rowCountRight, rowThresh, BAND_GAP);
                List<int[]> cL = denseBands(colCountLeft,  colThresh, BAND_GAP);
                List<int[]> cR = denseBands(colCountRight, colThresh, BAND_GAP);
                cR.forEach(b -> { b[0] += half; b[1] += half; });

                if (cL.size() >= 2 && cR.size() >= 2 && rL.size() >= 2 && rR.size() >= 2) {
                    rowBandsL = rL; rowBandsR = rR; colBandsL = cL; colBandsR = cR;
                    dualMap = true;
                    System.out.printf("Thresholds: row=%.0f%% col=%.0f%%%n", rd*100, cd*100);
                    break outer;
                }

                // Single-map fallback
                List<int[]> cAll = denseBands(colCount, (int)(H * cd),        BAND_GAP);
                List<int[]> rAll = denseBands(rowCount, (int)(W * rd * 1.22), BAND_GAP);
                if (cAll.size() >= 2 && rAll.size() >= 2) {
                    colBandsAll = cAll; rowBandsAll = rAll;
                    singleMap = true;
                    System.out.printf("Thresholds (single): row=%.0f%% col=%.0f%%%n", rd*122, cd*100);
                    break outer;
                }
            }
        }

        System.out.println("Left  row bands: " + (rowBandsL  != null ? bandsStr(rowBandsL)  : "—"));
        System.out.println("Right row bands: " + (rowBandsR  != null ? bandsStr(rowBandsR)  : "—"));
        System.out.println("Left  col bands: " + (colBandsL  != null ? bandsStr(colBandsL)  : "—"));
        System.out.println("Right col bands: " + (colBandsR  != null ? bandsStr(colBandsR)  : "—"));

        if (!dualMap && !singleMap) {
            System.err.println("Error: could not detect map rectangle(s).");
            System.err.println("Tip: run with --debug to see orange pixel diagnostics.");
            // Print diagnostics unconditionally so the user can report them
            int maxR=0; for(int v:rowCount) if(v>maxR) maxR=v;
            int maxC=0; for(int v:colCount) if(v>maxC) maxC=v;
            System.err.printf("  Max orange pixels per row: %d (need ~%d for single, ~%d for dual)%n",
                    maxR, (int)(W*0.12), (int)(half*0.15));
            System.err.printf("  Max orange pixels per col: %d (need ~%d)%n",
                    maxC, (int)(H*0.08));
            System.err.printf("  Image: %dx%d  Orange mask R>%d G=%d-%d B<%d%n",
                    W, H, ORANGE_R_MIN, ORANGE_G_MIN, ORANGE_G_MAX, ORANGE_B_MAX);
            System.exit(1);
        }

        if (dualMap) {
            System.out.println("Detected: DUAL MAP layout — saving two files");
            int[] rectL = rect(rowBandsL, colBandsL, H, W);
            int[] rectR = rect(rowBandsR, colBandsR, H, W);
            printRect("Left map",  rectL);
            printRect("Right map", rectR);

            BufferedImage left  = crop(img, rectL);
            BufferedImage right = crop(img, rectR);

            // Derive two output paths: stem_1t.png and stem_2t.png
            String outL = dualOutPath(inputPath, 1);
            String outR = dualOutPath(inputPath, 2);
            ImageIO.write(left,  "PNG", new File(outL));
            ImageIO.write(right, "PNG", new File(outR));
            System.out.println("Saved [1]: " + outL + "  (" + left.getWidth()  + "×" + left.getHeight()  + ")");
            System.out.println("Saved [2]: " + outR + "  (" + right.getWidth() + "×" + right.getHeight() + ")");
        } else {
            System.out.println("Detected: SINGLE MAP layout");
            int[] rectS = rect(rowBandsAll, colBandsAll, H, W);
            printRect("Map", rectS);
            BufferedImage output = crop(img, rectS);
            ImageIO.write(output, "PNG", new File(outputPath));
            System.out.println("Saved: " + outputPath + "  (" + output.getWidth() + "×" + output.getHeight() + ")");
        }
    }

    // ── Geometry helpers ─────────────────────────────────────────────────────

    /** Returns {x, y, w, h} crop rectangle from band lists.
     *  Uses the pair of THICK bands (>=3px) with the largest gap between them
     *  as the outer rectangle — this ignores thin stray bands from notes/text
     *  outside the map grid. */
    static int[] rect(List<int[]> rowBands, List<int[]> colBands, int H, int W) {
        int[] topRowBand    = outerBand(rowBands, true);
        int[] bottomRowBand = outerBand(rowBands, false);
        int[] leftColBand   = outerBand(colBands, true);
        int[] rightColBand  = outerBand(colBands, false);

        int cropX = Math.max(0, leftColBand[0]);
        int cropY = Math.max(0, topRowBand[0]);
        int cropW = Math.min(W, rightColBand[1] + 1) - cropX;
        int cropH = Math.min(H, bottomRowBand[1] + 1) - cropY;
        return new int[]{cropX, cropY, cropW, cropH};
    }

    /**
     * From a list of bands, selects the top (first=true) or bottom (first=false)
     * band of the pair that has the largest gap between them, considering only
     * bands with thickness >= MIN_BORDER_THICKNESS to ignore stray thin marks.
     */
    static final int MIN_BORDER_THICKNESS = 3;

    static int[] outerBand(List<int[]> bands, boolean wantFirst) {
        // Filter to thick bands only
        List<int[]> thick = new ArrayList<>();
        for (int[] b : bands)
            if (b[1] - b[0] + 1 >= MIN_BORDER_THICKNESS) thick.add(b);
        if (thick.isEmpty()) thick = bands; // fallback: use all bands

        // Find the pair with the largest gap
        int bestGap = -1, bestI = 0, bestJ = thick.size() - 1;
        for (int i = 0; i < thick.size(); i++)
            for (int j = i + 1; j < thick.size(); j++) {
                int gap = thick.get(j)[0] - thick.get(i)[1];
                if (gap > bestGap) { bestGap = gap; bestI = i; bestJ = j; }
            }
        return wantFirst ? thick.get(bestI) : thick.get(bestJ);
    }

    static BufferedImage crop(BufferedImage img, int[] r) {
        return img.getSubimage(r[0], r[1], r[2], r[3]);
    }

    // ── Orange mask ──────────────────────────────────────────────────────────

    /**
     * Reads a PNG bypassing embedded ICC profile colour conversion, which some
     * JVM versions (e.g. JDK 21.0.1) apply during ImageIO.read(), shifting
     * pixel values and breaking the orange threshold checks.
     * Forces result into plain TYPE_INT_RGB so values match the raw file bytes.
     */
    static BufferedImage readRaw(File file) throws Exception {
        try (javax.imageio.stream.ImageInputStream iis =
                     ImageIO.createImageInputStream(file)) {
            java.util.Iterator<javax.imageio.ImageReader> readers =
                    ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return null;

            javax.imageio.ImageReader reader = readers.next();
            reader.setInput(iis, true, true); // ignoreMetadata=true skips ICC

            javax.imageio.ImageReadParam param = reader.getDefaultReadParam();
            param.setDestinationType(
                    javax.imageio.ImageTypeSpecifier.createFromBufferedImageType(
                            BufferedImage.TYPE_INT_RGB));

            BufferedImage raw;
            try {
                raw = reader.read(0, param);
            } catch (Exception e) {
                raw = reader.read(0); // fallback if decoder ignores dest type
            }
            reader.dispose();

            // Ensure TYPE_INT_RGB (some decoders ignore the hint above)
            if (raw.getType() != BufferedImage.TYPE_INT_RGB) {
                BufferedImage dst = new BufferedImage(
                        raw.getWidth(), raw.getHeight(), BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = dst.createGraphics();
                g.drawImage(raw, 0, 0, null);
                g.dispose();
                raw = dst;
            }
            return raw;
        }
    }

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

    /** Derives the output path in the same directory as the source file. */
    static String tSuffix(String inputPath) {
        File f      = new File(inputPath).getAbsoluteFile();
        String name = f.getName();
        int dot     = name.lastIndexOf('.');
        String out  = (dot >= 0) ? name.substring(0, dot) + "t" + name.substring(dot)
                                 : name + "t.png";
        return new File(f.getParent(), out).getPath();
    }

    /** stem_1t.png / stem_2t.png saved beside the source file. */
    static String dualOutPath(String inputPath, int n) {
        File f      = new File(inputPath).getAbsoluteFile();
        String name = f.getName();
        int dot     = name.lastIndexOf('.');
        String stem = (dot >= 0) ? name.substring(0, dot) : name;
        String ext  = (dot >= 0) ? name.substring(dot)    : ".png";
        return new File(f.getParent(), stem + "_" + n + "t" + ext).getPath();
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
