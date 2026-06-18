package com.github.ledlogic.imgutils;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Scans a directory (recursively) and deletes image files whose names
 * contain the word "Copy" (case-sensitive by default).
 *
 * Usage:
 *   java RemoveCopyImages <directory> [--dry-run] [--ignore-case] [--recursive]
 *
 * Flags:
 *   --dry-run      List files that WOULD be deleted without actually deleting them.
 *   --ignore-case  Match "copy", "COPY", "Copy", etc.
 *   --recursive    Walk sub-directories (default: top-level only).
 */
public class ImageCopyRemoverApp {

    // Supported image extensions
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        ".jpg", ".jpeg", ".png", ".gif", ".bmp",
        ".tiff", ".tif", ".webp", ".heic", ".svg", ".ico"
    );

    public static void main(String[] args) throws IOException {

        if (args.length == 0) {
            System.err.println("Usage: java RemoveCopyImages <directory> [--dry-run] [--ignore-case] [--recursive]");
            System.exit(1);
        }

        Path targetDir   = Paths.get(args[0]);
        boolean dryRun      = hasFlag(args, "--dry-run");
        boolean ignoreCase  = hasFlag(args, "--ignore-case");
        boolean recursive   = hasFlag(args, "--recursive");

        if (!Files.isDirectory(targetDir)) {
            System.err.println("ERROR: Not a directory: " + targetDir);
            System.exit(1);
        }

        System.out.println("=== RemoveCopyImages ===");
        System.out.printf("Directory  : %s%n", targetDir.toAbsolutePath());
        System.out.printf("Recursive  : %s%n", recursive);
        System.out.printf("Ignore case: %s%n", ignoreCase);
        System.out.printf("Dry run    : %s%n%n", dryRun);

        List<Path> toDelete = new ArrayList<>();

        if (recursive) {
            // Walk the full tree
            Files.walkFileTree(targetDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isCopyImage(file, ignoreCase)) toDelete.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    System.err.println("Could not access: " + file + " — " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            // Top-level only
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetDir)) {
                for (Path entry : stream) {
                    if (Files.isRegularFile(entry) && isCopyImage(entry, ignoreCase)) {
                        toDelete.add(entry);
                    }
                }
            }
        }

        if (toDelete.isEmpty()) {
            System.out.println("No matching files found.");
            return;
        }

        System.out.printf("Found %d file(s) to %s:%n%n",
            toDelete.size(), dryRun ? "delete (DRY RUN)" : "delete");

        int deleted = 0, failed = 0;

        for (Path file : toDelete) {
            System.out.println("  " + file);
            if (!dryRun) {
                try {
                    Files.delete(file);
                    deleted++;
                } catch (IOException e) {
                    System.err.println("    ERROR deleting: " + e.getMessage());
                    failed++;
                }
            }
        }

        System.out.println();
        if (dryRun) {
            System.out.printf("Dry run complete. %d file(s) would be deleted.%n", toDelete.size());
        } else {
            System.out.printf("Done. Deleted: %d  |  Failed: %d%n", deleted, failed);
        }
    }

    /**
     * Returns true if the file is an image and its name contains "Copy".
     */
    private static boolean isCopyImage(Path file, boolean ignoreCase) {
        String name = file.getFileName().toString();
        String ext  = getExtension(name);

        if (!IMAGE_EXTENSIONS.contains(ext.toLowerCase())) return false;

        return ignoreCase
            ? name.toLowerCase().contains("copy")
            : name.contains("Copy");
    }

    /** Extracts the lowercase file extension including the dot, e.g. ".jpg" */
    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot >= 0) ? filename.substring(dot).toLowerCase() : "";
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) if (arg.equalsIgnoreCase(flag)) return true;
        return false;
    }
}