package com.github.ledlogic.webp;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

import org.apache.commons.io.FileUtils;

public class HeicConverterService {
	public static void convertWebFile(String inPath, String outPath) throws IOException, InterruptedException {
		String[] cmd = { "magick", "\"" + inPath + "\"", "\"" + outPath + "\"" };
		Process proc = Runtime.getRuntime().exec(cmd);
		int exitVal = proc.waitFor();
		System.out.println("Process exitValue: " + exitVal);
		
		if (exitVal == 0) {
			File inFile = new File(inPath);
			FileUtils.delete(inFile);
		}

		BufferedReader stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));

		BufferedReader stdError = new BufferedReader(new InputStreamReader(proc.getErrorStream()));

		// Read the output from the command
		System.out.println("Here is the standard output of the command:\n");
		String s = null;
		while ((s = stdInput.readLine()) != null) {
			System.out.println(s);
		}

		// Read any errors from the attempted command
		System.out.println("Here is the standard error of the command (if any):\n");
		while ((s = stdError.readLine()) != null) {
			System.out.println(s);
		}
	}
}