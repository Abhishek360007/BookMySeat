package com.abhishekraj.BookMySeat.ticket;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/** Reads a QR code PNG back to its text, the way a scanner at the door would. */
public final class QrDecoder {

	private QrDecoder() {}

	public static String decode(byte[] png) throws Exception {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
		int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
		RGBLuminanceSource source = new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels);
		return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
	}
}
