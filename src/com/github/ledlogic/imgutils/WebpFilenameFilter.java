package com.github.ledlogic.imgutils;

import java.io.File;
import java.io.FilenameFilter;

public class WebpFilenameFilter implements FilenameFilter {

	@Override
	public boolean accept(File dir, String name) {
		boolean ret = name.toLowerCase().endsWith("webp");
		return ret;
	}

}