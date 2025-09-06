package com.github.ledlogic.webp;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Assumption, running on a system with image magick installed
 * Ex. install cygwin, install image magick
 * 
 * @see https://www.cygwin.com/install.html
 * @see https://imagemagick.org/script/download.php#windows
 */
public class HeicConverterApp {
	
	private static HeicFilenameFilter filter = new HeicFilenameFilter();
	
	public static void main(String[] args) throws IOException, InterruptedException {
		String attackFolder = "G:\\My Drive\\Ebay\\20250901";
		File attackFolderFile = new File(attackFolder);
		
		File[] files = attackFolderFile.listFiles(filter);

		String pattern = "yyyyMMddHHmm";
		SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
		String date = simpleDateFormat.format(new Date());
		
		long cnt = 500;
		for (File file: files) {
			String inputFile = file.getName();
			String outputFile = date + (cnt++) + ".png";			
			
			String inPath = attackFolder + "\\" + inputFile;
			String outPath = attackFolder + "\\" + outputFile;
			
			HeicConverterService.convertWebFile(inPath, outPath);
		}
	}
}