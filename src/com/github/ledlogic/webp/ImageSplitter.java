package com.github.ledlogic.webp;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * ImageProcessor - Split wide images into 8.5-inch segments and create a PDF
 * 
 * This class handles:
 * 1. Splitting a wide panoramic image into multiple 8.5-inch wide segments
 * 2. Creating a PDF document with one slice per page for printing
 */
public class ImageSplitter {
    
    private static final double DEFAULT_IMAGE_WIDTH_INCHES = 71.111;
    private static final double SEGMENT_WIDTH_INCHES = 7.5;
    
    public static void main(String[] args) {
        try {
            // Input file path
            String inputPath = "G:\\My Drive\\Games\\BGC\\2026\\architecture\\stone-arch-crop.jpg";
            File inputFile = new File(inputPath);
            
            if (!inputFile.exists()) {
                System.err.println("Input file not found: " + inputPath);
                return;
            }
            
            // Step 1: Split the image into slices
            System.out.println("=== Step 1: Splitting Image ===");
            List<File> sliceFiles = splitImage(inputFile, DEFAULT_IMAGE_WIDTH_INCHES, SEGMENT_WIDTH_INCHES);
            
            if (sliceFiles.isEmpty()) {
                System.err.println("No slices were created!");
                return;
            }
            
            System.out.println("\n=== Step 2: Creating PDF ===");
            // Step 2: Create PDF from slices
            String inputDir = inputFile.getParent();
            String baseFileName = inputFile.getName().substring(0, inputFile.getName().lastIndexOf('.'));
            String outputPdf = inputDir + File.separator + baseFileName + "-slices.pdf";
            
            createPDF(sliceFiles, outputPdf);
            
            System.out.println("\n=== Complete ===");
            System.out.println("Created " + sliceFiles.size() + " slice images");
            System.out.println("Created PDF: " + outputPdf);
            
        } catch (IOException e) {
            System.err.println("Error processing image: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Split an image into segments of specified width in inches
     * 
     * @param inputFile The image file to split
     * @param widthInches Total width of the image in inches
     * @param segmentWidthInches Width of each segment in inches
     * @return List of created slice files
     * @throws IOException If there's an error reading or writing files
     */
    public static List<File> splitImage(File inputFile, double widthInches, double segmentWidthInches) 
            throws IOException {
        
        List<File> sliceFiles = new ArrayList<>();
        
        // Read the image
        BufferedImage sourceImage = ImageIO.read(inputFile);
        int fullWidth = sourceImage.getWidth();
        int fullHeight = sourceImage.getHeight();
        
        System.out.println("Source image dimensions: " + fullWidth + "x" + fullHeight);
        
        // Calculate DPI based on given width in inches
        double actualDPI = fullWidth / widthInches;
        int segmentWidthPixels = (int) Math.round(segmentWidthInches * actualDPI);
        
        System.out.println("Calculated DPI: " + actualDPI);
        System.out.println("Segment width in pixels: " + segmentWidthPixels);
        
        // Calculate number of segments needed
        int numFullSegments = fullWidth / segmentWidthPixels;
        int remainingWidth = fullWidth % segmentWidthPixels;
        int totalSegments = numFullSegments + (remainingWidth > 0 ? 1 : 0);
        
        System.out.println("Total segments: " + totalSegments);
        System.out.println("Full segments: " + numFullSegments);
        System.out.println("Remaining width: " + remainingWidth + " pixels");
        
        // Get input file directory and name
        String inputDir = inputFile.getParent();
        String inputFileName = inputFile.getName();
        String baseFileName = inputFileName.substring(0, inputFileName.lastIndexOf('.'));
        
        // Split and save images
        for (int i = 0; i < totalSegments; i++) {
            int xStart = i * segmentWidthPixels;
            int width = (i == totalSegments - 1 && remainingWidth > 0) 
                ? remainingWidth 
                : segmentWidthPixels;
            
            // Extract sub-image
            BufferedImage segment = sourceImage.getSubimage(xStart, 0, width, fullHeight);
            
            // Output filename with slice numbering (zero-padded to 2 digits)
            String outputPath = inputDir + File.separator + baseFileName 
                + String.format("-slice-%02d.jpg", i + 1);
            File outputFile = new File(outputPath);
            
            // Save with lossless JPEG (maximum quality)
            saveJPEGWithMaxQuality(segment, outputFile);
            sliceFiles.add(outputFile);
            
            System.out.println("Saved: " + outputPath + " (width: " + width + " pixels, " 
                + String.format("%.2f", width / actualDPI) + " inches)");
        }
        
        return sliceFiles;
    }
    
    /**
     * Save a BufferedImage as JPEG with maximum quality
     * 
     * @param image The image to save
     * @param outputFile The file to save to
     * @throws IOException If there's an error writing the file
     */
    private static void saveJPEGWithMaxQuality(BufferedImage image, File outputFile) throws IOException {
        // Get JPEG writer
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG writers found");
        }
        
        ImageWriter writer = writers.next();
        
        // Set up output stream
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputFile)) {
            writer.setOutput(ios);
            
            // Configure for maximum quality (lossless as possible for JPEG)
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(1.0f); // Maximum quality
            }
            
            // Write the image
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }
    
    /**
     * Create a PDF document from a list of image files
     * 
     * @param imageFiles List of image files to include (one per page)
     * @param outputPath Path where the PDF should be saved
     * @throws IOException If there's an error creating the PDF
     */
    public static void createPDF(List<File> imageFiles, String outputPath) throws IOException {
        PDDocument document = new PDDocument();
        
        try {
            // Use portrait letter size (8.5" x 11")
            PDRectangle pageSize = new PDRectangle(612, 792); // 8.5" x 11" at 72 DPI
            
            // Margins (0.5 inch = 36 points)
            float margin = 36;
            float availableWidth = pageSize.getWidth() - (2 * margin);
            float availableHeight = pageSize.getHeight() - (2 * margin);
            
            for (int i = 0; i < imageFiles.size(); i++) {
                File imageFile = imageFiles.get(i);
                System.out.println("Processing: " + imageFile.getName());
                
                // Create a new page
                PDPage page = new PDPage(pageSize);
                document.addPage(page);
                
                // Load image
                PDImageXObject image = PDImageXObject.createFromFile(
                    imageFile.getAbsolutePath(), document);
                
                // Calculate scaling to fit while maintaining aspect ratio
                float imgWidth = image.getWidth();
                float imgHeight = image.getHeight();
                
                float widthScale = availableWidth / imgWidth;
                float heightScale = availableHeight / imgHeight;
                float scale = Math.min(widthScale, heightScale);
                
                float finalWidth = imgWidth * scale;
                float finalHeight = imgHeight * scale;
                
                // Center the image on the page
                float x = margin + (availableWidth - finalWidth) / 2;
                float y = margin + (availableHeight - finalHeight) / 2;
                
                // Draw image on page
                PDPageContentStream contentStream = new PDPageContentStream(
                    document, page, PDPageContentStream.AppendMode.APPEND, true, true);
                
                try {
                    contentStream.drawImage(image, x, y, finalWidth, finalHeight);
                } finally {
                    contentStream.close();
                }
            }
            
            // Save the document
            document.save(outputPath);
            System.out.println("PDF saved: " + outputPath);
            
        } finally {
            document.close();
        }
    }
}
