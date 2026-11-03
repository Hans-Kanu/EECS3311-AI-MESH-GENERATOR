package AIMesher.ai_mesh_generator.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import AIMesher.ai_mesh_generator.config.GeminiProperties;
import AIMesher.ai_mesh_generator.model.Identification;

// Checks that real Gemini replies fit the JSON shapes the adapter expects, using the key from .env.
// Skipped unless run with: ./mvnw test -Dgemini.live=true
@SpringBootTest
@EnabledIfSystemProperty(named = "gemini.live", matches = "true")
class GeminiVisionAdapterLiveTest {

	@Autowired
	GeminiProperties properties;

	@Autowired
	GeminiVisionAdapter vision;

	@TempDir
	Path dir;

	@BeforeEach
	void requireKey() {
		assumeTrue(properties.hasApiKey(), "No GEMINI_API_KEY in .env");
	}

	// A simple drawing of a mug: a cylinder with a handle on the right.
	private File mug() throws Exception {
		BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, 256, 256);
		g.setColor(new Color(30, 90, 200));
		g.fillRect(60, 60, 110, 140);
		g.setStroke(new BasicStroke(14));
		g.drawArc(150, 90, 60, 70, -90, 180);
		g.dispose();
		File file = dir.resolve("mug.png").toFile();
		ImageIO.write(image, "png", file);
		return file;
	}

	@Test
	void identifiesADrawnObject() throws Exception {
		Identification id = vision.identify(List.of(mug()), null);

		assertFalse(id.name().isBlank());
		assertTrue(id.confidence() >= 0 && id.confidence() <= 1);
	}

	@Test
	void labelsAnAngle() throws Exception {
		assertNotNull(vision.labelAngle(mug()));
	}
}
