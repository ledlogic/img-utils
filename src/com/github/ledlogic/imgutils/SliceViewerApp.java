package com.github.ledlogic.imgutils;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * SliceViewerApp — CLI tool to extract preview thumbnails from resin slicer
 * files and save them as JPEGs.
 *
 * Supported input formats:
 *   Anycubic Photon Workshop: .pm4n .pm4u .pm3n .pm3 .pm3m .pm3r .pm5 .pm5s
 *                             .pm7 .pm7m .pwmb .pwms .pwmx .pwmo .pws .pw0
 *                             .pwx .dlp .dl2p .pwsq .pwc
 *   Lychee Slicer:            .lys
 *
 * Usage:
 *   java SliceViewerApp [options] <file|dir> [<file|dir> ...]
 *
 * Options:
 *   --output-dir <dir>      Write all JPEGs to this directory (default: beside source).
 *   --quality <0-100>       JPEG quality (default: 90).
 *   --bg <rrggbb>           Background color for alpha compositing, hex RGB (default: 000000).
 *   --force                 Re-export even if the JPEG already exists.
 *   --no-recurse            Do not recurse into sub-directories.
 *   --verbose               Print skipped files too.
 *   --help                  Show this help and exit.
 *
 * Output file name:
 *   <source-stem>.preview.jpg  (e.g. my_model.pm4n -> my_model.preview.jpg)
 *
 * Examples:
 *   java SliceViewerApp ~/prints/
 *   java SliceViewerApp --output-dir ./previews --bg ffffff ~/prints/
 *   java SliceViewerApp --quality 85 --force --bg 1a1a2e model.pm4n
 *   java SliceViewerApp --no-recurse --verbose /Volumes/USB/
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * FILE FORMAT NOTES
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * ── Anycubic Photon Workshop (.pm4n and relatives) ──────────────────────────
 *
 * All integers are little-endian.
 *
 * File header (20 bytes):
 *   [0..11]  ASCII magic "ANYCUBIC\0\0\0\0"
 *   [12..15] uint32 version  (e.g. 517)
 *   [16..19] uint32 nsec     (number of named sections)
 *
 * Section offset table (nsec × 4 bytes, starting at byte 20):
 *   Each uint32 is an absolute file offset pointing to a section block.
 *
 * Section block layout at each offset:
 *   [0..11]  12-byte ASCII tag, zero-padded ("HEADER", "PREVIEW", "LAYERDEF", …)
 *   [12..15] uint32 section data length
 *   [16..]   section data bytes
 *
 * PREVIEW section data:
 *   [0..3]   uint32 width
 *   [4..7]   uint32 height
 *   [8..11]  uint32 unknown field (observed: 0xA8 = 168; ignored)
 *   [12..]   raw RGB565 LE pixels, width × height × 2 bytes
 *
 * RGB565 decode: for each uint16 word p:
 *   R5 = (p >> 11) & 0x1F,  R8 = (R5 << 3) | (R5 >> 2)
 *   G6 = (p >>  5) & 0x3F,  G8 = (G6 << 2) | (G6 >> 4)
 *   B5 =  p        & 0x1F,  B8 = (B5 << 3) | (B5 >> 2)
 *
 * ── Lychee Slicer (.lys) ────────────────────────────────────────────────────
 *
 * Custom concatenated-blob container (NOT a ZIP).
 *
 * File layout:
 *   [0..3]   uint32 = 4  (constant header field count)
 *   [4..7]   uint32 (json_length + 8)
 *   [8..11]  uint32 (json_length + 4)
 *   [12..15] uint32 json_length
 *   [16 .. 16+json_length-1]  UTF-8 JSON manifest
 *   [16+json_length .. EOF]   concatenated binary file blobs (data section)
 *
 * JSON manifest (subset relevant to preview extraction):
 *   { "mangoFiles": {
 *       "preview.png": { "size": <bytes>, "offset": "<int>", ... },
 *       ...
 *   } }
 * Each "offset" is relative to the start of the data section.
 * "preview.png" is a PNG (possibly RGBA) and is always preferred.
 */
public class SliceViewerApp {

    // =========================================================================
    //  Supported extensions
    // =========================================================================

