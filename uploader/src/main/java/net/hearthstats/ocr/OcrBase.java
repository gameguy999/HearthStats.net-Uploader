package net.hearthstats.ocr;

import net.hearthstats.Main;
import net.sourceforge.tess4j.Tesseract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ColorConvertOp;
import java.awt.image.RescaleOp;
import java.io.File;

/**
 * Base class for performing OCR. Subclasses can override methods to customise the handling of OCR on different
 * types of text.
 */
public abstract class OcrBase {

    protected final static Logger debugLog = LoggerFactory.getLogger(OcrBase.class);


    /**
     * Crops the image to the expected location of the text to OCR. Different interations may return crop the image
     * differently if necessary.
     *
     * @param image A full screenshot image that needs to be cropped
     * @param iteration The iteration number, zero-based
     * @return
     */
    protected abstract BufferedImage crop(BufferedImage image, int iteration);

    /**
     * The filename of the image written to disk for debugging.
     * @return
     */
    protected abstract String getFilename();


    /**
     * Parse an OCR string to fix up any obvious errors, such as 'I' instead of '1' in a number.
     *
     * @param ocrResult A string generated by OCR
     * @param iteration The iteration number, zero-based
     * @return The OCR string with errors fixed, if possible
     */
    protected abstract String parseString(String ocrResult, int iteration);


    /**
     * Some OCR might require multiple iterations to find the right spot. Set this value to 1 if only one OCR attempt
     * should be made, or higher if multiple OCR attempts are needed.
     *
     * @param ocrResult A string generated by OCR
     * @param iteration The iteration number - one-based, unlike other methods where it is zero-based!
     * @return true if OCR should be processed again, or false if it's OK to continue with this OCR result
     */
    protected abstract boolean tryProcessingAgain(String ocrResult, int iteration);


    public String process(BufferedImage image) throws OcrException {
        String result = null;

        int iteration = 0;
        do {
            BufferedImage croppedImage = crop(image, 0);

            BufferedImage filteredImage = filter(croppedImage, iteration);
            croppedImage.flush();

            saveCopy(filteredImage, iteration);

            String rawResult = performOcr(filteredImage, iteration);
            filteredImage.flush();

            result = parseString(rawResult, iteration);

            iteration++;
        } while (tryProcessingAgain(result, iteration));

        debugLog.debug("OCR recognised \"{}\"", result);

        return result;
    }


    /**
     * Filters the image to make it easier to OCR, such as by turning it greyscale and increasing the contrast.
     *
     * @param image A cropped image
     * @param iteration The iteration number, zero-based
     * @return
     * @throws OcrException
     */
    protected BufferedImage filter(BufferedImage image, int iteration) throws OcrException {
        int width = image.getWidth();
        int height = image.getHeight();
        int bigWidth = width * 3;
        int bigHeight = height * 3;

        // to gray scale
        BufferedImage grayscale = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        BufferedImageOp grayscaleConv =
                new ColorConvertOp(image.getColorModel().getColorSpace(),
                        grayscale.getColorModel().getColorSpace(), null);
        grayscaleConv.filter(image, grayscale);

        // blow it up for ocr
        BufferedImage newImage = new BufferedImage(bigWidth, bigHeight, BufferedImage.TYPE_INT_RGB);
        Graphics g = newImage.createGraphics();
        g.drawImage(grayscale, 0, 0, bigWidth, bigHeight, null);
        g.dispose();

        // invert image
        for (int x = 0; x < bigWidth; x++) {
            for (int y = 0; y < bigHeight; y++) {
                int rgba = newImage.getRGB(x, y);
                Color col = new Color(rgba, true);
                col = new Color(255 - col.getRed(),
                        255 - col.getGreen(),
                        255 - col.getBlue());
                newImage.setRGB(x, y, col.getRGB());
            }
        }

        // increase contrast
        try {
            RescaleOp rescaleOp = new RescaleOp(1.8f, -30, null);
            rescaleOp.filter(newImage, newImage);  // Source and destination are the same.
        } catch (Exception e) {
            throw new OcrException("Error rescaling OCR image", e);
        }

        return newImage;
    }


    /**
     * Save a copy of the image to disk for use when debugging inaccurate OCR.
     *
     * @param image An image to be processed by OCR. Should already be cropped and filtered.
     */
    protected void saveCopy(BufferedImage image, int iteration) {
        File outputfile = new File(Main.getExtractionFolder() + "/" + getFilename() + ".png");
        try {
            ImageIO.write(image, "png", outputfile);
        } catch (Exception e) {
            debugLog.warn("Error writing OCR image " + getFilename(), e);
        }

    }


    /**
     * Perform the actual OCR using Tesseract.
     *
     * @param image An image to be processed by OCR. Should be cropped and filtered to ensure the contrast is sufficient.
     * @return The text that was recognised in the image
     */
    protected String performOcr(BufferedImage image, int iteration) throws OcrException {
        try {
            Tesseract instance = Tesseract.getInstance();
            String output = instance.doOCR(image);
            return output.trim();
        } catch (Exception e) {
            throw new OcrException("Error performing OCR", e);
        }
    }

}
