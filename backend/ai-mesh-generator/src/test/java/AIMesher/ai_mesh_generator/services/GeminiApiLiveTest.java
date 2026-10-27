package AIMesher.ai_mesh_generator.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import AIMesher.ai_mesh_generator.config.GeminiProperties;

// Calls the real Gemini API with the key from .env. Skipped unless run with: ./mvnw test -Dgemini.live=true
@SpringBootTest
@EnabledIfSystemProperty(named = "gemini.live", matches = "true")
class GeminiApiLiveTest {

	@Autowired
	GeminiProperties properties;

	@Autowired
	GeminiApi api;

	@BeforeEach
	void requireKey() {
		assumeTrue(properties.hasApiKey(), "No GEMINI_API_KEY in .env");
	}

	@Test
	void answersATextPrompt() {
		String text = api.generateContent("Reply with only the word: pong", List.of());

		assertTrue(text.toLowerCase(Locale.ROOT).contains("pong"), "Gemini replied: " + text);
	}

	@Test
	void seesTheImage(@TempDir Path dir) throws Exception {
		BufferedImage square = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = square.createGraphics();
		g.setColor(Color.RED);
		g.fillRect(0, 0, 64, 64);
		g.dispose();
		File image = dir.resolve("red.png").toFile();
		ImageIO.write(square, "png", image);

		String text = api.generateContent("What colour is this image? Answer with one word.", List.of(image));

		assertTrue(text.toLowerCase(Locale.ROOT).contains("red"), "Gemini replied: " + text);
	}
}
