package com.github.ledlogic.webp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

public class HarImageApp {

    static List<String[]> extractUrlMimePairs(String json) {
        List<String[]> pairs = new ArrayList<>();
        Pattern urlPat  = Pattern.compile("\"url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Pattern mimePat = Pattern.compile("\"mimeType\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher um = urlPat.matcher(json);
        while (um.find()) {
            String url = unescape(um.group(1));
            int searchEnd = Math.min(um.end() + 2000, json.length());
            String slice = json.substring(um.end(), searchEnd);
            Matcher mm = mimePat.matcher(slice);
            String mime = "";
            if (mm.find()) mime = unescape(mm.group(1));
            pairs.add(new String[]{url, mime});
        }
        return pairs;
    }

    static List<String[]> extractBase64Images(String json) {
        List<String[]> results = new ArrayList<>();
        Pattern p = Pattern.compile(
            "\"mimeType\"\\s*:\\s*\"(image/jpe?g)\"[^}]{0,500}?\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
            Pattern.DOTALL);
        Matcher m = p.matcher(json);
        while (m.find()) results.add(new String[]{m.group(1), m.group(2)});
        return results;
    }

    static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
    }

    static boolean isJpegUrl(String url, String mime) {
        String lower = url.toLowerCase();
        boolean urlHint  = lower.contains(".jpg") || lower.contains(".jpeg");
        boolean mimeHint = mime.toLowerCase().contains("image/jpeg")
                        || mime.toLowerCase().contains("image/jpg");
        return urlHint || mimeHint;
    }

    static String urlToFilename(String rawUrl, int index) {
        try {
            URI uri = new URI(rawUrl);
            String path = uri.getPath();
            if (path == null || path.isEmpty()) path = "/image";
            String name = Paths.get(path).getFileName().toString();
            name = name.replaceAll("[?#&=].*", "").replaceAll("[^a-zA-Z0-9._\\-]", "_");
            if (!name.toLowerCase().endsWith(".jpg") && !name.toLowerCase().endsWith(".jpeg"))
                name = name + ".jpg";
            return String.format("%04d_%s", index, name);
        } catch (Exception e) {
            return String.format("%04d_image.jpg", index);
        }
    }

    static int[] getImageDimensions(byte[] bytes) {
        try (MemoryCacheImageInputStream iis =
                new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("jpeg").next();
            reader.setInput(iis, true, true);
            int w = reader.getWidth(0);
            int h = reader.getHeight(0);
            reader.dispose();
            return new int[]{w, h};
        } catch (IOException | java.util.NoSuchElementException e) {
            return new int[]{-1, -1};
        }
    }

    static boolean isLargeEnough(byte[] bytes) {
        int[] dim = getImageDimensions(bytes);
        return dim[0] > 1000 || dim[1] > 1000;
    }

    /** Returns true if the URL is a 720x720 preview image. */
    static boolean is720(String url) {
        return url.contains("720X720") || url.contains("720x720");
    }

    // ── Derive the output directory name from a HAR file path ─────────────────
    static String harBaseName(Path harFile) {
        return harFile.getFileName().toString()
                .replaceAll("\\.[Hh][Aa][Rr]$", "")
                .replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    static Path outputDirForHar(Path harFile, String outputBase) {
        return Paths.get(outputBase, harBaseName(harFile) + "_images");
    }

    // Normalize a path argument: strip any surrounding quotes, stray quotes,
    // and trailing backslashes that Windows cmd/PowerShell can inject.
    static String cleanPath(String s) {
        if (s == null) return s;
        s = s.replace("\"", "").trim();   // remove ALL quote characters
        while (s.endsWith("\\") || s.endsWith("/"))
            s = s.substring(0, s.length() - 1);  // remove trailing separators
        return s;
    }

    // ── Collect HAR files to process ──────────────────────────────────────────
    static List<Path> collectHarFiles(String harPath, String outputBase) throws IOException {
        harPath    = cleanPath(harPath);
        outputBase = cleanPath(outputBase);
        Path p = Paths.get(harPath);
        if (!Files.exists(p)) {
            System.err.println("ERROR: Path not found: " + harPath);
            System.exit(1);
        }

        List<Path> harFiles = new ArrayList<>();

        if (Files.isDirectory(p)) {
            // Gather all *.har files in the folder (non-recursive)
            try (Stream<Path> stream = Files.list(p)) {
                List<Path> all = stream
                        .filter(f -> Files.isRegularFile(f))
                        .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".har"))
                        .sorted()
                        .collect(Collectors.toList());
                for (Path harFile : all) {
                    Path outDir = outputDirForHar(harFile, outputBase);
                    if (Files.exists(outDir)) {
                        System.out.printf("  [SKIP]  %s  (output dir already exists: %s)%n",
                                harFile.getFileName(), outDir.getFileName());
                    } else {
                        harFiles.add(harFile);
                    }
                }
            }
            if (harFiles.isEmpty()) {
                System.out.println("No new HAR files to process in: " + p.toAbsolutePath());
                System.exit(0);
            }
            System.out.printf("%nFound %d HAR file(s) to process.%n%n", harFiles.size());
        } else {
            // Single file — check if already processed
            Path outDir = outputDirForHar(p, outputBase);
            if (Files.exists(outDir)) {
                System.out.printf("Output directory already exists: %s%n", outDir.toAbsolutePath());
                System.out.printf("To re-process, delete that directory first.%n");
                System.exit(0);
            }
            harFiles.add(p);
        }