    private static final Set<String> SLICER_EXTS = new HashSet<>(Arrays.asList(
        ".pm4n", ".pm4u",
        ".pm3n", ".pm3",  ".pm3m", ".pm3r",
        ".pm5",  ".pm5s",
        ".pm7",  ".pm7m",
        ".pwmb", ".pwms", ".pwmx", ".pwmo",
        ".pws",  ".pw0",  ".pwx",
        ".dlp",  ".dl2p",
        ".pwsq", ".pwc",
        ".lys"
    ));

    // =========================================================================
    //  Configuration
    // =========================================================================

    private Path    outputDir   = null;         // null → beside source file
    private float   jpegQuality = 0.90f;
    private Color   bgColor     = Color.BLACK;  // used when flattening alpha
    private boolean force       = false;
    private boolean recurse     = true;
    private boolean verbose     = false;
    private Path    htmlOutput  = null;          // null → no HTML report
    private final java.util.List<Path> roots = new ArrayList<>();
    // ordered list of (stem, jpegPath) entries for the HTML report
    private final java.util.List<String[]> htmlEntries = new ArrayList<>();

    // =========================================================================
    //  Counters
    // =========================================================================

    private final AtomicInteger found    = new AtomicInteger();
    private final AtomicInteger exported = new AtomicInteger();
    private final AtomicInteger skipped  = new AtomicInteger();
    private final AtomicInteger errors   = new AtomicInteger();

    // =========================================================================
    //  Entry point
    // =========================================================================

    public static void main(String[] args) {
        new SliceViewerApp().run(args);
    }

    private void run(String[] args) {
        if (!parseArgs(args)) { System.exit(1); return; }
        if (roots.isEmpty())  { printHelp();    System.exit(0); return; }

        if (outputDir != null) {
            try { Files.createDirectories(outputDir); }
            catch (IOException e) {
                err("Cannot create output directory: " + outputDir + " — " + e.getMessage());
                System.exit(1);
            }
        }

        for (Path root : roots) {
            if (!Files.exists(root)) {
                err("Path not found: " + root);
            } else if (Files.isRegularFile(root)) {
                processFile(root);
            } else if (Files.isDirectory(root)) {
                walkDirectory(root);
            } else {
                err("Not a file or directory: " + root);
            }
        }

        if (htmlOutput != null && !htmlEntries.isEmpty()) {
            // Resolve the HTML output path now that we know where the JPEGs landed.
            // If --html was given without an explicit filename, place previews.html
            // in the same directory as the first exported JPEG (which is either
            // --output-dir, or the source file's own directory).
            if (htmlOutput.getNameCount() == 1 && htmlOutput.toString().equals("previews.html")) {
                Path firstJpeg = Paths.get(htmlEntries.get(0)[1]);
                Path jpegDir = firstJpeg.isAbsolute()
                        ? firstJpeg.getParent()
                        : Paths.get(htmlEntries.get(0)[2]);  // absolute dir stored at index 2
                htmlOutput = jpegDir.resolve("previews.html");
            }
            try {
                writeHtmlReport(htmlOutput, htmlEntries);
                System.out.println("  HTML  " + htmlOutput);
            } catch (IOException e) {
                err("Could not write HTML report: " + e.getMessage());
            }
        }

        System.out.printf("%nDone.  Found: %d  |  Exported: %d  |  Skipped: %d  |  Errors: %d%n",
            found.get(), exported.get(), skipped.get(), errors.get());

        if (errors.get() > 0) System.exit(2);
    }

    // =========================================================================
    //  Argument parsing
    // =========================================================================

    private boolean parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help": case "-h":
                    printHelp(); System.exit(0); return false;

                case "--force":
                    force = true; break;

                case "--recurse":
                    recurse = true; break;

                case "--no-recurse":
                    recurse = false; break;

                case "--verbose": case "-v":
                    verbose = true; break;

                case "--output-dir": case "-o":
                    if (++i >= args.length) { err("--output-dir requires a path"); return false; }
                    outputDir = Paths.get(args[i]);
                    break;

                case "--quality": case "-q":
                    if (++i >= args.length) { err("--quality requires a value 0-100"); return false; }
                    try {
                        int q = Integer.parseInt(args[i]);
                        if (q < 0 || q > 100) throw new NumberFormatException();
                        jpegQuality = q / 100f;
                    } catch (NumberFormatException e) {
                        err("--quality must be an integer 0-100, got: " + args[i]); return false;
                    }
                    break;

