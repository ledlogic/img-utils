package com.github.ledlogic.webp;

import java.io.File;
import java.io.FilenameFilter;

public class PngFilenameFilter implements FilenameFilter {

	@Override
	public boolean accept(File dir, String name) {
		boolean ret = name.endsWith("png");
		return ret;
	}

}