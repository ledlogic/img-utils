package com.github.ledlogic.webp;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.apache.commons.io.FileUtils;

public class GrayScalerService {
	public static void convertWebFile(String inPath, String outPath) throws IOException, InterruptedException {
        BufferedImage img = null;
		File inFile = new File(inPath);
        try{
            img = ImageIO.read(inFile);
        } catch(IOException e){
            System.out.println(e);
        }

        int width = img.getWidth();
        int height = img.getHeight();
        for(int y = 0; y < height; y++){
            for(int x = 0; x < width; x++){
                int p = img.getRGB(x,y);
                int a = (p >> 24) & 0xff;
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                int avg = (r + g + b)/3;
                p = (a<<24) | (avg<<16) | (avg<<8) |  avg;
                img.setRGB(x, y, p);
            }
        }
        try{
            File outFile = new File(outPath);
            ImageIO.write(img, "png", outFile);
        } catch(IOException e){
            System.out.println(e);
        }
		FileUtils.delete(inFile);
	}
}