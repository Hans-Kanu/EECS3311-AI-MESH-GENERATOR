package AIMesher.ai_mesh_generator.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

import AIMesher.ai_mesh_generator.config.GeminiProperties;
import AIMesher.ai_mesh_generator.model.Angle;
import AIMesher.ai_mesh_generator.model.Identification;

// Uses a stub GeminiApi that returns a canned reply and records what it was sent, so no key or network is needed.
class GeminiVisionAdapterTest {

	private static final File PHOTO = new File("photo.png");
	private static final File FOUND = new File("found.jpg");

	private static final class StubGemini extends GeminiApi {

		String reply;
		String prompt;
		List<File> images;

		StubGemini() {
			super(new GeminiProperties("test-key", "test-model"), "http://unused", delay -> {
			});
		}

		@Override
		public String generateJson(String prompt, List<File> images) {
			this.prompt = prompt;
			this.images = images;
			return reply;
		}
	}

	private final StubGemini gemini = new StubGemini();
	private final GeminiVisionAdapter vision = new GeminiVisionAdapter(gemini);

	private IllegalStateException unusable(String reply, Runnable call) {
		gemini.reply = reply;
		return assertThrows(IllegalStateException.class, call::run);
	}

	@Test
	void identifyTurnsTheReplyIntoAnIdentification() {
		gemini.reply = """
				{"name": "Office chair", "category": "Furniture", "keyFeatures": ["mesh back", " five wheels "], "confidence": 0.92}""";

		Identification id = vision.identify(List.of(PHOTO), "black mesh chair");

		assertEquals("Office chair", id.name());
		assertEquals("Furniture", id.category());
		assertEquals(List.of("mesh back", "five wheels"), id.keyFeatures());
		assertEquals(0.92, id.confidence());
		assertEquals(List.of(PHOTO), gemini.images);
		assertTrue(gemini.prompt.contains("black mesh chair"));
	}

	@Test
	void identifyWorksFromADescriptionAlone() {
		gemini.reply = """
				{"name": "Desk lamp", "category": "Lighting", "keyFeatures": [], "confidence": 0.5}""";

		Identification id = vision.identify(List.of(), "a lamp with a bendable neck");

		assertTrue(id.isLowConfidence());
		assertTrue(gemini.images.isEmpty());
		assertTrue(gemini.prompt.contains("described below"));
	}

	@Test
	void identifyNeedsAnImageOrADescription() {
		assertThrows(IllegalArgumentException.class, () -> vision.identify(List.of(), "  "));
		assertNull(gemini.prompt, "nothing should be sent");
	}

	@Test
	void identifyRejectsAConfidenceOutsideZeroToOne() {
		IllegalStateException error = unusable("""
				{"name": "Chair", "category": "Furniture", "keyFeatures": [], "confidence": 1.7}""",
				() -> vision.identify(List.of(PHOTO), null));

		assertTrue(error.getMessage().contains("confidence 1.7"), error.getMessage());
	}

	@Test
	void identifyRejectsAMissingOrEmptyName() {
		assertTrue(unusable("""
				{"category": "Furniture", "keyFeatures": [], "confidence": 0.9}""",
				() -> vision.identify(List.of(PHOTO), null)).getMessage().contains("\"name\""));
		assertTrue(unusable("""
				{"name": " ", "category": "Furniture", "keyFeatures": [], "confidence": 0.9}""",
				() -> vision.identify(List.of(PHOTO), null)).getMessage().contains("\"name\""));
	}

	@Test
	void identifyRejectsFeaturesThatAreNotAListOfText() {
		assertTrue(unusable("""
				{"name": "Chair", "category": "Furniture", "keyFeatures": "wheels", "confidence": 0.9}""",
				() -> vision.identify(List.of(PHOTO), null)).getMessage().contains("keyFeatures"));
		assertTrue(unusable("""
				{"name": "Chair", "category": "Furniture", "keyFeatures": [3], "confidence": 0.9}""",
				() -> vision.identify(List.of(PHOTO), null)).getMessage().contains("keyFeatures"));
	}

	@Test
	void aReplyThatIsNotJsonIsRejected() {
		IllegalStateException error = unusable("Sure! It's an office chair.", () -> vision.identify(List.of(PHOTO), null));

		assertTrue(error.getMessage().contains("not valid JSON"), error.getMessage());
	}

	@Test
	void aReplyThatIsNotAJsonObjectIsRejected() {
		IllegalStateException error = unusable("[\"chair\"]", () -> vision.identify(List.of(PHOTO), null));

		assertTrue(error.getMessage().contains("not a JSON object"), error.getMessage());
	}

	@Test
	void isSameItemSendsTheUsersPhotoFirst() {
		gemini.reply = """
				{"sameItem": true, "reason": "Same chair, same colour."}""";

		assertTrue(vision.isSameItem(FOUND, PHOTO));
		assertEquals(List.of(PHOTO, FOUND), gemini.images);
	}

	@Test
	void isSameItemRejectsAnAnswerThatIsNotTrueOrFalse() {
		IllegalStateException error = unusable("""
				{"sameItem": "yes"}""", () -> vision.isSameItem(FOUND, PHOTO));

		assertTrue(error.getMessage().contains("sameItem"), error.getMessage());
	}

	@Test
	void labelAngleAcceptsAnyCaseAndOffersEveryAngle() {
		gemini.reply = """
				{"angle": "left"}""";

		assertEquals(Angle.LEFT, vision.labelAngle(FOUND));
		assertTrue(gemini.prompt.contains("FRONT, BACK, LEFT, RIGHT"), gemini.prompt);
		assertTrue(gemini.prompt.contains("object's own left side"), gemini.prompt);
	}

	@Test
	void labelAngleRejectsAnAngleThatIsNotOneOfTheFour() {
		IllegalStateException error = unusable("""
				{"angle": "TOP"}""", () -> vision.labelAngle(FOUND));

		assertTrue(error.getMessage().contains("\"TOP\""), error.getMessage());
	}

	@Test
	void isConsistentSendsTheViewThenTheReferences() {
		File view = new File("back.png");
		File ref1 = new File("ref1.png");
		File ref2 = new File("ref2.png");
		gemini.reply = """
				{"consistent": false, "problems": ["the armrests are missing"]}""";

		assertFalse(vision.isConsistent(view, List.of(ref1, ref2)));
		assertEquals(List.of(view, ref1, ref2), gemini.images);
		assertTrue(gemini.prompt.contains("Images 2 to 3"), gemini.prompt);
	}

	@Test
	void isConsistentNeedsAReference() {
		assertThrows(IllegalArgumentException.class, () -> vision.isConsistent(new File("back.png"), List.of()));
		assertNull(gemini.prompt, "nothing should be sent");
	}

	@Test
	void writeImagePromptReturnsThePrompt() {
		gemini.reply = """
				{"prompt": "  A black mesh office chair on a white background.  "}""";

		assertEquals("A black mesh office chair on a white background.", vision.writeImagePrompt("black office chair"));
		assertTrue(gemini.prompt.contains("black office chair"));
		assertTrue(gemini.images.isEmpty());
	}

	@Test
	void writeImagePromptNeedsADescription() {
		assertThrows(IllegalArgumentException.class, () -> vision.writeImagePrompt(""));
		assertNull(gemini.prompt, "nothing should be sent");
	}
}
