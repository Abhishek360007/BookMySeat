package com.abhishekraj.BookMySeat.ticket;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** Black-on-white QR code PNGs. Error correction M survives a scratched or dimmed phone screen. */
public final class QrCodes {

	private QrCodes() {}

	public static byte[] png(String text, int size) {
		try {
			BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
					Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
			BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
			for (int y = 0; y < matrix.getHeight(); y++) {
				for (int x = 0; x < matrix.getWidth(); x++) {
					image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		} catch (WriterException e) {
			throw new IllegalArgumentException("Text too long for a QR code", e);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
