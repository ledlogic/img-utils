package com.github.ledlogic.webp;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.apache.commons.lang3.StringUtils;

public class WebpConverterApp {
	
	private static WebpFilenameFilter filter = new WebpFilenameFilter();
	
	public static void main(String[] args) throws IOException, InterruptedException {
		String attackFolder = "G:\\My Drive\\Games\\Bubblegum Crisis\\2026\\npcs";
		File attackFolderFile = new File(attackFolder);
		
		File[] files = attackFolderFile.listFiles(filter);

		String pattern = "yyyyMMddHHmm";
		SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
		String date = simpleDateFormat.format(new Date());
		boolean newNames = false;
		
		long cnt = 500;
		for (File file: files) {
			String inputFile = file.getName();
			String outputFile = (newNames ? date + (cnt++) : StringUtils.replace(file.getName(),".webp", "")) + ".png";			
			
			String inPath = attackFolder + "\\" + inputFile;
			String outPath = attackFolder + "\\" + outputFile;
			
			WebpConverterService.convertWebFile(inPath, outPath);
		}
	}
}