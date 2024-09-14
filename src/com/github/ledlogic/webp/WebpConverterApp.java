package com.github.ledlogic.webp;

import java.io.File;
import java.io.IOException;

public class WebpConverterApp {
	
	private static WebpFilenameFilter filter = new WebpFilenameFilter();
	
	public static void main(String[] args) throws IOException, InterruptedException {
		String attackFolder = "G:\\My Drive\\Games\\Traveller\\Traveller Scenarios\\Traveller CHEFs\\Art";
		File attackFolderFile = new File(attackFolder);
		
		File[] files = attackFolderFile.listFiles(filter);
		int cnt = 400;
		for (File file: files) {
			String inputFile = file.getName();
			String outputFile = (cnt++) + ".png";			
			
			String inPath = attackFolder + "\\" + inputFile;
			String outPath = attackFolder + "\\" + outputFile;
			
			WebpConverterService.convertWebFile(inPath, outPath);
		}
	}
}