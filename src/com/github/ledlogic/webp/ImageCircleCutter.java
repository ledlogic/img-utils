package com.github.ledlogic.webp;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

public class ImageCircleCutter {
    
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java PlanetCircleCutter <image-path>");
            System.out.println("Example: java PlanetCircleCutter planet.webp");
            return;
        }
        
        String inputPath = args[0];
        processImage(inputPath);
    }
    
    public static void processImage(String inputPath) {
        try {
            // Load the image
            File inputFile = new File(inputPath);
            BufferedImage image = ImageIO.read(inputFile);
            
            if (image == null) {
                System.err.println("Error: Could not load image from " + inputPath);
                return;
            }
            
            System.out.println("Loaded image: " + image.getWidth() + "x" + image.getHeight());
            
            // Detect the circle (planet boundary)
            Circle circle = detectCircle(image);
            
            if (circle == null) {
                System.err.println("Error: Could not detect planet circle");
                return;
            }
            
            System.out.println("Detected circle: center(" + circle.x + ", " + circle.y + "), radius=" + circle.radius);
            
            // Create output image with transparency
            BufferedImage output = createCircularMask(image, circle);
            
            // Generate output filename
            String outputPath = generateOutputPath(inputPath);
            
            // Save the output image as PNG
            File outputFile = new File(outputPath);
            ImageIO.write(output, "PNG", outputFile);
            
            System.out.println("Saved circular cutout to: " + outputPath);
            
        } catch (IOException e) {
            System.err.println("Error processing image: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static Circle detectCircle(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        
        // Find the bounds of non-black pixels (the planet)
        int minX = width, maxX = 0;
        int minY = height, maxY = 0;
        
        // Threshold for considering a pixel as part of the planet
        int brightnessThreshold = 30;
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                
                // Check if pixel is bright enough to be part of the planet
                if (r > brightnessThreshold || g > brightnessThreshold || b > brightnessThreshold) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        
        // Calculate circle from bounding box
        int centerX = (minX + maxX) / 2;
        int centerY = (minY + maxY) / 2;
        int radiusX = (maxX - minX) / 2;
        int radiusY = (maxY - minY) / 2;
        int radius = Math.max(radiusX, radiusY);
        
        // Refine circle detection by finding actual edge points
        radius = refineRadius(image, centerX, centerY, radius, brightnessThreshold);
        
        return new Circle(centerX, centerY, radius);
    }
    
    private static int refineRadius(BufferedImage image, int centerX, int centerY, int initialRadius, int threshold) {
        int width = image.getWidth();
        int height = image.getHeight();
        
        // Sample along 8 directions to find the actual edge
        int[] angles = {0, 45, 90, 135, 180, 225, 270, 315};
        int sumRadius = 0;
        int count = 0;
        
        for (int angle : angles) {
            double rad = Math.toRadians(angle);
            
            // Search outward from center
            for (int r = 0; r < initialRadius + 50; r++) {
                int x = centerX + (int)(r * Math.cos(rad));
                int y = centerY + (int)(r * Math.sin(rad));
                
                if (x < 0 || x >= width || y < 0 || y >= height) {
                    break;
                }
                
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                
                // Check if we've hit the edge (transitioning to dark space)
                if (red <= threshold && green <= threshold && blue <= threshold) {
                    if (r > 0) {
                        sumRadius += r;
                        count++;
                    }
                    break;
                }
            }
        }
        
        return count > 0 ? sumRadius / count : initialRadius;
    }
    
    private static BufferedImage createCircularMask(BufferedImage source, Circle circle) {
        int width = source.getWidth();
        int height = source.getHeight();
        
        // Create image with alpha channel
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int dx = x - circle.x;
                int dy = y - circle.y;
                double distance = Math.sqrt(dx * dx + dy * dy);
                
                if (distance <= circle.radius) {
                    // Inside circle - copy pixel
                    output.setRGB(x, y, source.getRGB(x, y));
                } else {
                    // Outside circle - make transparent
                    output.setRGB(x, y, 0x00000000);
                }
            }
        }
        
        return output;
    }
    
    private static String generateOutputPath(String inputPath) {
        int lastDot = inputPath.lastIndexOf('.');
        if (lastDot > 0) {
            return inputPath.substring(0, lastDot) + "-circle.png";
        } else {
            return inputPath + "-circle.png";
        }
    }
    
    static class Circle {
        int x, y, radius;
        
        Circle(int x, int y, int radius) {
            this.x = x;
            this.y = y;
            this.radius = radius;
        }
    }
}