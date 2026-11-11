package com.abhishekraj.BookMySeat.film;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;

/**
 * Turns an uploaded poster into the one the app serves: always a 480 × 720 JPEG (2:3, cropped around the centre),
 * whatever came in. Plain Java (ImageIO), so every rule is unit-tested.
 *
 * <p>Only JPEG and PNG are accepted, recognised by their first bytes (not by a file name or a content type the client
 * claims). The size is read from the header before anything is decoded, and the image is decoded with subsampling
 * (every n-th pixel), so even a 8000-pixel upload is read at about twice the target size: a few MB of memory on a
 * 512 MB instance, never the full picture. Re-encoding also drops any metadata (camera, location).
 */
public final class PosterImages {

	public static final int WIDTH = 480;
	public static final int HEIGHT = 720;
	static final int MIN_SIDE = 200;
	static final int MAX_SIDE = 8000;
	static final float QUALITY = 0.85f;

	/** The poster as served: a JPEG, and an ETag-style version (changes whenever the image does). */
	public record Poster(byte[] jpeg, String version) {}

	private PosterImages() {
	}

	public static Poster process(byte[] upload) {
		String format = formatOf(upload);
		BufferedImage decoded = decode(upload, format);
		BufferedImage poster = cropAndScale(decoded);
		byte[] jpeg = encode(poster);
		return new Poster(jpeg, version(jpeg));
	}

	/** "jpeg" or "png" from the file's first bytes. */
	static String formatOf(byte[] data) {
		if (data == null || data.length < 8) {
			throw new PosterRejectedException(PosterRejectedException.Reason.NOT_AN_IMAGE, "That file is empty or not an image");
		}
		if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
			return "jpeg";
		}
		if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
				&& data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A) {
			return "png";
		}
		throw new PosterRejectedException(PosterRejectedException.Reason.UNSUPPORTED_TYPE, "Posters must be JPEG or PNG images");
	}

	private static BufferedImage decode(byte[] data, String format) {
		Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
		if (!readers.hasNext()) {
			throw new IllegalStateException("No ImageIO reader for " + format);
		}
		ImageReader reader = readers.next();
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
			reader.setInput(in, true, true); // one image, metadata ignored
			int width = reader.getWidth(0);
			int height = reader.getHeight(0);
			if (width < MIN_SIDE || height < MIN_SIDE) {
				throw new PosterRejectedException(PosterRejectedException.Reason.TOO_SMALL,
						"The poster is " + width + " × " + height + " pixels; it needs at least " + MIN_SIDE + " on each side");
			}
			if (width > MAX_SIDE || height > MAX_SIDE) {
				throw new PosterRejectedException(PosterRejectedException.Reason.TOO_LARGE,
						"The poster is " + width + " × " + height + " pixels; at most " + MAX_SIDE + " on each side");
			}
			// Read only every n-th pixel: still at least twice the target size, so the scaling below stays sharp
			int step = Math.max(1, Math.min(width / (2 * WIDTH), height / (2 * HEIGHT)));
			ImageReadParam param = reader.getDefaultReadParam();
			param.setSourceSubsampling(step, step, 0, 0);
			return reader.read(0, param);
		} catch (IOException | RuntimeException ex) {
			if (ex instanceof PosterRejectedException rejected) {
				throw rejected;
			}
			throw new PosterRejectedException(PosterRejectedException.Reason.NOT_AN_IMAGE, "That image could not be read");
		} finally {
			reader.dispose();
		}
	}

	/** The largest 2:3 part around the centre, scaled to 480 × 720 on white (a transparent PNG gets a white back). */
	static BufferedImage cropAndScale(BufferedImage source) {
		int w = source.getWidth();
		int h = source.getHeight();
		int cropW = w;
		int cropH = (int) Math.round(w * 1.5);
		if (cropH > h) {
			cropH = h;
			cropW = (int) Math.round(h / 1.5);
		}
		int x = (w - cropW) / 2;
		int y = (h - cropH) / 2;
		BufferedImage out = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		try {
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, WIDTH, HEIGHT);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(source, 0, 0, WIDTH, HEIGHT, x, y, x + cropW, y + cropH, null);
		} finally {
			g.dispose();
		}
		return out;
	}

	private static byte[] encode(BufferedImage image) {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
			writer.setOutput(out);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(QUALITY);
			writer.write(null, new IIOImage(image, null, null), param);
		} catch (IOException ex) {
			throw new IllegalStateException("Could not encode the poster", ex);
		} finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	/** The first 16 hex digits of the JPEG's SHA-256: a new image gets a new URL, so browsers can cache it for good. */
	static String version(byte[] jpeg) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jpeg)).substring(0, 16);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
