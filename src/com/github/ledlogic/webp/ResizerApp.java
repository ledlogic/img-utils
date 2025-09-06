package com.github.ledlogic.webp;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ResizerApp {
	
	private static JpgFilenameFilter jpgFilter = new JpgFilenameFilter();
	
	public static void main(String[] args) throws IOException, InterruptedException {
		String attackFolder = "G:\\My Drive\\Games\\Traveller\\Traveller Art\\Gals\\temp";
		String outputFolder = "G:\\My Drive\\Games\\Traveller\\Traveller Art\\Gals\\temp";
		File attackFolderFile = new File(attackFolder);
		File outputFolderFile = new File(outputFolder);
		
		if (!outputFolderFile.exists()) {
			outputFolderFile.mkdirs();
		}
		
		File[] jpgFiles = attackFolderFile.listFiles(jpgFilter);

		String pattern = "yyyyMMddHHmm";
		SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
		String date = simpleDateFormat.format(new Date());
		
		long cnt = 1000;
		for (File file: jpgFiles) {
			processFile(attackFolder, outputFolder, date, cnt++, file);
		}
	}

	private static void processFile(String attackFolder, String outputFolder, String date, long cnt, File file)
			throws IOException, InterruptedException {
		String inputFile = file.getName();
		String outputFile = date + cnt + ".jpg";			
		
		String inPath = attackFolder + "\\" + inputFile;
		String outPath = outputFolder + "\\" + outputFile;
		
		float scaleFactor = 4.0f;
		
		ResizerService.convertWebFile(inPath, outPath, scaleFactor);
	}
}