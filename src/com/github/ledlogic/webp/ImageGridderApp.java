package com.github.ledlogic.webp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

public class ImageGridderApp {
	
	private static PngFilenameFilter pngFilter = new PngFilenameFilter();
	
	public static void main(String[] args) throws IOException, InterruptedException {
		
		// input folders - add or remove folders as needed
		String[] inputFolderList = {
			"G:\\My Drive\\Games\\Farsight Games\\Pressure\\character - images",
			"G:\\My Drive\\Games\\Farsight Games\\Pressure\\npc-images",
		};

		// get files from all input folders
		List<File> files = new ArrayList<File>();

		for (String inputFolder : inputFolderList) {
			File inputFolderFile = new File(inputFolder);

			File[] files1 = inputFolderFile.listFiles(pngFilter);
			if (files1 != null) {
				for (File file: files1) {
					if (file.getName().startsWith("monster-09")) {
						files.add(file);
					}
				}
			}
		}

		// sort by filename
		Collections.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

		// use the first folder as the output location
		String attackFolder = inputFolderList[0];

		// generate html page
		List<String> html = new ArrayList<String>();
		html.add("<html>");
		html.add("<head><style>");
		html.add("  * { page-break-inside: avoid; page-break-before: avoid; page-break-after: avoid; }");
		html.add("  body { margin: 0; padding: 0; }");
		html.add("</style></head>");
		html.add("<body>");

		// 1in x 1in section (96px at 96 DPI) - loop twice
		int dim1 = 96;
		for (int pass = 0; pass < 2; pass++) {
			addImageGrid(html, files, dim1);
			html.add("<div style=\"clear: both; margin-bottom: 0.25in;\"></div>");
		}

		// 2in x 2in section (192px at 96 DPI) - monster files only, loop twice
		List<File> monsterFiles = new ArrayList<File>();
		for (File file : files) {
			if (file.getName().startsWith("monster")) {
				monsterFiles.add(file);
			}
		}
		int dim2 = 192;
		for (int pass = 0; pass < 2; pass++) {
			addImageGrid(html, monsterFiles, dim2);
			html.add("<div style=\"clear: both; margin-bottom: 0.25in;\"></div>");
		}

		html.add("</body>");
		html.add("</html>");
		
		File outFile = new File(attackFolder + "\\index-grid.html");
		FileUtils.write(outFile, StringUtils.join(html, "\n"), "UTF-8");
		
		String[] cmd = { "\"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\" \"file:///" + outFile + "\"" };
		Process proc = Runtime.getRuntime().exec(cmd);
	}

	private static void addImageGrid(List<String> html, List<File> files, int dim) {
		for (File file: files) {
			String name = file.getName();
			String truncName = StringUtils.substringBefore(name, ".");
			String srcPath = file.getAbsolutePath().replace("\\", "/");
			html.add("<div style=\"position: relative; width: " + dim + "px; height: " + dim + "px; border: 1px solid #ccc; float: left; overflow: hidden;\">");
			html.add("<img src=\"file:///" + srcPath + "\" alt=\"" + truncName + "\" title=\"" + truncName + "\" style=\"width: 100%; height: 100%; object-fit: cover; display: block;\" />");
			html.add("<span style=\"position: absolute; bottom: 0; left: 0; right: 0; font-family: Bahnschrift; font-size: 10px; font-weight: bold; background: #fff; text-align: center;\">" + truncName + "</span>");
			html.add("</div>");
		}
	}
}
