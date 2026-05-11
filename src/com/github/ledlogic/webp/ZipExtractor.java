package com.github.ledlogic.webp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ZipExtractor - CLI tool to extract and merge Google Drive split zip downloads.
 *
 * Usage:
 *   java com.github.ledlogic.webp.ZipExtractor <directory-or-zip> [options]
 *
 *   <directory-or-zip> may be:
 *     - A directory  → scans for all matching numbered zip files inside it
 *     - A single .zip file → extracts just that one file into its parent directory
 *
 * Options:
 *   --dry-run, -n          Show what would be done without changing anything
 *   --pattern, -p <regex>  Override the filename-matching regex (directory mode only)
 *   --help, -h             Show this help
 *
 * Default pattern matches files like:
 *   drive-download-20260503T145811Z-3-001.zip … -023.zip
 */
public class ZipExtractor {

    // Matches: <prefix>-NNN.zip  where NNN is 3+ digits
    private static final Pattern DEFAULT_PATTERN =
            Pattern.compile("^(.+)-(\\d{3,})\\.zip$", Pattern.CASE_INSENSITIVE);

    // ANSI colours (disabled on Windows unless in Windows Terminal)
    private static final String RESET  = "\u001B[0m";
    private static final String BOLD   = "\u001B[1m";
    private static final String GREEN  = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String CYAN   = "\u001B[36m";
    private static final boolean USE_COLOR =
            !System.getProperty("os.name", "").toLowerCase().contains("win")
            || System.getenv("WT_SESSION") != null;

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        // --- Parse arguments ---
        Path inputPath      = null;
        boolean dryRun      = false;
        Pattern customPattern = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dry-run": case "-n":
                    dryRun = true;
                    break;
                case "--pattern": case "-p":
                    if (++i >= args.length) { err("--pattern requires an argument"); System.exit(1); }
                    try { customPattern = Pattern.compile(args[i], Pattern.CASE_INSENSITIVE); }
                    catch (PatternSyntaxException e) { err("Invalid regex: " + e.getMessage()); System.exit(1); }
                    break;
                case "--help": case "-h":
                    printUsage();
                    System.exit(0);
                    break;
                default:
                    if (inputPath == null) inputPath = Paths.get(args[i]);
                    else { err("Unexpected argument: " + args[i]); printUsage(); System.exit(1); }
            }
        }

        if (inputPath == null) { err("No file or directory specified."); printUsage(); System.exit(1); }
        if (!Files.exists(inputPath)) { err("Path does not exist: " + inputPath); System.exit(1); }

        info(BOLD + "ZipExtractor" + RESET);
        if (dryRun) info(YELLOW + "[DRY RUN – no files will be changed]" + RESET);
        System.out.println();

        int exitCode;
        if (Files.isRegularFile(inputPath)) {
            // ── Single-file mode ──────────────────────────────────────────────
            if (customPattern != null) warn("--pattern is ignored in single-file mode.");
            exitCode = processSingleFile(inputPath, dryRun);
        } else if (Files.isDirectory(inputPath)) {
            // ── Directory mode ────────────────────────────────────────────────
            exitCode = processDirectory(inputPath, customPattern, dryRun);
        } else {
            err("Not a file or directory: " + inputPath);
            exitCode = 1;
        }

        System.exit(exitCode);
    }

    // ── Single-file mode ──────────────────────────────────────────────────────

    /**
     * Extracts a single named zip into its parent directory, then deletes it.
     * Returns 0 on success, 2 on error.
     */
    private static int processSingleFile(Path zipFile, boolean dryRun) {
        if (!zipFile.getFileName().toString().toLowerCase().endsWith(".zip")) {
            err("File does not have a .zip extension: " + zipFile);
            return 1;
        }

        Path destDir = zipFile.toAbsolutePath().getParent();

        info("File      : " + zipFile.toAbsolutePath());
        info("Extract to: " + destDir);
        System.out.println();

        info(BOLD + CYAN + "File: " + zipFile.getFileName() + RESET);
        int extracted = extractZip(zipFile, destDir, dryRun);
        if (extracted < 0) return 2;

        boolean deleted = deleteZip(zipFile, dryRun);
        System.out.println();

        info(BOLD + "Done." + RESET);
        info(GREEN + "  Entries extracted : " + extracted + RESET);
        info(GREEN + "  Zip files deleted : " + (deleted ? 1 : 0) + RESET);
        if (!deleted) info(RED + "  Errors            : 1" + RESET);
        if (dryRun)   info(YELLOW + "  (Dry run – nothing was actually changed)" + RESET);

        return deleted ? 0 : 2;
    }

    // ── Directory mode ────────────────────────────────────────────────────────

    /**
     * Scans {@code dir} for numbered zip groups, extracts and deletes them.
     * Returns 0 on full success, 2 if any errors occurred.
     */
    private static int processDirectory(Path dir, Pattern customPattern, boolean dryRun) {
        Pattern matchPattern = customPattern != null ? customPattern : DEFAULT_PATTERN;

        info("Directory : " + dir.toAbsolutePath());
        info("Pattern   : " + matchPattern.pattern());
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

        if (allZips.isEmpty()) {
            warn("No matching zip files found in " + dir);
            return 0;
        }

        // Group by prefix (everything before -NNN)
        Map<String, List<Path>> groups = new LinkedHashMap<>();
        for (Path zip : allZips) {
            Matcher m = DEFAULT_PATTERN.matcher(zip.getFileName().toString());
            String key = m.matches() ? m.group(1) : "all";
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(zip);
        }

        info(String.format("Found %d zip file(s) in %d group(s).", allZips.size(), groups.size()));
        System.out.println();

        int totalExtracted = 0;
        int totalDeleted   = 0;
        int errors         = 0;

        for (Map.Entry<String, List<Path>> entry : groups.entrySet()) {
            String prefix      = entry.getKey();
            List<Path> zipList = entry.getValue();

            info(BOLD + CYAN + "Group: " + prefix + RESET
                    + "  (" + zipList.size() + " file" + (zipList.size() > 1 ? "s" : "") + ")");

            for (Path zip : zipList) {
                info("  " + CYAN + zip.getFileName() + RESET);
                int extracted = extractZip(zip, dir, dryRun);
                if (extracted < 0) {
                    errors++;
                } else {
                    totalExtracted += extracted;
                    if (deleteZip(zip, dryRun)) totalDeleted++;
                    else errors++;
                }
            }
            System.out.println();
        }

        info(BOLD + "Done." + RESET);
        info(GREEN + "  Entries extracted : " + totalExtracted + RESET);
        info(GREEN + "  Zip files deleted : " + totalDeleted   + RESET);
        if (errors > 0) info(RED + "  Errors            : " + errors + RESET);
        if (dryRun)     info(YELLOW + "  (Dry run – nothing was actually changed)" + RESET);

        return errors > 0 ? 2 : 0;
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    /**
     * Extracts all entries from {@code zip} into {@code destDir}.
     * Returns the number of file entries extracted, or -1 on error.
     */
    private static int extractZip(Path zip, Path destDir, boolean dryRun) {
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(zip)))) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = resolveEntry(destDir, entry.getName());
                if (outPath == null) {
                    warn("    Skipping potentially unsafe entry: " + entry.getName());
                    zis.closeEntry();
                    continue;
                }

                if (entry.isDirectory()) {
                    if (!dryRun) Files.createDirectories(outPath);
                    info("    [DIR]  " + entry.getName());
                } else {
                    if (!dryRun) {
                        Files.createDirectories(outPath.getParent());
                        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(outPath))) {
                            zis.transferTo(out);
                        }
                    }
                    info("    [FILE] " + entry.getName());
                    count++;
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            err("    ERROR extracting " + zip.getFileName() + ": " + e.getMessage());
            return -1;
        }
        return count;
    }

    /** Deletes {@code zip}. Returns true on success. */
    private static boolean deleteZip(Path zip, boolean dryRun) {
        if (dryRun) {
            info("    " + YELLOW + "[would delete] " + zip.getFileName() + RESET);
            return true;
        }
        try {
            Files.delete(zip);
            info("    " + GREEN + "[deleted] " + zip.getFileName() + RESET);
            return true;
        } catch (IOException e) {
            err("    ERROR deleting " + zip.getFileName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Resolves a zip entry name against {@code destDir}, preventing zip-slip attacks.
     * Returns null if the resolved path would escape destDir.
     */
    private static Path resolveEntry(Path destDir, String entryName) {
        String normalized = entryName.replace('\\', '/').replaceAll("^/+", "");
        Path resolved = destDir.resolve(normalized).normalize();
        if (!resolved.startsWith(destDir.normalize())) return null;
        return resolved;
    }

    // --- Console output ---

    private static void printUsage() {
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java com.github.ledlogic.webp.ZipExtractor <directory-or-zip> [options]");
        System.out.println();
        System.out.println("  <directory-or-zip>");
        System.out.println("    A directory  → scans for all numbered zip files matching the pattern");
        System.out.println("    A .zip file  → extracts just that file into its parent directory");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --dry-run, -n          Show what would be done without changing anything");
        System.out.println("  --pattern, -p <regex>  Override filename-matching regex (directory mode only)");
        System.out.println("  --help, -h             Show this help");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  ZipExtractor ~/Downloads/drive-stuff");
        System.out.println("  ZipExtractor ~/Downloads/drive-stuff --dry-run");
        System.out.println("  ZipExtractor ~/Downloads/drive-download-20260503T145811Z-3-007.zip");
        System.out.println("  ZipExtractor ~/Downloads/drive-stuff --pattern \"backup-\\d{4}-part\\d+\\.zip\"");
        System.out.println();
        System.out.println("Default pattern matches files like:");
        System.out.println("  drive-download-20260503T145811Z-3-001.zip");
        System.out.println("  drive-download-20260503T145811Z-3-023.zip");
        System.out.println();
    }

    private static void info(String msg) {
        System.out.println(USE_COLOR ? msg : stripAnsi(msg));
    }

    private static void warn(String msg) {
        System.out.println(USE_COLOR ? YELLOW + msg + RESET : stripAnsi(msg));
    }

    private static void err(String msg) {
        System.err.println(USE_COLOR ? RED + "ERROR: " + msg + RESET : "ERROR: " + stripAnsi(msg));
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*m", "");
    }
}
