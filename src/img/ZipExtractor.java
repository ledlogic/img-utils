package img;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.*;

/**
 * ZipExtractor - CLI tool to extract and merge Google Drive split zip downloads.
 *
 * Usage:
 *   java com.github.ledlogic.webp.ZipExtractor <directory-or-zip> [options]
 *
 *   <directory-or-zip> may be:
 *     - A directory  -> scans for all matching numbered zip files inside it
 *     - A single .zip file -> extracts just that one file into its parent directory
 *
 * Options:
 *   --dry-run, -n          Show what would be done without changing anything
 *   --threads, -t <N>      Parallel threads (default: all CPU cores, autodetected)
 *   --pattern, -p <regex>  Override the filename-matching regex (directory mode only)
 *   --help, -h             Show this help
 *
 * Default pattern matches files like:
 *   drive-download-20260503T145811Z-3-001.zip ... -023.zip
 *
 * Skip behaviour:
 *   If a destination file already exists AND its size matches the zip entry's
 *   uncompressed size, extraction of that entry is skipped. The zip is still
 *   deleted afterwards if ALL its entries were either extracted or skipped.
 */
public class ZipExtractor {

    // Matches: <prefix>-NNN.zip  where NNN is 3+ digits
    private static final Pattern DEFAULT_PATTERN =
            Pattern.compile("^(.+)-(\\d{3,})\\.zip$", Pattern.CASE_INSENSITIVE);

    // ANSI colours (disabled on plain Windows cmd; enabled in Windows Terminal)
    private static final String RESET  = "\u001B[0m";
    private static final String BOLD   = "\u001B[1m";
    private static final String GREEN  = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String CYAN   = "\u001B[36m";
    private static final boolean USE_COLOR =
            !System.getProperty("os.name", "").toLowerCase().contains("win")
            || System.getenv("WT_SESSION") != null;

    // Serialize console writes so parallel threads don't interleave lines
    private static final Object PRINT_LOCK = new Object();

    /**
     * Result of extracting one zip file.
     * extracted = files written, skipped = files already present with matching size, errors = failures
     */
    private static class ExtractResult {
        int extracted = 0;
        int skipped   = 0;
        boolean failed = false; // true if an IOException aborted the whole zip
    }

