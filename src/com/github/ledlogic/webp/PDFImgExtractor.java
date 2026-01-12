package com.github.ledlogic.webp;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.cos.COSName;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/* @see https://claude.ai/chat/5efe4691-e4b0-4d1d-a9e2-dbce8edf30ae */

public class PDFImgExtractor {
	
    private static final int MIN_DIMENSION = 150;
    private int imageCounter = 0;
    private Set<String> processedImages = new HashSet<>();
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java PDFImageExtractor <path-to-pdf-file>");
            System.exit(1);
        }
        
        String pdfPath = args[0];
        File pdfFile = new File(pdfPath);
        
        if (!pdfFile.exists()) {
            System.err.println("Error: PDF file not found: " + pdfPath);
            System.exit(1);
        }
        
        PDFImgExtractor extractor = new PDFImgExtractor();
        extractor.extractImages(pdfFile);
    }
    
    public void extractImages(File pdfFile) {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            System.out.println("Processing PDF: " + pdfFile.getName());
            System.out.println("Total pages: " + document.getNumberOfPages());
            
            String outputDir = pdfFile.getParent();
            if (outputDir == null) {
                outputDir = ".";
            }
            
            String baseName = pdfFile.getName().replaceFirst("[.][^.]+$", "");
            
            int totalPages = document.getNumberOfPages();
            int pagesToProcess = Math.max(0, totalPages - 4);
            
            for (int pageNum = 0; pageNum < pagesToProcess; pageNum++) {
                PDPage page = document.getPage(pageNum);
                processPage(page, outputDir, baseName, pageNum + 1);
            }
            
            System.out.println("\nExtraction complete!");
            System.out.println("Total images extracted: " + imageCounter);
            
        } catch (IOException e) {
            System.err.println("Error processing PDF: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void processPage(PDPage page, String outputDir, String baseName, int pageNum) {
        try {
            PDResources resources = page.getResources();
            if (resources == null) {
                return;
            }
            
            processResources(resources, outputDir, baseName, pageNum);
            
        } catch (IOException e) {
            System.err.println("Error processing page " + pageNum + ": " + e.getMessage());
        }
    }
    
    private void processResources(PDResources resources, String outputDir, 
                                   String baseName, int pageNum) throws IOException {
        for (COSName name : resources.getXObjectNames()) {
            if (resources.isImageXObject(name)) {
                PDImageXObject image = (PDImageXObject) resources.getXObject(name);
                processImage(image, outputDir, baseName, pageNum, name.getName());
            }
        }
    }
    
    private void processImage(PDImageXObject image, String outputDir, 
                              String baseName, int pageNum, String imageName) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            
            // Filter: both dimensions must be >= 150px
            if (width < MIN_DIMENSION || height < MIN_DIMENSION) {
                return;
            }
            
            // Create a unique identifier for this image to avoid duplicates
            String imageId = imageName + "_" + width + "x" + height;
            if (processedImages.contains(imageId)) {
                return;
            }
            processedImages.add(imageId);
            
            BufferedImage bImage = image.getImage();
            if (bImage == null) {
                return;
            }
            
            imageCounter++;
            String fileName = String.format("%s_page%d_img%d_%dx%d.png", 
                                           baseName, pageNum, imageCounter, width, height);
            String outputPath = outputDir + File.separator + fileName;
            File outputFile = new File(outputPath);
            
            ImageIO.write(bImage, "PNG", outputFile);
            System.out.println("Extracted: " + fileName + " (" + width + "x" + height + "px)");
            
        } catch (IOException e) {
            System.err.println("Error extracting image: " + e.getMessage());
        }
    }
}
