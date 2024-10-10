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
	private static JpgFilenameFilter jpgFilter = new JpgFilenameFilter();
	
	public static void main(String[] args) throws IOException, InterruptedException {
		
		// attack folder
		String attackFolder = "G:\\My Drive\\Games\\Traveller\\Traveller Scenarios\\Traveller CHEFs\\Portraits";
		File attackFolderFile = new File(attackFolder);
				
		// get filenames
		List<String> names = new ArrayList<String>();
		
		File[] files1 = attackFolderFile.listFiles(pngFilter);
		for (File file: files1) {
			String name = file.getName();
			names.add(name);
		}
		
		File[] files2 = attackFolderFile.listFiles(jpgFilter);
		for (File file: files2) {
			String name = file.getName();
			names.add(name);
		}
		
		// sort
		Collections.sort(names);

		boolean square = false;
		float ratio = 1.25f;
		int dim = Math.round(96f * ratio);

		// generate html page
		List<String> html = new ArrayList<String>();
		html.add("<html>");
		for (String name: names) {
			String truncName = StringUtils.substringBefore(name, ".");
			html.add("<div style=\"position: relative; width: " + dim + "px; height: " + (square ? dim : 200) + "px; border: 1px solid #ccc; float: left; overflow:hidden;\">");	
			html.add("<img src=\"" + name + "\" alt=\"" + truncName + "\" title=\"" + truncName + "\" width=\"" + dim + "\" />");	
			html.add("<span style=\"position: relative; margin-top: -10px; font-family: Bahnschrift; font-size: 10px; font-weight: bold; display: inline-block; background: #fff;\">" + truncName + "</span>");	
			html.add("</div>");	
		}
		html.add("</html>");
		
		File outFile = new File(attackFolder + "\\index-" + dim + ".html");
		FileUtils.write(outFile, StringUtils.join(html, "\n"), "UTF-8");
		
		String[] cmd = { "\"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\" \"file:///" + outFile + "\"" };
		Process proc = Runtime.getRuntime().exec(cmd);
	}
}