    public static void main(String[] args) {
        if (args.length == 0) { printUsage(); System.exit(1); }

        // --- Parse arguments ---
        Path    inputPath     = null;
        boolean dryRun        = false;
        int     threads       = Runtime.getRuntime().availableProcessors();
        Pattern customPattern = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dry-run": case "-n":
                    dryRun = true;
                    break;
                case "--threads": case "-t":
                    if (++i >= args.length) { err("--threads requires a number"); System.exit(1); }
                    try {
                        threads = Integer.parseInt(args[i]);
                        if (threads < 1) throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        err("--threads must be a positive integer, got: " + args[i]);
                        System.exit(1);
                    }
                    break;
                case "--pattern": case "-p":
                    if (++i >= args.length) { err("--pattern requires an argument"); System.exit(1); }
                    try { customPattern = Pattern.compile(args[i], Pattern.CASE_INSENSITIVE); }
                    catch (PatternSyntaxException e) { err("Invalid regex: " + e.getMessage()); System.exit(1); }
                    break;
                case "--help": case "-h":
                    printUsage(); System.exit(0);
                    break;
                default:
                    if (inputPath == null) inputPath = Paths.get(args[i]);
                    else { err("Unexpected argument: " + args[i]); printUsage(); System.exit(1); }
            }
        }

        if (inputPath == null) { err("No file or directory specified."); printUsage(); System.exit(1); }
        if (!Files.exists(inputPath)) { err("Path does not exist: " + inputPath); System.exit(1); }

        info(BOLD + "ZipExtractor" + RESET);
        if (dryRun) info(YELLOW + "[DRY RUN - no files will be changed]" + RESET);

        int exitCode;
        if (Files.isRegularFile(inputPath)) {
            if (customPattern != null) warn("--pattern is ignored in single-file mode.");
            exitCode = processSingleFile(inputPath, dryRun);
        } else if (Files.isDirectory(inputPath)) {
            exitCode = processDirectory(inputPath, customPattern, threads, dryRun);
        } else {
            err("Not a file or directory: " + inputPath);
            exitCode = 1;
        }

        System.exit(exitCode);
    }

    // ── Single-file mode ──────────────────────────────────────────────────────

    private static int processSingleFile(Path zipFile, boolean dryRun) {
        if (!zipFile.getFileName().toString().toLowerCase().endsWith(".zip")) {
            err("File does not have a .zip extension: " + zipFile);
            return 1;
        }
        Path destDir = zipFile.toAbsolutePath().getParent();

        System.out.println();
        info("File      : " + zipFile.toAbsolutePath());
        info("Extract to: " + destDir);
        System.out.println();

        info(BOLD + CYAN + "File: " + zipFile.getFileName() + RESET);
        ExtractResult result = extractZip(zipFile, destDir, dryRun);
        if (result.failed) return 2;

        boolean deleted = deleteZip(zipFile, dryRun);
        System.out.println();
        printSummary(result.extracted, result.skipped, deleted ? 1 : 0, deleted ? 0 : 1, 1, dryRun);
        return deleted ? 0 : 2;
    }

    // ── Directory mode ────────────────────────────────────────────────────────

    private static int processDirectory(Path dir, Pattern customPattern, int threads, boolean dryRun) {
        Pattern matchPattern = customPattern != null ? customPattern : DEFAULT_PATTERN;

        System.out.println();
        info("Directory : " + dir.toAbsolutePath());
        info("Pattern   : " + matchPattern.pattern());
        info("Threads   : " + threads
                + "  (logical CPU cores available: " + Runtime.getRuntime().availableProcessors() + ")");
        System.out.println();

        // Discover matching zips
        List<Path> allZips;
        try (Stream<Path> stream = Files.list(dir)) {
            allZips = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> matchPattern.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            err("Cannot list directory: " + e.getMessage());
            return 1;
        }

        if (allZips.isEmpty()) { warn("No matching zip files found in " + dir); return 0; }

        // Group by prefix (everything before -NNN)
        Map<String, List<Path>> groups = new LinkedHashMap<>();
        for (Path zip : allZips) {
            Matcher m = DEFAULT_PATTERN.matcher(zip.getFileName().toString());
            String key = m.matches() ? m.group(1) : "all";
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(zip);
        }

        info(String.format("Found %d zip file(s) in %d group(s).", allZips.size(), groups.size()));
        System.out.println();

        // Shared counters - safe for concurrent increment
        AtomicInteger totalExtracted = new AtomicInteger(0);
        AtomicInteger totalSkipped   = new AtomicInteger(0);
        AtomicInteger totalDeleted   = new AtomicInteger(0);
        AtomicInteger errors         = new AtomicInteger(0);

        long startMs = System.currentTimeMillis();

        for (Map.Entry<String, List<Path>> entry : groups.entrySet()) {
            String     prefix  = entry.getKey();
            List<Path> zipList = entry.getValue();

            info(BOLD + CYAN + "Group: " + prefix + RESET
                    + "  (" + zipList.size() + " file" + (zipList.size() > 1 ? "s" : "") + ")");

            int poolSize = Math.min(threads, zipList.size());
            ExecutorService pool = Executors.newFixedThreadPool(poolSize);
            List<Future<?>> futures = new ArrayList<>();

            for (Path zip : zipList) {
                futures.add(pool.submit(() -> {
                    info("  " + CYAN + "[start] " + zip.getFileName() + RESET);
                    ExtractResult result = extractZip(zip, dir, dryRun);
                    if (result.failed) {
                        errors.incrementAndGet();
                    } else {
                        totalExtracted.addAndGet(result.extracted);
                        totalSkipped.addAndGet(result.skipped);
                        if (deleteZip(zip, dryRun)) totalDeleted.incrementAndGet();
                        else errors.incrementAndGet();
                    }
                }));
            }

            pool.shutdown();
            for (Future<?> f : futures) {
                try { f.get(); }
                catch (ExecutionException e) {
                    err("Unexpected worker error: " + e.getCause());
                    errors.incrementAndGet();
                }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            System.out.println();
        }

        long elapsedSec = (System.currentTimeMillis() - startMs) / 1000;
        info(String.format("Completed in %d s", elapsedSec));
        printSummary(totalExtracted.get(), totalSkipped.get(), totalDeleted.get(), errors.get(), allZips.size(), dryRun);
        return errors.get() > 0 ? 2 : 0;
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    /**
     * Extracts all entries from {@code zip} into {@code destDir}.
     *
     * Uses ZipFile (not ZipInputStream) so that entry.getSize() is always
     * populated from the central directory — ZipInputStream reads sequentially
     * and returns -1 for size when the local header omits it (common with
     * Google Drive zips).
     *
     * Skip logic: if the destination file already exists and its on-disk size
     * matches the zip entry's uncompressed size, the entry is skipped entirely
     * (no read, no write). The zip is still deleted afterwards.
     *
     * Uses a 64 KB read/write buffer for efficient I/O.
     */
    private static ExtractResult extractZip(Path zip, Path destDir, boolean dryRun) {
        ExtractResult result = new ExtractResult();
        byte[] buf = new byte[64 * 1024];
        try (ZipFile zf = new ZipFile(zip.toFile())) {

            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path outPath = resolveEntry(destDir, entry.getName());
                if (outPath == null) {
                    warn("  [SKIP-UNSAFE] " + zip.getFileName() + ": " + entry.getName());
                    continue;
                }

                if (entry.isDirectory()) {
                    if (!dryRun) Files.createDirectories(outPath);
                    // directories don't count toward extracted/skipped totals
                    continue;
                }

                // ── Skip check (size always available via ZipFile central dir) ──
                long storedSize = entry.getSize(); // reliable — read from central directory
                if (storedSize >= 0 && Files.exists(outPath)) {
                    long diskSize = Files.size(outPath);
                    if (diskSize == storedSize) {
                        info("  [SKIP] " + zip.getFileName() + " -> " + entry.getName()
                                + "  (" + storedSize + " bytes, already exists)");
                        result.skipped++;
                        continue;
                    }
                }

                // ── Extract ───────────────────────────────────────────────────
                if (!dryRun) {
                    Files.createDirectories(outPath.getParent());
                    try (InputStream in  = zf.getInputStream(entry);
                         OutputStream out = new BufferedOutputStream(
                                 Files.newOutputStream(outPath), 64 * 1024)) {
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                }
                info("  [FILE] " + zip.getFileName() + " -> " + entry.getName());
                result.extracted++;
            }
        } catch (IOException e) {
            err("ERROR extracting " + zip.getFileName() + ": " + e.getMessage());
            result.failed = true;
        }
        return result;
    }

    /** Deletes {@code zip}. Returns true on success. */
    private static boolean deleteZip(Path zip, boolean dryRun) {
        if (dryRun) {
            info("  " + YELLOW + "[would delete] " + zip.getFileName() + RESET);
            return true;
        }
        try {
            Files.delete(zip);
            info("  " + GREEN + "[deleted] " + zip.getFileName() + RESET);
            return true;
        } catch (IOException e) {
            err("ERROR deleting " + zip.getFileName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Resolves a zip entry name safely inside destDir (zip-slip guard).
     * Returns null if the path would escape destDir.
     */
    private static Path resolveEntry(Path destDir, String entryName) {
        String normalized = entryName.replace('\\', '/').replaceAll("^/+", "");
        Path resolved = destDir.resolve(normalized).normalize();
        return resolved.startsWith(destDir.normalize()) ? resolved : null;
    }

    // --- Console output (all synchronized to prevent parallel line interleaving) ---

    private static void printSummary(int extracted, int skipped, int deleted, int errs, int total, boolean dryRun) {
        info(BOLD + "Done." + RESET);
        info(GREEN  + "  Entries extracted : " + extracted + RESET);
        info(YELLOW + "  Entries skipped   : " + skipped + " (already existed with matching size)" + RESET);
        info(GREEN  + "  Zip files deleted : " + deleted + " / " + total + RESET);
        if (errs > 0) info(RED + "  Errors            : " + errs + RESET);
        if (dryRun)   info(YELLOW + "  (Dry run - nothing was actually changed)" + RESET);
    }

    private static void printUsage() {
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java com.github.ledlogic.webp.ZipExtractor <directory-or-zip> [options]");
        System.out.println();
        System.out.println("  <directory-or-zip>");
        System.out.println("    A directory  -> scans for all numbered zip files matching the pattern");
        System.out.println("    A .zip file  -> extracts just that file into its parent directory");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --dry-run,  -n          Show what would be done without changing anything");
        System.out.println("  --threads,  -t <N>      Parallel threads (default: all CPU cores, autodetected)");
        System.out.println("  --pattern,  -p <regex>  Override filename-matching regex (directory mode only)");
        System.out.println("  --help,     -h          Show this help");
        System.out.println();
        System.out.println("Skip behaviour:");
        System.out.println("  If a file already exists at the destination with the same size as the");
        System.out.println("  zip entry's uncompressed size, that entry is skipped (not re-extracted).");
        System.out.println("  The zip is still deleted if all its entries were extracted or skipped.");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  ZipExtractor \"G:\\My Drive\\Games\\W Fantasy\"");
        System.out.println("  ZipExtractor \"G:\\My Drive\\Games\\W Fantasy\" --dry-run");
        System.out.println("  ZipExtractor \"G:\\My Drive\\Games\\W Fantasy\" --threads 4");
        System.out.println("  ZipExtractor drive-download-20260503T145811Z-3-007.zip");
        System.out.println();
        System.out.println("Default pattern matches files like:");
        System.out.println("  drive-download-20260503T145811Z-3-001.zip");
        System.out.println("  drive-download-20260503T145811Z-3-023.zip");
        System.out.println();
    }

    private static void info(String msg) {
        synchronized (PRINT_LOCK) {
            System.out.println(USE_COLOR ? msg : stripAnsi(msg));
        }
    }

    private static void warn(String msg) {
        synchronized (PRINT_LOCK) {
            System.out.println(USE_COLOR ? YELLOW + msg + RESET : stripAnsi(msg));
        }
    }

    private static void err(String msg) {
        synchronized (PRINT_LOCK) {
            System.err.println(USE_COLOR ? RED + "ERROR: " + msg + RESET : "ERROR: " + stripAnsi(msg));
        }
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*m", "");
    }
}