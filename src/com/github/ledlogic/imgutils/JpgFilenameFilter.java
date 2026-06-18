package com.github.ledlogic.imgutils;

import java.io.File;
import java.io.FilenameFilter;

public class JpgFilenameFilter implements FilenameFilter {

	@Override
	public boolean accept(File dir, String name) {
		boolean ret = name.endsWith("jpg");
		return ret;
	}

}