        return harFiles;
    }

    // ── Process a single HAR file ──────────────────────────────────────────────
    static void processHar(Path harFile, String outputBase, int threads, int timeoutSecs,
                           boolean alsoResponse, boolean verbose) throws Exception {

        Path outputDir = outputDirForHar(harFile, outputBase);
        Files.createDirectories(outputDir);

        System.out.println("=== HAR Image Downloader ===");
        System.out.println("HAR file  : " + harFile.toAbsolutePath());
        System.out.println("Output dir: " + outputDir.toAbsolutePath());
        System.out.println("Threads   : " + threads);
        System.out.println();

        System.out.print("Reading HAR file... ");
        String harJson = Files.readString(harFile);
        System.out.printf("done (%.1f MB)%n", harJson.length() / 1024.0 / 1024.0);

        System.out.print("Extracting image URLs... ");
        List<String[]> allPairs = extractUrlMimePairs(harJson);
        List<String> jpegUrls = new ArrayList<>();
        for (String[] pair : allPairs) {
            if (!isJpegUrl(pair[0], pair[1])) continue;
            String url = pair[0];
            String lower = url.toLowerCase();
            // Skip obvious UI thumbnails by size prefix or path segment
            if (lower.contains("70x70") || lower.contains("70X70"))    continue;
            if (lower.contains("230x230") || lower.contains("230X230")) continue;
            if (lower.contains("/object/"))                              continue;
            jpegUrls.add(url);
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>(jpegUrls);
        jpegUrls = new ArrayList<>(seen);
        // Separate 720x720 URLs from the rest for reporting
        long count720 = jpegUrls.stream().filter(u -> is720(u)).count();
        System.out.printf("found %d unique JPEG URL(s) (%d are 720x720)%n",
                jpegUrls.size(), count720);

        int savedBase64 = 0;
        if (alsoResponse) {
            System.out.print("Extracting embedded base64 JPEG response bodies... ");
            List<String[]> b64list = extractBase64Images(harJson);
            System.out.println("found " + b64list.size());
            for (int i = 0; i < b64list.size(); i++) {
                String b64 = b64list.get(i)[1].replaceAll("\\s+", "");
                try {
                    byte[] bytes = Base64.getDecoder().decode(b64);
                    if (!isLargeEnough(bytes)) {
                        int[] dim = getImageDimensions(bytes);
                        if (verbose) System.out.printf("  [base64] SMALL %dx%d, skipped entry %d%n", dim[0], dim[1], i);
                        continue;
                    }
                    Path dest = outputDir.resolve(String.format("b64_%04d.jpg", i + 1));
                    Files.write(dest, bytes);
                    savedBase64++;
                    if (verbose) System.out.printf("  [base64] saved -> %s%n", dest.getFileName());
                } catch (IllegalArgumentException ex) {
                    System.err.printf("  [base64] skipped entry %d (not valid base64)%n", i);
                }
            }
        }

        if (jpegUrls.isEmpty() && savedBase64 == 0) {
            System.out.println("\nNo JPEG images found in this HAR file.");
            // Remove the empty output dir we just created
            Files.deleteIfExists(outputDir);
            return;
        }

        System.out.printf("%nDownloading %d image(s) — Pass 1: large images (>1000px)...%n%n", jpegUrls.size());

        final int finalTimeout       = timeoutSecs;
        final boolean finalVerbose   = verbose;
        final List<String> finalUrls = jpegUrls;

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(finalTimeout))
                .build();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger ok       = new AtomicInteger();
        AtomicInteger failedC  = new AtomicInteger();
        AtomicInteger skipped  = new AtomicInteger();
        AtomicInteger filtered = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        // Collect small images in case we need a fallback pass
        // key = url, value = {bytes, maxDim}
        ConcurrentHashMap<String, Object[]> smallImages = new ConcurrentHashMap<>();

        for (int idx = 0; idx < finalUrls.size(); idx++) {
            final int i      = idx;
            final String url = finalUrls.get(i);
            futures.add(pool.submit(() -> {
                String filename = urlToFilename(url, i + 1);
                Path dest = outputDir.resolve(filename);
                if (Files.exists(dest)) {
                    if (finalVerbose) System.out.printf("  [SKIP]  %s (already exists)%n", filename);
                    skipped.incrementAndGet(); return;
                }
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(finalTimeout))
                            .header("User-Agent", "HarImageDownloader/1.0")
                            .GET().build();
                    HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                    if (resp.statusCode() == 200) {
                        byte[] body = resp.body();
                        int[] dim = getImageDimensions(body);
                        if (!isLargeEnough(body)) {
                            filtered.incrementAndGet();
                            if (finalVerbose)
                                System.out.printf("  [SMALL] %dx%d — %s%n", dim[0], dim[1], filename);
                            // Only keep 720x720 images for fallback — skip other small sizes
                            if (is720(url)) {
                                int maxDim = Math.max(dim[0], dim[1]);
                                if (maxDim <= 0) maxDim = body.length;
                                smallImages.put(url, new Object[]{body, maxDim, filename});
                            }
                            return;
                        }
                        Files.write(dest, body);
                        int n = ok.incrementAndGet();
                        if (finalVerbose)
                            System.out.printf("  [OK]    %s (%,d bytes)%n", filename, body.length);
                        else
                            System.out.printf("  [%4d/%d] %s%n", n, finalUrls.size(), filename);
                    } else {
                        failedC.incrementAndGet();
                        System.out.printf("  [FAIL]  HTTP %d — %s%n", resp.statusCode(), url);
                    }
                } catch (Exception ex) {
                    failedC.incrementAndGet();
                    System.out.printf("  [ERR]   %s — %s%n", ex.getClass().getSimpleName(), url);
                    if (finalVerbose) ex.printStackTrace(System.err);
                }
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (ExecutionException e) { /* already logged */ }
        }
        pool.shutdown();

        // ── Fallback pass: if no large images saved, save ALL small images ──────
        int fallbackSaved = 0;
        System.out.printf("%nPass 1 complete: %d large saved, %d small collected.%n",
                ok.get(), smallImages.size());
        if (ok.get() == 0 && !smallImages.isEmpty()) {
            // Find best available dimension for reporting
            int bestDim = smallImages.values().stream()
                    .mapToInt(v -> (int) v[1])
                    .max().orElse(0);
            System.out.printf("%nNo large images found. Falling back — saving all %d small image(s) (%dpx).%n%n",
                    smallImages.size(), bestDim);

            int fallbackIdx = 1;
            for (Object[] entry : smallImages.values()) {
                byte[] body     = (byte[]) entry[0];
                String filename = (String) entry[2];
                Path dest = outputDir.resolve(filename);
                if (!Files.exists(dest)) {
                    Files.write(dest, body);
                    fallbackSaved++;
                    System.out.printf("  [FALLBACK %d] %s%n", fallbackIdx++, filename);
                }
            }
        }

        System.out.println("\n=== Summary ===");
        System.out.printf("  Downloaded   : %d%n", ok.get());
        if (fallbackSaved > 0)
            System.out.printf("  Fallback saved: %d  (best available size, no >1000px images found)%n", fallbackSaved);
        System.out.printf("  Too small    : %d  (filtered in pass 1)%n", filtered.get());
        System.out.printf("  Failed       : %d%n", failedC.get());
        System.out.printf("  Skipped      : %d%n", skipped.get());
        if (alsoResponse) System.out.printf("  Base64 saved : %d%n", savedBase64);
        System.out.println("  Output dir   : " + outputDir.toAbsolutePath());
        System.out.println();
    }

    // ── main ──────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            printHelp(); System.exit(0);
        }

        String harPath      = args[0];
        int    threads      = 4;
        int    timeoutSecs  = 30;
        String outputBase   = ".";
        boolean alsoResponse = false;
        boolean verbose      = false;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--threads":       threads      = Integer.parseInt(args[++i]); break;
                case "--timeout":       timeoutSecs  = Integer.parseInt(args[++i]); break;
                case "--output-dir":    outputBase   = args[++i]; break;
                case "--also-response": alsoResponse = true; break;
                case "--verbose":       verbose      = true; break;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    printHelp(); System.exit(1);
            }
        }

        harPath    = cleanPath(harPath);
        outputBase = cleanPath(outputBase);
        List<Path> harFiles = collectHarFiles(harPath, outputBase);

        for (int i = 0; i < harFiles.size(); i++) {
            Path harFile = harFiles.get(i);
            if (harFiles.size() > 1) {
                System.out.printf("─── [%d/%d] %s ───%n%n",
                        i + 1, harFiles.size(), harFile.getFileName());
            }
            processHar(harFile, outputBase, threads, timeoutSecs, alsoResponse, verbose);
        }

        if (harFiles.size() > 1) {
            System.out.printf("=== All done. Processed %d HAR file(s). ===%n", harFiles.size());
        }
    }

    static void printHelp() {
        System.out.println("Usage:");
        System.out.println("  java HarImageApp <file.har|folder> [options]");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  file.har   Process a single HAR file.");
        System.out.println("  folder     Process all *.har files in the folder.");
        System.out.println("             HAR files whose output directory already exists are skipped.");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --threads <n>        Parallel download threads (default: 4)");
        System.out.println("  --timeout <secs>     HTTP timeout per request  (default: 30)");
        System.out.println("  --output-dir <dir>   Parent dir for output     (default: .)");
        System.out.println("  --also-response      Also save base64-encoded JPEGs from response bodies");
        System.out.println("  --verbose            Print each URL and byte count");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java HarImageApp mysession.har --threads 8 --verbose");
        System.out.println("  java HarImageApp ./har-files/ --output-dir ~/Downloads");
        System.out.println("  java HarImageApp ./har-files/ --output-dir ~/Downloads --also-response");
    }
}