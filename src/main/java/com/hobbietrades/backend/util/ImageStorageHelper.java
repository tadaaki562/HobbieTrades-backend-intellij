package com.hobbietrades.backend.util;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.Locale;

/** Shrinks photos before DB storage so MySQL packet/column limits are not exceeded. */
public final class ImageStorageHelper {

    private static final int MAX_EDGE_PX = 1600;
    private static final long TARGET_MAX_BYTES = 900_000;

    private ImageStorageHelper() {}

    public static byte[] prepareForDatabase(byte[] input, String mime) {
        if (input == null || input.length == 0) {
            return input;
        }
        if (input.length <= TARGET_MAX_BYTES && isJpeg(mime)) {
            return input;
        }
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(input));
            if (src == null) {
                return input;
            }
            BufferedImage scaled = scaleDown(src);
            byte[] jpeg = encodeJpeg(scaled, 0.82f);
            if (jpeg.length >= input.length && isJpeg(mime)) {
                return input;
            }
            return jpeg;
        } catch (Exception e) {
            System.out.println("[ImageStorage] Could not compress image, storing original: " + e.getMessage());
            return input;
        }
    }

    public static String normalizedMime(String mime) {
        if (mime == null || mime.isBlank()) {
            return "image/jpeg";
        }
        return mime.toLowerCase(Locale.ROOT);
    }

    private static boolean isJpeg(String mime) {
        if (mime == null) {
            return false;
        }
        String m = mime.toLowerCase(Locale.ROOT);
        return m.contains("jpeg") || m.contains("jpg");
    }

    private static BufferedImage scaleDown(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= MAX_EDGE_PX && h <= MAX_EDGE_PX) {
            return toRgb(src);
        }
        double ratio = Math.min((double) MAX_EDGE_PX / w, (double) MAX_EDGE_PX / h);
        int nw = Math.max(1, (int) Math.round(w * ratio));
        int nh = Math.max(1, (int) Math.round(h * ratio));
        Image tmp = src.getScaledInstance(nw, nh, Image.SCALE_SMOOTH);
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(tmp, 0, 0, null);
        g.dispose();
        return out;
    }

    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG writer available");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
}
