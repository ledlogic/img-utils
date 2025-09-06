package com.github.ledlogic.webp;

import java.io.File;
import java.io.FilenameFilter;

public class HeicFilenameFilter implements FilenameFilter {

	@Override
	public boolean accept(File dir, String name) {
		boolean ret = name.toLowerCase().endsWith("heic");
		return ret;
	}

}