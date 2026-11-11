package com.abhishekraj.BookMySeat.film;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PosterImagesTest {

	static byte[] image(String format, int width, int height, int type, Color fill) throws IOException {
		BufferedImage image = new BufferedImage(width, height, type);
		Graphics2D g = image.createGraphics();
		g.setColor(fill);
		g.fillRect(0, 0, width, height);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, format, out);
		return out.toByteArray();
	}

	static BufferedImage read(byte[] jpeg) throws IOException {
		return ImageIO.read(new ByteArrayInputStream(jpeg));
	}

	static boolean near(int rgb, Color expected) {
		Color c = new Color(rgb);
		return Math.abs(c.getRed() - expected.getRed()) < 12 && Math.abs(c.getGreen() - expected.getGreen()) < 12
				&& Math.abs(c.getBlue() - expected.getBlue()) < 12;
	}

	@Test
	void aJpegOrPng_becomesA480By720Jpeg() throws IOException {
		for (String format : new String[] {"jpeg", "png"}) {
			PosterImages.Poster poster = PosterImages.process(image(format, 1200, 1800, BufferedImage.TYPE_INT_RGB, Color.ORANGE));
			assertThat(PosterImages.formatOf(poster.jpeg())).isEqualTo("jpeg");
			BufferedImage out = read(poster.jpeg());
			assertThat(out.getWidth()).isEqualTo(480);
			assertThat(out.getHeight()).isEqualTo(720);
			assertThat(near(out.getRGB(240, 360), Color.ORANGE)).as(format).isTrue();
		}
	}

	@Test
	void aWideImage_isCroppedAroundTheCentre_notSquashed() throws IOException {
		// 3000 × 1000: blue edges, a red 600-pixel band in the middle. The 2:3 crop is the middle 667 pixels
		BufferedImage wide = new BufferedImage(3000, 1000, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = wide.createGraphics();
		g.setColor(Color.BLUE);
		g.fillRect(0, 0, 3000, 1000);
		g.setColor(Color.RED);
		g.fillRect(1200, 0, 600, 1000);
		g.dispose();
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(wide, "png", png);

		BufferedImage out = read(PosterImages.process(png.toByteArray()).jpeg());
		assertThat(near(out.getRGB(240, 360), Color.RED)).isTrue(); // the centre
		assertThat(near(out.getRGB(5, 360), Color.BLUE)).isTrue(); // a sliver of the edges either side
		assertThat(near(out.getRGB(474, 360), Color.BLUE)).isTrue();
	}

	@Test
	void transparency_becomesWhite() throws IOException {
		byte[] clear = image("png", 400, 600, BufferedImage.TYPE_INT_ARGB, new Color(0, 0, 0, 0));
		BufferedImage out = read(PosterImages.process(clear).jpeg());
		assertThat(near(out.getRGB(240, 360), Color.WHITE)).isTrue();
	}

	@Test
	void theTypeComesFromTheBytes_notTheName() throws IOException {
		byte[] gif = image("gif", 400, 600, BufferedImage.TYPE_INT_RGB, Color.GREEN);
		assertThatThrownBy(() -> PosterImages.process(gif)).isInstanceOf(PosterRejectedException.class)
				.extracting("reason").isEqualTo(PosterRejectedException.Reason.UNSUPPORTED_TYPE);
		assertThatThrownBy(() -> PosterImages.process("<svg onload=alert(1)>".getBytes()))
				.extracting("reason").isEqualTo(PosterRejectedException.Reason.UNSUPPORTED_TYPE);
		assertThatThrownBy(() -> PosterImages.process(new byte[3]))
				.extracting("reason").isEqualTo(PosterRejectedException.Reason.NOT_AN_IMAGE);
	}

	@Test
	void aBrokenImage_isRefused_notA500() throws IOException {
		byte[] jpeg = image("jpeg", 800, 1200, BufferedImage.TYPE_INT_RGB, Color.PINK);
		byte[] cut = Arrays.copyOf(jpeg, 40); // a real JPEG start, then nothing
		assertThatThrownBy(() -> PosterImages.process(cut)).isInstanceOf(PosterRejectedException.class)
				.extracting("reason").isEqualTo(PosterRejectedException.Reason.NOT_AN_IMAGE);
	}

	@Test
	void tooSmallOrTooLarge_isRefusedFromTheHeader() throws IOException {
		assertThatThrownBy(() -> PosterImages.process(image("png", 150, 600, BufferedImage.TYPE_INT_RGB, Color.GRAY)))
				.extracting("reason").isEqualTo(PosterRejectedException.Reason.TOO_SMALL);
		assertThatThrownBy(() -> PosterImages.process(image("png", 8001, 300, BufferedImage.TYPE_BYTE_GRAY, Color.GRAY)))
				.extracting("reason").isEqualTo(PosterRejectedException.Reason.TOO_LARGE);
		// The largest allowed is fine, read with subsampling
		PosterImages.Poster big = PosterImages.process(image("png", 4000, 6000, BufferedImage.TYPE_BYTE_GRAY, Color.DARK_GRAY));
		assertThat(read(big.jpeg()).getWidth()).isEqualTo(480);
	}

	@Test
	void theVersion_followsTheImage() throws IOException {
		PosterImages.Poster a = PosterImages.process(image("png", 400, 600, BufferedImage.TYPE_INT_RGB, Color.CYAN));
		PosterImages.Poster again = PosterImages.process(image("png", 400, 600, BufferedImage.TYPE_INT_RGB, Color.CYAN));
		PosterImages.Poster b = PosterImages.process(image("png", 400, 600, BufferedImage.TYPE_INT_RGB, Color.MAGENTA));
		assertThat(a.version()).hasSize(16).isEqualTo(again.version()).isNotEqualTo(b.version());
	}
}
