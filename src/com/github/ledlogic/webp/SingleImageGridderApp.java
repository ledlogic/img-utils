package com.github.ledlogic.webp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

public class SingleImageGridderApp {
	
	public static void main(String[] args) throws IOException, InterruptedException {
		
		// Single image file path
		String attackFile = "G:\\My Drive\\Games\\Spaceballs\\the-shwartz.png";
		File imageFile = new File(attackFile);
		
		if (!imageFile.exists()) {
			System.err.println("Error: Image file not found at " + attackFile);
			return;
		}
		
		String name = imageFile.getName();
		String truncName = StringUtils.substringBefore(name, ".");
		
		// Letter portrait dimensions: 8.5" x 11"
		// 27.94mm = 1.1 inches = 105.6 pixels at 96 DPI
		// Calculate how many images fit on Letter portrait
		float imageWidthInches = 1.1f;
		float imageHeightInches = 1.1f;
		
		float pageWidthInches = 8.5f;
		float pageHeightInches = 11.0f;
		
		// Add margins (0.5 inch on each side)
		float marginInches = 0.5f;
		float printableWidthInches = pageWidthInches - (2 * marginInches);
		float printableHeightInches = pageHeightInches - (2 * marginInches);
		
		int cols = (int) Math.floor(printableWidthInches / imageWidthInches);
		int rows = (int) Math.floor(printableHeightInches / imageHeightInches);
		
		// Convert to pixels at 96 DPI (standard screen DPI)
		int imageWidthPx = Math.round(imageWidthInches * 96f);
		int imageHeightPx = Math.round(imageHeightInches * 96f);
		int pageWidthPx = Math.round(pageWidthInches * 96f);
		int pageHeightPx = Math.round(pageHeightInches * 96f);
		int marginPx = Math.round(marginInches * 96f);
		
		System.out.println("Grid layout: " + cols + " columns x " + rows + " rows");
		System.out.println("Total images per page: " + (cols * rows));
		System.out.println("Image size: " + imageWidthPx + "px x " + imageHeightPx + "px");
		
		// Generate HTML page with print styles
		List<String> html = new ArrayList<String>();
		html.add("<!DOCTYPE html>");
		html.add("<html>");
		html.add("<head>");
		html.add("<meta charset=\"UTF-8\">");
		html.add("<title>Image Grid - " + truncName + "</title>");
		html.add("<style>");
		html.add("@page {");
		html.add("  size: letter portrait;");
		html.add("  margin: " + marginInches + "in;");
		html.add("}");
		html.add("body {");
		html.add("  margin: 0;");
		html.add("  padding: 0;");
		html.add("  font-family: Arial, sans-serif;");
		html.add("}");
		html.add(".page {");
		html.add("  width: " + pageWidthPx + "px;");
		html.add("  height: " + pageHeightPx + "px;");
		html.add("  padding: " + marginPx + "px;");
		html.add("  box-sizing: border-box;");
		html.add("  page-break-after: always;");
		html.add("}");
		html.add(".grid-container {");
		html.add("  display: grid;");
		html.add("  grid-template-columns: repeat(" + cols + ", " + imageWidthPx + "px);");
		html.add("  grid-template-rows: repeat(" + rows + ", " + imageHeightPx + "px);");
		html.add("  gap: 0;");
		html.add("}");
		html.add(".grid-item {");
		html.add("  width: " + imageWidthPx + "px;");
		html.add("  height: " + imageHeightPx + "px;");
		html.add("  border: 1px solid #ccc;");
		html.add("  box-sizing: border-box;");
		html.add("  overflow: hidden;");
		html.add("}");
		html.add(".grid-item img {");
		html.add("  width: 100%;");
		html.add("  height: 100%;");
		html.add("  object-fit: contain;");
		html.add("}");
		html.add("@media print {");
		html.add("  .grid-item {");
		html.add("    border: 1px solid #000;");
		html.add("    -webkit-print-color-adjust: exact;");
		html.add("    print-color-adjust: exact;");
		html.add("  }");
		html.add("}");
		html.add("</style>");
		html.add("</head>");
		html.add("<body>");
		
		// Create one page with grid
		html.add("<div class=\"page\">");
		html.add("<div class=\"grid-container\">");
		
		int totalImages = cols * rows;
		for (int i = 0; i < totalImages; i++) {
			html.add("<div class=\"grid-item\">");
			html.add("<img src=\"" + name + "\" alt=\"" + truncName + "\" />");
			html.add("</div>");
		}
		
		html.add("</div>"); // close grid-container
		html.add("</div>"); // close page
		
		html.add("</body>");
		html.add("</html>");
		
		// Save to same directory as the image
		File outFile = new File(imageFile.getParent() + "\\image-grid-" + truncName + ".html");
		FileUtils.write(outFile, StringUtils.join(html, "\n"), "UTF-8");
		
		System.out.println("HTML file created: " + outFile.getAbsolutePath());
		
		// Open in Chrome
		String[] cmd = { "\"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\" \"file:///" + outFile.getAbsolutePath() + "\"" };
		Process proc = Runtime.getRuntime().exec(cmd);
	}
}