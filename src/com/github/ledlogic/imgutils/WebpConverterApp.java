package com.github.ledlogic.imgutils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Converts .webp images to .png.
 *
 * Usage: WebpConverterApp [--new-names] <file-or-folder>
 *
 *   <file-or-folder>  a single .webp file, or a folder whose .webp files are all converted
 *   --new-names       name outputs yyyyMMddHHmm + counter instead of reusing the source name
 */
public class WebpConverterApp {

	private static final String WEBP_EXT = ".webp";
	private static final String PNG_EXT = ".png";

	private static WebpFilenameFilter filter = new WebpFilenameFilter();

	public static void main(String[] args) {
		boolean newNames = false;
		String path = null;

		for (String arg : args) {
			if ("--new-names".equals(arg)) {
				newNames = true;
			} else if ("-h".equals(arg) || "--help".equals(arg)) {
				usage();
				return;
			} else if (path == null) {
				path = arg;
			} else {
				System.err.println("Unexpected argument: " + arg);
				usage();
				System.exit(1);
			}
		}

		if (path == null) {
			usage();
			System.exit(1);
		}

		File target = new File(path);
		File[] files;

		if (target.isDirectory()) {
			files = target.listFiles(filter);
			if (files == null) {
				System.err.println("Unable to read folder: " + target.getAbsolutePath());
				System.exit(1);
			}
		} else if (target.isFile()) {
			if (!target.getName().toLowerCase().endsWith(WEBP_EXT)) {
				System.err.println("Not a .webp file: " + target.getAbsolutePath());
				System.exit(1);
			}
			files = new File[] { target };
		} else {
			System.err.println("Path not found: " + target.getAbsolutePath());
			System.exit(1);
			return;
		}

		if (files.length == 0) {
			System.out.println("No .webp files found in " + target.getAbsolutePath());
			return;
		}

		String date = new SimpleDateFormat("yyyyMMddHHmm").format(new Date());
		long cnt = 500;
		int converted = 0;
		int failed = 0;

		for (File file : files) {
			String name = file.getName();
			String baseName = newNames ? date + (cnt++) : stripExtension(name);
			File outFile = new File(file.getParentFile(), baseName + PNG_EXT);

			try {
				WebpConverterService.convertWebFile(file.getAbsolutePath(), outFile.getAbsolutePath());
				System.out.println(name + " -> " + outFile.getName());
				converted++;
			} catch (Exception e) {
				System.err.println("FAILED " + name + ": " + e.getMessage());
				failed++;
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}

		System.out.println("Converted " + converted + ", failed " + failed);
		if (failed > 0) {
			System.exit(2);
		}
	}

	private static String stripExtension(String name) {
		if (name.toLowerCase().endsWith(WEBP_EXT)) {
			return name.substring(0, name.length() - WEBP_EXT.length());
		}
		return name;
	}

	private static void usage() {
		System.out.println("Usage: WebpConverterApp [--new-names] <file-or-folder>");
		System.out.println("  <file-or-folder>  a .webp file, or a folder of .webp files");
		System.out.println("  --new-names       name outputs yyyyMMddHHmm+counter instead of the source name");
	}
}