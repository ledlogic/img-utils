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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /**
     * Returns {width, height} of a JPEG byte array using ImageIO header-only reading
     * (does not decode the full image). Returns {-1, -1} if dimensions cannot be read.
     */
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

    /** Returns true if the image bytes have at least one dimension > 2000px. */
    static boolean isLargeEnough(byte[] bytes) {
        int[] dim = getImageDimensions(bytes);
        return dim[0] > 2000 || dim[1] > 2000;
    }

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
                case "--threads":      threads     = Integer.parseInt(args[++i]); break;
                case "--timeout":      timeoutSecs = Integer.parseInt(args[++i]); break;
                case "--output-dir":   outputBase  = args[++i]; break;
                case "--also-response": alsoResponse = true; break;
                case "--verbose":      verbose = true; break;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    printHelp(); System.exit(1);
            }
        }

        Path harFile = Paths.get(harPath);
        if (!Files.exists(harFile)) {
            System.err.println("ERROR: File not found: " + harPath); System.exit(1);
        }

        String harBaseName = harFile.getFileName().toString()
                .replaceAll("\\.[Hh][Aa][Rr]$", "").replaceAll("[^a-zA-Z0-9._\\-]", "_");
        Path outputDir = Paths.get(outputBase, harBaseName + "_images");
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
            if (isJpegUrl(pair[0], pair[1])) jpegUrls.add(pair[0]);
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>(jpegUrls);
        jpegUrls = new ArrayList<>(seen);
        System.out.println("found " + jpegUrls.size() + " unique JPEG URL(s)");

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
            System.out.println("\nNo JPEG images found in this HAR file."); System.exit(0);
        }

        System.out.printf("%nDownloading %d image(s)...%n%n", jpegUrls.size());

        final int finalTimeout = timeoutSecs;
        final boolean finalVerbose = verbose;
        final List<String> finalJpegUrls = jpegUrls;

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(finalTimeout))
                .build();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger ok       = new AtomicInteger();
        AtomicInteger failedC  = new AtomicInteger();
        AtomicInteger skipped  = new AtomicInteger();
        AtomicInteger filtered = new AtomicInteger(); // too small (<= 2000px on both axes)
        List<Future<?>> futures = new ArrayList<>();

        for (int idx = 0; idx < finalJpegUrls.size(); idx++) {
            final int i   = idx;
            final String url = finalJpegUrls.get(i);
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
                        if (!isLargeEnough(body)) {
                            int[] dim = getImageDimensions(body);
                            filtered.incrementAndGet();
                            if (finalVerbose)
                                System.out.printf("  [SMALL] %dx%d, deleted — %s%n", dim[0], dim[1], filename);
                            return; // don't write; file was never created
                        }
                        Files.write(dest, body);
                        int n = ok.incrementAndGet();
                        if (finalVerbose)
                            System.out.printf("  [OK]    %s (%,d bytes)%n", filename, body.length);
                        else
                            System.out.printf("  [%4d/%d] %s%n", n, finalJpegUrls.size(), filename);
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

        System.out.println("\n=== Summary ===");
        System.out.printf("  Downloaded : %d%n", ok.get());
        System.out.printf("  Too small  : %d  (width <= 2000px and height <= 2000px, not saved)%n", filtered.get());
        System.out.printf("  Failed     : %d%n", failedC.get());
        System.out.printf("  Skipped    : %d%n", skipped.get());
        if (alsoResponse) System.out.printf("  Base64 saved: %d%n", savedBase64);
        System.out.println("  Output dir : " + outputDir.toAbsolutePath());
    }

    static void printHelp() {
        System.out.println("Usage:");
        System.out.println("  java HarImageDownloader <file.har> [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --threads <n>        Parallel download threads (default: 4)");
        System.out.println("  --timeout <secs>     HTTP timeout per request  (default: 30)");
        System.out.println("  --output-dir <dir>   Parent dir for output     (default: .)");
        System.out.println("  --also-response      Also save base64-encoded JPEGs from response bodies");
        System.out.println("  --verbose            Print each URL and byte count");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java HarImageDownloader mysession.har --threads 8 --verbose");
        System.out.println("  java HarImageDownloader archive.har --also-response --output-dir ~/Downloads");
    }
}