                case "--bg":
                    if (++i >= args.length) { err("--bg requires a hex color (e.g. ff0000)"); return false; }
                    try {
                        bgColor = parseHexColor(args[i]);
                    } catch (IllegalArgumentException e) {
                        err("--bg: " + e.getMessage()); return false;
                    }
                    break;

                case "--html":
                    // Optional value: consume the next arg as the HTML filename only if it
                    // looks like a .html path (ends with .html or .htm).
                    // Otherwise default to "previews.html", placed beside the JPEGs after the walk.
                    if (i + 1 < args.length && !args[i + 1].startsWith("-") &&
                            (args[i + 1].toLowerCase().endsWith(".html") ||
                             args[i + 1].toLowerCase().endsWith(".htm"))) {
                        htmlOutput = Paths.get(args[++i]);
                    } else {
                        htmlOutput = Paths.get("previews.html");  // resolved after walk
                    }
                    break;

                default:
                    if (args[i].startsWith("-")) {
                        err("Unknown option: " + args[i]); return false;
                    }
                    roots.add(Paths.get(args[i]));
            }
        }
        return true;
    }

    /**
     * Parse a 6-digit hex RGB color string (with or without leading #).
     * Examples: "ff0000", "#1a2b3c", "FFFFFF"
     */
    static Color parseHexColor(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() != 6)
            throw new IllegalArgumentException(
                "color must be exactly 6 hex digits (rrggbb), got: '" + hex + "'");
        try {
            int rgb = Integer.parseUnsignedInt(h, 16);
            return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "invalid hex color '" + hex + "' — use rrggbb format, e.g. ff0000");
        }
    }

    // =========================================================================
    //  Directory walking
    // =========================================================================

    private void walkDirectory(Path dir) {
        int maxDepth = recurse ? Integer.MAX_VALUE : 1;
        try {
            Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), maxDepth,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        processFile(file);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        err("Cannot access: " + file + " — " + exc.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });
        } catch (IOException e) {
            err("Directory walk failed for " + dir + ": " + e.getMessage());
        }
    }

    // =========================================================================
    //  Per-file processing
    // =========================================================================

    private void processFile(Path src) {
        String name  = src.getFileName().toString();
        String lower = name.toLowerCase();

        boolean matched = SLICER_EXTS.stream().anyMatch(lower::endsWith);
        if (!matched) return;

        // Prefer .lys over any Anycubic binary with the same stem.
        // The .lys preview is a full 512x512 PNG; the .pm4n preview is a
        // low-resolution 224x120 RGB565 thumbnail that is often cut off.
        if (isAnycubicExt(lower)) {
            int dot = lower.lastIndexOf('.');
            if (dot > 0) {
                Path lysCandidate = src.resolveSibling(name.substring(0, dot) + ".lys");
                if (Files.exists(lysCandidate)) {
                    if (verbose) System.out.println(
                        "  PREF  " + src.getFileName() + "  (skipped — " +
                        lysCandidate.getFileName() + " exists)");
                    return;   // .lys will be (or was) processed on its own visit
                }
            }
        }

        found.incrementAndGet();

        Path jpegPath = resolveOutputPath(src, name);

        if (!force && Files.exists(jpegPath)) {
            skipped.incrementAndGet();
            if (verbose) System.out.println("  SKIP  " + src + "  ->  " + jpegPath);
            return;
        }

        try {
            BufferedImage preview = loadPreview(src.toFile());
            writeJpeg(preview, jpegPath);
            exported.incrementAndGet();
            System.out.println("  OK    " + src.getFileName() + "  ->  " + jpegPath);
            if (htmlOutput != null) {
                int dot = name.lastIndexOf('.');
                String stem = (dot > 0) ? name.substring(0, dot) : name;
                // [0]=stem  [1]=jpeg filename  [2]=absolute jpeg parent dir
                Path jpegParent = jpegPath.toAbsolutePath().getParent();
                String jpegDir  = (jpegParent != null) ? jpegParent.toString() : ".";
                htmlEntries.add(new String[]{ stem, jpegPath.getFileName().toString(), jpegDir });
            }
        } catch (IOException e) {
            errors.incrementAndGet();
            err("  FAIL  " + src + ": " + e.getMessage());
        }
    }

    private Path resolveOutputPath(Path src, String name) {
        int dot = name.lastIndexOf('.');
        String stem     = (dot > 0) ? name.substring(0, dot) : name;
        String jpegName = stem + ".preview.jpg";
        if (outputDir != null) {
            return outputDir.resolve(jpegName);
        }
        Path parent = src.getParent();
        return (parent != null ? parent : Paths.get(".")).resolve(jpegName);
    }

    // =========================================================================
    //  JPEG writing
    // =========================================================================

    private void writeJpeg(BufferedImage img, Path dest) throws IOException {
        // Flatten to RGB using bgColor if the image has alpha
        img = flattenToRgb(img, bgColor);

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("No JPEG ImageWriter available");
        ImageWriter writer = writers.next();

        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(jpegQuality);

        Path parent = dest.getParent();
        if (parent != null) Files.createDirectories(parent);

        try (FileImageOutputStream out = new FileImageOutputStream(dest.toFile())) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /**
     * Flatten any image to TYPE_INT_RGB, compositing transparent pixels
     * over the given background color.
     * Images that are already opaque RGB are returned as-is (zero-copy path).
     */
    static BufferedImage flattenToRgb(BufferedImage src, Color bg) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        if (src.getType() == BufferedImage.TYPE_3BYTE_BGR) return src;

        BufferedImage dst = new BufferedImage(
            src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setColor(bg);
        g.fillRect(0, 0, dst.getWidth(), dst.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    // =========================================================================
    //  Preview loading — dispatches to format-specific parsers below
    // =========================================================================

    static BufferedImage loadPreview(File file) throws IOException {
        String name = file.getName().toLowerCase();
        if (isAnycubicExt(name)) return parseAnycubicFile(file);
        if (name.endsWith(".lys")) return parseLycheeFile(file);
        // Unknown extension — try both parsers
        try { return parseAnycubicFile(file); } catch (IOException ignored) {}
        try { return parseLycheeFile(file);   } catch (IOException ignored) {}
        throw new IOException("Unrecognised file format — expected .pm4n or .lys");
    }

    private static boolean isAnycubicExt(String n) {
        return n.endsWith(".pm4n") || n.endsWith(".pm4u") ||
               n.endsWith(".pm3n") || n.endsWith(".pm3")  ||
               n.endsWith(".pm3m") || n.endsWith(".pm3r") ||
               n.endsWith(".pm5")  || n.endsWith(".pm5s") ||
               n.endsWith(".pm7")  || n.endsWith(".pm7m") ||
               n.endsWith(".pwmb") || n.endsWith(".pwms") ||
               n.endsWith(".pwmx") || n.endsWith(".pwmo") ||
               n.endsWith(".pws")  || n.endsWith(".pw0")  ||
               n.endsWith(".pwx")  || n.endsWith(".dlp")  ||
               n.endsWith(".dl2p") || n.endsWith(".pwsq") ||
               n.endsWith(".pwc");
    }

    // =========================================================================
    //  Anycubic Photon Workshop parser
    // =========================================================================

    static BufferedImage parseAnycubicFile(File file) throws IOException {
        return parseAnycubicBytes(Files.readAllBytes(file.toPath()));
    }

    static BufferedImage parseAnycubicBytes(byte[] data) throws IOException {
        if (data.length < 20)
            throw new IOException("File too small to be a valid Anycubic file");

        if (!new String(data, 0, 8).equals("ANYCUBIC"))
            throw new IOException("Not an Anycubic file — missing 'ANYCUBIC' magic");

        ByteBuffer buf     = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int        version = buf.getInt(12);
        int        nsec    = buf.getInt(16);

        if (nsec <= 0 || nsec > 256)
            throw new IOException("Invalid section count: " + nsec);

        // Walk the section offset table (nsec uint32 entries starting at byte 20)
        BufferedImage best   = null;
        int           bestPx = 0;

        for (int i = 0; i < nsec; i++) {
            int tableOff = 20 + i * 4;
            if (tableOff + 4 > data.length) break;

            int secOff = buf.getInt(tableOff);
            if (secOff < 0 || secOff + 16 > data.length) continue;

            String tag    = new String(data, secOff, 12).replace("\0", "").trim();
            int    secLen = buf.getInt(secOff + 12);

            if (!"PREVIEW".equalsIgnoreCase(tag)) continue;
            if (secLen < 12 || secOff + 16 + secLen > data.length) continue;

            // Section data: width(4) + height(4) + unknown(4) + pixels
            int dataBase = secOff + 16;
            int width    = buf.getInt(dataBase);
            int height   = buf.getInt(dataBase + 4);
            // dataBase+8: unknown field — ignored
            int pixStart = dataBase + 12;
            int pixBytes = width * height * 2;

            if (width <= 0 || width > 8192 || height <= 0 || height > 8192
                    || pixStart + pixBytes > data.length) continue;

            BufferedImage img = decodeRgb565(data, pixStart, width, height);
            if (width * height > bestPx) {
                best   = img;
                bestPx = width * height;
            }
        }

        if (best == null)
            throw new IOException(
                "No valid PREVIEW section found (version=" + version + "). " +
                "The file may not embed a preview thumbnail.");
        return best;
    }

    /**
     * Decode raw RGB565 little-endian bytes starting at {@code offset} in {@code data}.
     * RGB565: bits[15:11]=R5, bits[10:5]=G6, bits[4:0]=B5
     * Expanded to 8-bit: R8=(R5<<3)|(R5>>2), G8=(G6<<2)|(G6>>4), B8=(B5<<3)|(B5>>2)
     */
    static BufferedImage decodeRgb565(byte[] data, int offset, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteBuffer    buf = ByteBuffer.wrap(data, offset, width * height * 2)
                                      .order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int p  = buf.getShort() & 0xFFFF;
                int r5 = (p >> 11) & 0x1F;  int r8 = (r5 << 3) | (r5 >> 2);
                int g6 = (p >>  5) & 0x3F;  int g8 = (g6 << 2) | (g6 >> 4);
                int b5 =  p        & 0x1F;  int b8 = (b5 << 3) | (b5 >> 2);
                img.setRGB(x, y, (r8 << 16) | (g8 << 8) | b8);
            }
        }
        return img;
    }

    // =========================================================================
    //  Lychee Slicer (.lys) parser
    // =========================================================================

    static BufferedImage parseLycheeFile(File file) throws IOException {
        byte[] data = Files.readAllBytes(file.toPath());

        if (data.length < 16)
            throw new IOException("File too small to be a .lys file");

        ByteBuffer hdr    = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int        magic  = hdr.getInt(0);
        int        jsonLen = hdr.getInt(12);   // field[3] = JSON text byte length

        if (magic != 4 || jsonLen <= 0 || 16 + jsonLen > data.length)
            throw new IOException("Not a valid Lychee .lys file (bad header)");

        String json     = new String(data, 16, jsonLen, StandardCharsets.UTF_8);
        long   dataBase = 16L + jsonLen;       // blob section starts here

        LysEntry entry = findBestLysPreview(json);
        if (entry == null)
            throw new IOException(
                "No preview image entry found in .lys manifest " +
                "(expected 'preview.png' or similar)");

        long absOffset = dataBase + entry.offset;
        if (absOffset < 0 || absOffset + entry.size > data.length)
            throw new IOException("Preview blob is out of file bounds (manifest corrupt?)");

        // Some Lychee versions write 1–4 prefix bytes before the actual image data
        // at the stated manifest offset (observed: 2 bytes in newer exports).
        // Scan forward for the image magic rather than assuming offset+0 is the start.
        int imgStart = (int) absOffset;
        int imgEnd   = (int)(absOffset + entry.size);
        String lname = entry.name.toLowerCase();
        if (lname.endsWith(".png") || lname.endsWith(".jpg") || lname.endsWith(".jpeg")) {
            byte[] pngMagic  = { (byte)0x89, 0x50, 0x4E, 0x47 };  // \x89PNG
            byte[] jpegMagic = { (byte)0xFF, (byte)0xD8 };
            for (int skip = 0; skip <= 8 && imgStart + skip + 4 <= imgEnd; skip++) {
                if (lname.endsWith(".png")
                        && data[imgStart + skip]     == pngMagic[0]
                        && data[imgStart + skip + 1] == pngMagic[1]
                        && data[imgStart + skip + 2] == pngMagic[2]
                        && data[imgStart + skip + 3] == pngMagic[3]) {
                    imgStart += skip;
                    break;
                }
                if ((lname.endsWith(".jpg") || lname.endsWith(".jpeg"))
                        && data[imgStart + skip]     == jpegMagic[0]
                        && data[imgStart + skip + 1] == jpegMagic[1]) {
                    imgStart += skip;
                    break;
                }
            }
        }

        byte[]        imgBytes = Arrays.copyOfRange(data, imgStart, imgEnd);
        BufferedImage img      = ImageIO.read(new ByteArrayInputStream(imgBytes));
        if (img == null)
            throw new IOException("Could not decode preview image '" + entry.name + "'");

        return img;  // caller (writeJpeg) handles alpha flattening with bgColor
    }

    /**
     * Scan the LYS JSON manifest for the best preview image entry.
     * Uses lightweight string scanning — no JSON library required.
     *
     * Priority: exact "preview.png" > name contains "preview" > "thumbnail"/"thumb"
     *           > "cover" > any other image.  PNG preferred over JPEG at equal priority.
     */
    private static LysEntry findBestLysPreview(String json) {
        LysEntry best      = null;
        int      bestScore = -1;

        int pos = json.indexOf("\"mangoFiles\"");
        if (pos < 0) return null;

        while (true) {
            int nameStart = json.indexOf('"', pos + 1);
            if (nameStart < 0) break;
            int nameEnd = json.indexOf('"', nameStart + 1);
            if (nameEnd < 0) break;
            String name = json.substring(nameStart + 1, nameEnd);

            int objStart = json.indexOf('{', nameEnd);
            if (objStart < 0) break;
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) break;
            String obj = json.substring(objStart, objEnd + 1);
            pos = objEnd + 1;

            String lname = name.toLowerCase();
            boolean isImg = lname.endsWith(".png") || lname.endsWith(".jpg")
                         || lname.endsWith(".jpeg");
            if (!isImg) continue;

            long size   = lysExtractLong(obj, "\"size\"");
            long offset = lysExtractLong(obj, "\"offset\"");
            if (size <= 0 || offset < 0) continue;

            int score;
            if (lname.equals("preview.png"))                   score = 100;
            else if (lname.contains("preview"))                score = 80;
            else if (lname.contains("thumbnail") ||
                     lname.contains("thumb"))                  score = 70;
            else if (lname.contains("cover"))                  score = 60;
            else                                               score = 10;
            if (lname.endsWith(".png"))                        score += 5;

            if (score > bestScore ||
                    (score == bestScore && size > (best == null ? 0 : best.size))) {
                bestScore = score;
                best = new LysEntry(name, offset, (int) size);
            }
        }
        return best;
    }

    /** Pull the first integer value after {@code key} from a JSON fragment. */
    private static long lysExtractLong(String json, String key) {
        int kp = json.indexOf(key);
        if (kp < 0) return -1;
        int vp = kp + key.length();
        while (vp < json.length() &&
               (json.charAt(vp) == ':' || json.charAt(vp) == ' ' || json.charAt(vp) == '"'))
            vp++;
        int ve = vp;
        while (ve < json.length() && Character.isDigit(json.charAt(ve))) ve++;
        if (ve == vp) return -1;
        try { return Long.parseLong(json.substring(vp, ve)); }
        catch (NumberFormatException e) { return -1; }
    }

    private static final class LysEntry {
        final String name;
        final long   offset;
        final int    size;
        LysEntry(String name, long offset, int size) {
            this.name = name; this.offset = offset; this.size = size;
        }
    }

    // =========================================================================
    //  HTML report generation
    // =========================================================================

    /**
     * Write a print-friendly HTML checklist.
     * Images are referenced by relative src= path -- the HTML file sits in the
     * same directory as the JPEGs produced by this run.
     * Layout: 3 cards per row on screen, 2 per row when printing.
     * Each card has a checkbox, the filename as a label, and the preview image.
     */
    private static void writeHtmlReport(Path dest, java.util.List<String[]> entries)
            throws IOException {
        String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                .format(new java.util.Date());

        try (PrintWriter w = new PrintWriter(
                Files.newBufferedWriter(dest, StandardCharsets.UTF_8))) {

            w.println("<!DOCTYPE html>");
            w.println("<html lang=\"en\">");
            w.println("<head>");
            w.println("<meta charset=\"UTF-8\">");
            w.println("<title>Resin Print Preview Checklist</title>");
            w.println("<style>");
            w.println("*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}");
            w.println("body{font-family:Arial,Helvetica,sans-serif;font-size:13px;background:#f4f4f4;color:#222;padding:16px}");
            w.println("h1{font-size:18px;margin-bottom:4px}");
            w.println(".meta{color:#666;font-size:12px;margin-bottom:16px}");
            w.println(".grid{display:flex;flex-wrap:wrap;gap:12px}");
            w.println(".card{background:#fff;border:1px solid #ccc;border-radius:6px;padding:8px;");
            w.println("  width:calc(33.333% - 8px);page-break-inside:avoid;break-inside:avoid}");
            w.println(".card-header{display:flex;align-items:flex-start;gap:6px;margin-bottom:6px}");
            w.println(".card-header input[type=checkbox]{margin-top:2px;flex-shrink:0;width:16px;height:16px;cursor:pointer}");
            w.println(".card-header label{font-size:12px;font-weight:bold;word-break:break-all;line-height:1.3;cursor:pointer}");
            w.println(".card img{width:100%;height:auto;display:block;border-radius:3px;border:1px solid #e0e0e0}");
            w.println("@media print{");
            w.println("  body{background:#fff;padding:8px}");
            w.println("  .card{width:calc(50% - 6px);border-color:#999}");
            w.println("  .card-header input[type=checkbox]{-webkit-print-color-adjust:exact;print-color-adjust:exact}");
            w.println("}");
            w.println("</style>");
            w.println("</head>");
            w.println("<body>");
            w.printf("<h1>Resin Print Preview Checklist</h1>%n");
            w.printf("<p class=\"meta\">Generated: %s &nbsp;&middot;&nbsp; %d file(s)</p>%n",
                     htmlEsc(ts), entries.size());
            w.println("<div class=\"grid\">");

            for (int i = 0; i < entries.size(); i++) {
                String stem     = entries.get(i)[0];
                String jpegFile = entries.get(i)[1];
                String cbId     = "cb" + i;
                w.println("  <div class=\"card\">");
                w.println("    <div class=\"card-header\">");
                w.printf( "      <input type=\"checkbox\" id=\"%s\">%n", htmlEsc(cbId));
                w.printf( "      <label for=\"%s\">%s</label>%n", htmlEsc(cbId), htmlEsc(stem));
                w.println("    </div>");
                w.printf( "    <img src=\"%s\" alt=\"%s\">%n", htmlEsc(jpegFile), htmlEsc(stem));
                w.println("  </div>");
            }

            w.println("</div>");
            w.println("</body>");
            w.println("</html>");
        }
    }

    private static String htmlEsc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // =========================================================================
    //  Help / error output
    // =========================================================================

    private static void printHelp() {
        System.out.println(
            "Usage: java SliceViewerApp [options] <file|dir> [<file|dir> ...]\n" +
            "\n" +
            "Extracts preview thumbnails from Anycubic Photon Workshop and Lychee Slicer\n" +
            "files and saves them as JPEG images.\n" +
            "\n" +
            "Supported formats:\n" +
            "  .pm4n .pm4u .pm3n .pm3 .pm3m .pm3r .pm5 .pm5s .pm7 .pm7m\n" +
            "  .pwmb .pwms .pwmx .pwmo .pws .pw0 .pwx .dlp .dl2p .pwsq .pwc\n" +
            "  .lys\n" +
            "\n" +
            "Output: <source-stem>.preview.jpg in the same directory as the source file,\n" +
            "         or in --output-dir if specified.\n" +
            "\n" +
            "Options:\n" +
            "  --output-dir <dir>   Write all JPEGs to this directory (default: same as input).\n" +
            "  --quality <0-100>    JPEG quality (default: 90).\n" +
            "  --bg <rrggbb>        Background color for alpha compositing (default: 000000).\n" +
            "                       Accepts 6-digit hex with or without leading #.\n" +
            "                       Examples: ff0000  #1a2b3c  FFFFFF\n" +
            "  --html [file]        Write a print-friendly HTML checklist alongside the JPEGs.\n" +
            "                       Default filename: previews.html in --output-dir (or cwd).\n" +
            "  --force              Overwrite existing JPEGs.\n" +
            "  --no-recurse         Don't recurse into sub-directories.\n" +
            "  --verbose            Print skipped files too.\n" +
            "  --help               Show this help.\n" +
            "\n" +
            "Examples:\n" +
            "  java SliceViewerApp --html ~/prints/\n" +
            "  java SliceViewerApp --output-dir ./previews --html --bg ffffff ~/prints/\n" +
            "  java SliceViewerApp --html checklist.html --quality 85 ~/prints/\n" +
            "  java SliceViewerApp --no-recurse --verbose /Volumes/USB/"
        );
    }

    private static void err(String msg) {
        System.err.println("ERROR: " + msg);
    }
}