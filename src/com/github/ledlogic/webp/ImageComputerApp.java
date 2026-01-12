package com.github.ledlogic.webp;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

public class ImageComputerApp {
	
	private static PngFilenameFilter pngFilter = new PngFilenameFilter();
	private static JpgFilenameFilter jpgFilter = new JpgFilenameFilter();
	
	public static void main(String[] args) throws IOException, InterruptedException {
		
		// attack folder
		String attackFolder = "C:\\Dev\\workspace-2024\\mgt2-geomorph-assembler\\img\\geomorphs";
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

		// generate json page
		List<String> json = new ArrayList<String>();
		json.add("[");
		int cnt = 0;
		for (String name: names) {
			if (cnt++ > 0) {
				json.add(",");
			}
			
	        BufferedImage img = null;
			File inFile = new File(attackFolder + "\\" + name);
	        try{
	            img = ImageIO.read(inFile);
	        } catch(IOException e){
	            System.out.println(e);
	        }

	        int width = img.getWidth();
	        int height = img.getHeight();
			
			json.add("{ \"name\": \"" + name + "\", \"width\": " + width + ",\"height\": " + height + "}");
		}
		json.add("]");
		
		File outFile = new File(attackFolder + "\\index.json");
		FileUtils.write(outFile, StringUtils.join(json, "\n"), "UTF-8");
		
		String[] cmd = { "\"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\" \"file:///" + outFile + "\"" };
		Process proc = Runtime.getRuntime().exec(cmd);
	}
}