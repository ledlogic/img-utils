package com.github.ledlogic.imgutils;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;

import javax.imageio.ImageIO;

/**
 * ImageColorApp – replaces near-black pixels in a PNG (or folder of PNGs)
 * with the given hex color.
 *
 * Usage:
 *   java ImageColorApp <input.png|folder> <hexcolor>
 *
 * Examples:
 *   java ImageColorApp map.png FF0000           # single file → map_FF0000.png
 *   java ImageColorApp map.png #3A7BD5          # leading # is optional
 *   java ImageColorApp ./maps  FF0000           # folder → ./maps/FF0000/*.png
 *
 * Single-file output:  <basename>_<HEXCOLOR>.png  (same directory as input)
 * Folder output:       <folder>/<HEXCOLOR>/<originalname>.png
 *
 * "Black" is defined as any pixel whose R, G, and B values are all
 * below the threshold (default 30/255).  Alpha is preserved.
 */
public class ImageColorApp {

    /** Pixels with every channel below this value are treated as black. */
    private static final int BLACK_THRESHOLD = 30;

    public static void main(String[] args) throws Exception {

        // ── argument validation ──────────────────────────────────────────────
        if (args.length != 2) {
            System.err.println("Usage: java ImageColorApp <input.png|folder> <hexcolor>");
            System.err.println("Examples:");
            System.err.println("  java ImageColorApp map.png FF0000");
            System.err.println("  java ImageColorApp ./maps  FF0000");
            System.exit(1);
        }

        String inputPath = args[0];
        String hexRaw    = args[1].replaceFirst("^#", "").toUpperCase();

        if (!hexRaw.matches("[0-9A-F]{6}")) {
            System.err.println("Error: hex color must be 6 hex digits (e.g. FF0000 or #FF0000).");
            System.exit(1);
        }

        File input = new File(inputPath);
        if (!input.exists()) {
            System.err.println("Error: path not found: " + inputPath);
            System.exit(1);
        }

        if (input.isDirectory()) {
            processFolder(input, hexRaw);
        } else {
            processSingleFile(input, hexRaw);
        }
    }

    // ── folder mode ──────────────────────────────────────────────────────────

    private static void processFolder(File folder, String hexRaw) throws Exception {
        // Collect all PNGs directly in the folder (non-recursive)
        File[] pngFiles = folder.listFiles(
                f -> f.isFile() && f.getName().toLowerCase().endsWith(".png"));

        if (pngFiles == null || pngFiles.length == 0) {
            System.out.println("No PNG files found in: " + folder.getPath());
            return;
        }

        // Sort for deterministic output order
        Arrays.sort(pngFiles);

        // Create <folder>/<HEXCOLOR>/ output directory
        File outDir = new File(folder, hexRaw);
        if (!outDir.exists()) {
            if (!outDir.mkdirs()) {
                System.err.println("Error: could not create output directory: " + outDir.getPath());
                System.exit(1);
            }
        }

        System.out.printf("Processing %d PNG file(s) → %s%n%n", pngFiles.length, outDir.getPath());

        int filesDone = 0;
        for (File png : pngFiles) {
            File outFile = new File(outDir, png.getName());
            int replaced = recolor(png, outFile, hexRaw);
            System.out.printf("  %-30s  →  %,d pixel(s) replaced%n", png.getName(), replaced);
            filesDone++;
        }

        System.out.printf("%nDone. Processed %d file(s). Output in: %s%n", filesDone, outDir.getPath());
    }

    // ── single-file mode ─────────────────────────────────────────────────────

    private static void processSingleFile(File inputFile, String hexRaw) throws Exception {
        String name   = inputFile.getName();
        String base   = name.contains(".")
                ? name.substring(0, name.lastIndexOf('.'))
                : name;
        String parent = inputFile.getParent();
        String outName = base + "_" + hexRaw + ".png";
        File   outFile = (parent != null) ? new File(parent, outName) : new File(outName);

        int replaced = recolor(inputFile, outFile, hexRaw);
        System.out.printf("Done. Replaced %,d black pixel(s) with #%s.%n", replaced, hexRaw);
        System.out.printf("Output: %s%n", outFile.getPath());
    }

    // ── core recolor logic ───────────────────────────────────────────────────

    /**
     * Reads {@code src}, replaces near-black pixels with the color described by
     * {@code hexRaw} (6 uppercase hex digits, no #), writes the result to
     * {@code dst}, and returns the number of pixels replaced.
     */
    private static int recolor(File src, File dst, String hexRaw) throws Exception {
        int targetR = Integer.parseInt(hexRaw.substring(0, 2), 16);
        int targetG = Integer.parseInt(hexRaw.substring(2, 4), 16);
        int targetB = Integer.parseInt(hexRaw.substring(4, 6), 16);

        BufferedImage srcImg = ImageIO.read(src);
        if (srcImg == null) {
            System.err.println("  Warning: could not read image, skipping: " + src.getName());
            return 0;
        }

        int width  = srcImg.getWidth();
        int height = srcImg.getHeight();
        BufferedImage dstImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        int replaced = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb  = srcImg.getRGB(x, y);
                int alpha = (argb >> 24) & 0xFF;
                int r     = (argb >> 16) & 0xFF;
                int g     = (argb >>  8) & 0xFF;
                int b     =  argb        & 0xFF;

                if (r < BLACK_THRESHOLD && g < BLACK_THRESHOLD && b < BLACK_THRESHOLD) {
                    int newArgb = (alpha << 24) | (targetR << 16) | (targetG << 8) | targetB;
                    dstImg.setRGB(x, y, newArgb);
                    replaced++;
                } else {
                    dstImg.setRGB(x, y, argb);
                }
            }
        }

        ImageIO.write(dstImg, "PNG", dst);
        return replaced;
    }
}