package com.github.ledlogic.webp;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * PDF Text Extractor - Extracts plain text from PDF files
 * and saves it to a markup document.
 */
public class PDFTextExtractor {

    /**
     * Extracts text from a PDF file and saves it to a markdown file.
     * 
     * @param pdfPath Path to the input PDF file
     * @param outputPath Path to the output markdown file
     * @throws IOException if file operations fail
     */
    public static void extractTextToMarkdown(String pdfPath, String outputPath) throws IOException {
        File pdfFile = new File(pdfPath);
        
        if (!pdfFile.exists()) {
            throw new IOException("PDF file not found: " + pdfPath);
        }
        
        // Load the PDF document
        try (PDDocument document = PDDocument.load(pdfFile)) {
            
            // Create text stripper
            PDFTextStripper stripper = new PDFTextStripper();
            
            // Configure stripper to preserve some formatting
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            
            // Extract text from all pages
            String text = stripper.getText(document);
            
            // Clean up the text (remove excessive blank lines)
            text = cleanText(text);
            
            // Write to output file
            try (FileWriter writer = new FileWriter(outputPath)) {
                // Add markdown header
                writer.write("# Extracted Text from PDF\n\n");
                writer.write("**Source:** " + pdfFile.getName() + "\n\n");
                writer.write("**Pages:** " + document.getNumberOfPages() + "\n\n");
                writer.write("---\n\n");
                
                // Write the extracted text
                writer.write(text);
            }
            
            System.out.println("Text extraction complete!");
            System.out.println("Input: " + pdfPath);
            System.out.println("Output: " + outputPath);
            System.out.println("Pages processed: " + document.getNumberOfPages());
        }
    }
    
    /**
     * Cleans up extracted text by removing excessive blank lines.
     * 
     * @param text The raw extracted text
     * @return Cleaned text
     */
    private static String cleanText(String text) {
        // Replace multiple consecutive blank lines with just two newlines
        text = text.replaceAll("\n{3,}", "\n\n");
        
        // Remove trailing whitespace from lines
        text = text.replaceAll("[ \t]+\n", "\n");
        
        // Trim leading and trailing whitespace
        text = text.trim();
        
        return text;
    }
    
    /**
     * Generates the output filename by replacing the PDF extension with .md
     * 
     * @param pdfPath The input PDF file path
     * @return The output markdown file path
     */
    private static String generateOutputPath(String pdfPath) {
        // Remove .pdf extension (case insensitive) and add .md
        if (pdfPath.toLowerCase().endsWith(".pdf")) {
            return pdfPath.substring(0, pdfPath.length() - 4) + ".md";
        } else {
            // If it doesn't end with .pdf, just append .md
            return pdfPath + ".md";
        }
    }
    
    /**
     * Main method for command-line usage.
     * 
     * @param args Command line arguments: [input_pdf_path]
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java PDFTextExtractor <input_pdf_path>");
            System.err.println("Example: java PDFTextExtractor document.pdf");
            System.err.println("Output will be saved as: document.md");
            System.exit(1);
        }
        
        String pdfPath = args[0];
        String outputPath = generateOutputPath(pdfPath);
        
        try {
            extractTextToMarkdown(pdfPath, outputPath);
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
