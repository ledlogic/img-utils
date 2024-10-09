package com.github.ledlogic.webp;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class GrayscalerApp {
	
	private static PngFilenameFilter pngFilter = new PngFilenameFilter();
	private static JpgFilenameFilter jpgFilter = new JpgFilenameFilter();
	
	public static void main(String[] args) throws IOException, InterruptedException {
		String attackFolder = "G:\\My Drive\\Games\\Traveller\\Traveller Scenarios\\Traveller Solomani Rim\\04-Ludmilla\\Art";
		File attackFolderFile = new File(attackFolder);
		
		File[] pngFiles = attackFolderFile.listFiles(pngFilter);
		File[] jpgFiles = attackFolderFile.listFiles(jpgFilter);

		String pattern = "yyyyMMddHHmm";
		SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
		String date = simpleDateFormat.format(new Date());
		
		long cnt = 200;
		for (File file: pngFiles) {
			processFile(attackFolder, date, cnt++, file);
		}
		for (File file: jpgFiles) {
			processFile(attackFolder, date, cnt++, file);
		}
	}

	private static void processFile(String attackFolder, String date, long cnt, File file)
			throws IOException, InterruptedException {
		String inputFile = file.getName();
		String outputFile = date + cnt + ".png";			
		
		String inPath = attackFolder + "\\" + inputFile;
		String outPath = attackFolder + "\\" + outputFile;
		
		GrayScalerService.convertWebFile(inPath, outPath);
	}
}