package com.github.ledlogic.imgutils;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * StlSupFinder - Recursively scans a directory for *.stl files
 * whose filename contains "SUP" (case-insensitive) and copies
 * them all to a single output folder.
 *
 * Usage:
 *   java StlSupFinder <sourceDir> <outputDir>
 *
 * Example:
 *   java StlSupFinder "C:\Users\Jeff\Desktop\STL TEMP\June24" "C:\Users\Jeff\Desktop\SUP_Files"
 */
public class StlSupFinderApp {

    public static void main(String[] args) {
        // ── Argument handling ──────────────────────────────────────────────
        if (args.length < 2) {
            System.out.println("Usage: java StlSupFinder <sourceDir> <outputDir>");
            System.out.println("Example:");
            System.out.println("  java StlSupFinder \"C:\\Users\\Jeff\\Desktop\\STL TEMP\\June24\" \"C:\\Users\\Jeff\\Desktop\\SUP_Files\"");
            System.exit(1);
        }

        Path sourceDir = Paths.get(args[0]);
        Path outputDir = Paths.get(args[1]);

        // ── Validate source directory ──────────────────────────────────────
        if (!Files.exists(sourceDir) || !Files.isDirectory(sourceDir)) {
            System.err.println("ERROR: Source directory does not exist or is not a directory:");
            System.err.println("  " + sourceDir.toAbsolutePath());
            System.exit(1);
        }

        // ── Create output directory if needed ─────────────────────────────
        try {
            Files.createDirectories(outputDir);
            System.out.println("Output folder: " + outputDir.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("ERROR: Could not create output directory: " + e.getMessage());
            System.exit(1);
        }

        System.out.println("Scanning:      " + sourceDir.toAbsolutePath());
        System.out.println("Looking for:   *.stl files containing \"SUP\" in the name");
        System.out.println("─".repeat(60));

        // ── Walk the directory tree ────────────────────────────────────────
        List<Path> found = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try {
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();

                    // Match: extension is .stl (case-insensitive) AND name contains SUP
                    if (name.toLowerCase().endsWith(".stl") &&
                        name.toUpperCase().contains("SUP")) {

                        found.add(file);
                        System.out.println("  FOUND: " + file);

                        // Build destination path; handle duplicate filenames
                        Path dest = outputDir.resolve(name);
                        if (Files.exists(dest)) {
                            dest = uniquePath(outputDir, name);
                            System.out.println("         (renamed to avoid collision: " + dest.getFileName() + ")");
                        }

                        try {
                            Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            errors.add("Copy failed for " + file + ": " + e.getMessage());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    errors.add("Could not read: " + file + " (" + exc.getMessage() + ")");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("ERROR during directory walk: " + e.getMessage());
            System.exit(1);
        }

        // ── Summary ───────────────────────────────────────────────────────
        System.out.println("─".repeat(60));
        System.out.println("Done! " + found.size() + " SUP file(s) copied to:");
        System.out.println("  " + outputDir.toAbsolutePath());

        if (!errors.isEmpty()) {
            System.out.println("\nWarnings / errors encountered:");
            errors.forEach(e -> System.out.println("  ⚠  " + e));
        }
    }

    /**
     * If a file named "foo_SUP.stl" already exists in dest dir,
     * returns "foo_SUP_2.stl", "foo_SUP_3.stl", etc.
     */
    private static Path uniquePath(Path dir, String filename) {
        int dot = filename.lastIndexOf('.');
        String base = (dot >= 0) ? filename.substring(0, dot) : filename;
        String ext  = (dot >= 0) ? filename.substring(dot)    : "";

        int counter = 2;
        Path candidate;
        do {
            candidate = dir.resolve(base + "_" + counter + ext);
            counter++;
        } while (Files.exists(candidate));

        return candidate;
    }
}