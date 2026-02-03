package com.github.ledlogic.webp;

import java.io.File;
import java.io.IOException;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Test tool to inspect what images exist on page 118
 * and diagnose why they might not be extracted
 */
public class PDFImageExtractorTest {
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java PDFImageExtractorTest <path-to-pdf-file>");
            System.exit(1);
        }
        
        String pdfPath = args[0];
        File pdfFile = new File(pdfPath);
        
        if (!pdfFile.exists()) {
            System.err.println("Error: PDF file not found: " + pdfPath);
            System.exit(1);
        }
        
        try (PDDocument document = PDDocument.load(pdfFile)) {
			int totalPages = document.getNumberOfPages();
            System.out.println("PDF: " + pdfFile.getName());
            System.out.println("Total pages: " + totalPages);
            System.out.println();
            
            if (totalPages < 118) {
                System.out.println("ERROR: PDF only has " + totalPages + " pages, but page 118 requested");
                System.exit(1);
            }
            
            // Page 118 (index 117, zero-based)
            PDPage page = document.getPage(117);
            System.out.println("========================================");
            System.out.println("Inspecting Page 118 (index 117)");
            System.out.println("========================================");
            System.out.println();
            
            PDResources resources = page.getResources();
            if (resources == null) {
                System.out.println("No resources found on page 118");
                System.exit(0);
            }
            
            System.out.println("XObject Names:");
            int imageCount = 0;
            for (COSName name : resources.getXObjectNames()) {
                System.out.println("  - " + name.getName());
                
                PDXObject xObject = resources.getXObject(name);
                if (xObject instanceof PDImageXObject) {
                    imageCount++;
                    PDImageXObject image = (PDImageXObject) xObject;
                    
                    System.out.println("    Type: IMAGE");
                    System.out.println("    Size: " + image.getWidth() + "x" + image.getHeight() + " pixels");
                    System.out.println("    Color Space: " + image.getColorSpace().getName());
                    System.out.println("    Bits Per Component: " + image.getBitsPerComponent());
                    
                    try {
                        var bufferedImage = image.getImage();
                        if (bufferedImage != null) {
                            System.out.println("    BufferedImage Type: " + getImageTypeName(bufferedImage.getType()));
                            System.out.println("    Can Extract: YES");
                        } else {
                            System.out.println("    Can Extract: NO (getImage() returned null)");
                        }
                    } catch (Exception e) {
                        System.out.println("    Can Extract: NO (" + e.getMessage() + ")");
                    }
                    System.out.println();
                } else {
                    System.out.println("    Type: " + xObject.getClass().getSimpleName());
                    System.out.println();
                }
            }
            
            System.out.println("========================================");
            System.out.println("Summary");
            System.out.println("========================================");
            
            // Count XObjects manually since getXObjectNames() returns Iterable
            int xobjectCount = 0;
            for (@SuppressWarnings("unused") COSName name : resources.getXObjectNames()) {
                xobjectCount++;
            }
            
            System.out.println("Total XObjects on page 118: " + xobjectCount);
            System.out.println("Images on page 118: " + imageCount);
            
            if (imageCount == 0) {
                System.out.println();
                System.out.println("⚠️  NO IMAGES FOUND ON PAGE 118!");
                System.out.println("Possible reasons:");
                System.out.println("  - Images are inline (not XObjects)");
                System.out.println("  - Images are in Form XObjects");
                System.out.println("  - Page 118 has no images");
                System.out.println("  - Images are vector graphics, not raster");
            }
            
        } catch (IOException e) {
            System.err.println("Error processing PDF: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static String getImageTypeName(int type) {
        switch (type) {
            case 1: return "TYPE_INT_RGB";
            case 2: return "TYPE_INT_ARGB";
            case 3: return "TYPE_INT_ARGB_PRE";
            case 4: return "TYPE_INT_BGR";
            case 5: return "TYPE_3BYTE_BGR";
            case 6: return "TYPE_4BYTE_ABGR";
            case 7: return "TYPE_4BYTE_ABGR_PRE";
            case 8: return "TYPE_USHORT_565_RGB";
            case 9: return "TYPE_USHORT_555_RGB";
            case 10: return "TYPE_BYTE_GRAY";
            case 11: return "TYPE_USHORT_GRAY";
            case 12: return "TYPE_BYTE_BINARY";
            case 13: return "TYPE_BYTE_INDEXED";
            default: return "CUSTOM (" + type + ")";
        }
    }
}
