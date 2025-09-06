package com.github.ledlogic.webp;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.apache.commons.io.FileUtils;

public class ResizerService {
	public static void convertWebFile(String inPath, String outPath, float scaleFactor) throws IOException, InterruptedException {
        BufferedImage img = null;
		File inFile = new File(inPath);
        try{
            img = ImageIO.read(inFile);
        } catch(IOException e){
            System.out.println(e);
        }

        int width = img.getWidth();
        int height = img.getHeight();
        
        int targetWidth = Math.round(width * scaleFactor);
        int targetHeight = Math.round(height * scaleFactor);
        
        Image scaledImage = img.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH);
        BufferedImage outputImg = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        outputImg.getGraphics().drawImage(scaledImage, 0, 0, null);
        
        try{
            File outFile = new File(outPath);
            ImageIO.write(outputImg, "jpg", outFile);
        } catch(IOException e){
            System.out.println(e);
        }
	}
}