package AIMesher.ai_mesh_generator.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import AIMesher.ai_mesh_generator.config.GeminiProperties;
import AIMesher.ai_mesh_generator.model.Angle;
import AIMesher.ai_mesh_generator.services.GeminiApi.GeneratedImage;

// Uses a stub GeminiApi that returns a fixed image and records the request, so no key or network is needed.
class GeminiImageAdapterTest {

	@TempDir
	Path dir;

	private static final byte[] PIXELS = { 9, 8, 7 };

	private static final class StubGemini extends GeminiApi {

		String mimeType = "image/png";
		String prompt;
		List<File> images;

		StubGemini() {
			super(new GeminiProperties("test-key", "test-model", "test-image-model"), "http://unused", delay -> {
			});
		}

		@Override
		public GeneratedImage generateImage(String prompt, List<File> images) {
			this.prompt = prompt;
			this.images = images;
			return new GeneratedImage(PIXELS, mimeType);
		}
	}

	private final StubGemini gemini = new StubGemini();

	private GeminiImageAdapter adapter() {
		return new GeminiImageAdapter(gemini, dir);
	}

	@Test
	void generateAngleSendsTheReferencesAndAPromptForThatAngle() throws Exception {
		List<File> refs = List.of(new File("front.png"), new File("photo.jpg"));

		File view = adapter().generateAngle(refs, "Office chair (Furniture)", Angle.LEFT);

		assertEquals(refs, gemini.images);
		assertTrue(gemini.prompt.contains("Office chair (Furniture)"));
		assertTrue(gemini.prompt.contains("from its left side"));
		assertTrue(gemini.prompt.contains("The 2 attached image(s)"));
		assertTrue(gemini.prompt.contains("plain white background"));
		assertArrayEquals(PIXELS, Files.readAllBytes(view.toPath()));
		assertTrue(view.getName().startsWith("view-left-") && view.getName().endsWith(".png"));
		assertEquals(dir, view.toPath().getParent());
	}

	@Test
	void eachAngleGetsItsOwnViewpoint() {
		assertTrue(GeminiImageAdapter.anglePrompt(1, "stool", Angle.BACK).contains("directly behind"));
		assertTrue(GeminiImageAdapter.anglePrompt(1, "stool", Angle.RIGHT).contains("right side"));
		assertTrue(GeminiImageAdapter.anglePrompt(1, "stool", Angle.FRONT).contains("from the front"));
	}

	@Test
	void generateFromPromptAsksForAFrontViewWithoutReferences() {
		gemini.mimeType = "image/jpeg";

		File view = adapter().generateFromPrompt("A red wooden stool with three legs");

		assertEquals(List.of(), gemini.images);
		assertTrue(gemini.prompt.startsWith("A red wooden stool with three legs"));
		assertTrue(gemini.prompt.contains("straight from the front"));
		assertTrue(view.getName().startsWith("view-front-") && view.getName().endsWith(".jpg"));
	}

	@Test
	void promptWithoutReferencesDoesNotMentionAttachedImages() {
		assertFalse(GeminiImageAdapter.anglePrompt(0, "stool", Angle.FRONT).contains("attached"));
	}

	@Test
	void blankInputIsRejectedBeforeCallingGemini() {
		assertThrows(IllegalArgumentException.class, () -> adapter().generateFromPrompt(" "));
		assertThrows(IllegalArgumentException.class, () -> adapter().generateAngle(List.of(), "", Angle.FRONT));
		assertEquals(null, gemini.prompt);
	}
}
