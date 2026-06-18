package img;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renames files in a folder in two passes:
 *   1. Removes the given prefix from all matching filenames.
 *   2. Renames any PDF files matching "MonthYY.pdf" or "MonthYYYY.pdf" to "YYYY-MM.pdf".
 *
 * Usage: ImagePrefixRemoverAndDateOrdererApp <folder> <prefix>
 */
public class ImagePrefixRemoverAndDateOrdererApp {

    private static final Map<String, String> MONTH_MAP = new LinkedHashMap<>();

    static {
        MONTH_MAP.put("january",   "01");
        MONTH_MAP.put("jan",       "01");
        MONTH_MAP.put("february",  "02");
        MONTH_MAP.put("feb",       "02");
        MONTH_MAP.put("march",     "03");
        MONTH_MAP.put("mar",       "03");
        MONTH_MAP.put("april",     "04");
        MONTH_MAP.put("apr",       "04");
        MONTH_MAP.put("may",       "05");
        MONTH_MAP.put("june",      "06");
        MONTH_MAP.put("jun",       "06");
        MONTH_MAP.put("july",      "07");
        MONTH_MAP.put("jul",       "07");
        MONTH_MAP.put("august",    "08");
        MONTH_MAP.put("aug",       "08");
        MONTH_MAP.put("september", "09");
        MONTH_MAP.put("sep",       "09");
        MONTH_MAP.put("sept",      "09");
        MONTH_MAP.put("october",   "10");
        MONTH_MAP.put("oct",       "10");
        MONTH_MAP.put("november",  "11");
        MONTH_MAP.put("nov",       "11");
        MONTH_MAP.put("december",  "12");
        MONTH_MAP.put("dec",       "12");
    }

    private static final Pattern MONTH_YEAR_PATTERN =
        Pattern.compile("^([A-Za-z]+)(\\d{2}|\\d{4})\\.pdf$", Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: ImagePrefixRemoverAndDateOrdererApp <folder> <prefix>");
            System.exit(1);
        }

        String folderPath = args[0];
        String prefix     = args[1];

        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            System.err.println("Error: Not a valid directory: " + folderPath);
            System.exit(1);
        }

        System.out.println("=== Pass 1: Remove prefix \"" + prefix + "\" ===");
        removePrefixes(folder, prefix);

        System.out.println("\n=== Pass 2: Rename month-year PDFs to YYYY-MM.pdf ===");
        renameMonthYear(folder);
    }

    private static void removePrefixes(File folder, String prefix) {
        File[] files = folder.listFiles(f -> f.isFile() && f.getName().startsWith(prefix));

        if (files == null || files.length == 0) {
            System.out.println("No files found with prefix \"" + prefix + "\".");
            return;
        }

        int renamed = 0, skipped = 0, failed = 0;

        for (File file : files) {
            String oldName = file.getName();
            String newName = oldName.substring(prefix.length());

            if (newName.isEmpty()) {
                System.err.println("Skipping \"" + oldName + "\": result would be an empty filename.");
                skipped++;
                continue;
            }

            File newFile = new File(folder, newName);
            if (newFile.exists()) {
                System.err.println("Skipping \"" + oldName + "\": target \"" + newName + "\" already exists.");
                skipped++;
                continue;
            }

            if (file.renameTo(newFile)) {
                System.out.println("Renamed: " + oldName + " -> " + newName);
                renamed++;
            } else {
                System.err.println("Failed to rename: " + oldName);
                failed++;
            }
        }

        printSummary(renamed, skipped, failed);
    }

    private static void renameMonthYear(File folder) {
        File[] files = folder.listFiles(f -> f.isFile()
                && f.getName().toLowerCase().endsWith(".pdf"));

        if (files == null || files.length == 0) {
            System.out.println("No PDF files found.");
            return;
        }

        int renamed = 0, skipped = 0, failed = 0;

        for (File file : files) {
            String name = file.getName();
            Matcher m = MONTH_YEAR_PATTERN.matcher(name);

            if (!m.matches()) {
                System.out.println("Skipping (no match): " + name);
                skipped++;
                continue;
            }

            String monthToken = m.group(1).toLowerCase();
            String yearToken  = m.group(2);

            String month = MONTH_MAP.get(monthToken);
            if (month == null) {
                System.out.println("Skipping (unknown month \"" + m.group(1) + "\"): " + name);
                skipped++;
                continue;
            }

            // Expand 2-digit year: 00-29 -> 2000s, 30-99 -> 1900s
            String year;
            if (yearToken.length() == 2) {
                int y = Integer.parseInt(yearToken);
                year = (y <= 29 ? "20" : "19") + yearToken;
            } else {
                year = yearToken;
            }

            String newName = year + "-" + month + ".pdf";
            File newFile = new File(folder, newName);

            if (newFile.exists()) {
                System.err.println("Skipping \"" + name + "\": target \"" + newName + "\" already exists.");
                skipped++;
                continue;
            }

            if (file.renameTo(newFile)) {
                System.out.println("Renamed: " + name + " -> " + newName);
                renamed++;
            } else {
                System.err.println("Failed to rename: " + name);
                failed++;
            }
        }

        printSummary(renamed, skipped, failed);
    }

    private static void printSummary(int renamed, int skipped, int failed) {
        System.out.println("Done. Renamed: " + renamed
                + ", Skipped: " + skipped
                + ", Failed: " + failed);
    }
}