package AIMesher.ai_mesh_generator.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

import AIMesher.ai_mesh_generator.model.Angle;
import AIMesher.ai_mesh_generator.model.GenerationRequest;
import AIMesher.ai_mesh_generator.model.Identification;
import AIMesher.ai_mesh_generator.model.ImageOrigin;
import AIMesher.ai_mesh_generator.model.ReferenceImage;
import AIMesher.ai_mesh_generator.model.ReferenceImageSet;

class GenerationPathTest {

	private static final File PHOTO = new File("photo.png");

	private final Fakes.Vision vision = new Fakes.Vision();
	private final Fakes.Generator generator = new Fakes.Generator();
	private final Fakes.Search search = new Fakes.Search();
	private final Fakes.Recorder progress = new Fakes.Recorder();

	private static List<Angle> generatedAngles(ReferenceImageSet set) {
		return set.getImages().stream()
				.filter(image -> image.getOrigin() == ImageOrigin.AI_GENERATED)
				.map(ReferenceImage::getAngle)
				.toList();
	}

	private List<Angle> calledAngles() {
		return generator.calls.stream().map(Fakes.Call::angle).toList();
	}

	@Test
	void descriptionOnlyGeneratesTheFrontFromAPromptThenEachSideFromTheFront() {
		ReferenceImageSet set = new DescriptionOnlyPath(vision, generator)
				.run(new GenerationRequest(List.of(), "red wooden stool"), progress);

		assertEquals(List.of(Angle.FRONT, Angle.LEFT, Angle.RIGHT, Angle.BACK), generatedAngles(set));
		assertEquals("red wooden stool", vision.promptDescription);
		Fakes.Call front = generator.calls.get(0);
		assertEquals("prompt", front.method());
		assertEquals("PROMPT: red wooden stool", front.text());
		File frontFile = set.getImages().get(0).getFile();
		for (Fakes.Call call : generator.calls.subList(1, 4)) {
			assertEquals("angle", call.method());
			assertEquals(List.of(frontFile), call.refs());
			assertEquals("red wooden stool", call.text());
		}
	}

	@Test
	void imageOnlyUsesMatchingWebPhotosAsReferencesButRegeneratesEveryView() {
		File same1 = search.add("same1.jpg");
		search.add("other.jpg");
		File same2 = search.add("same2.jpg");
		vision.sameItems.addAll(List.of(same1, same2));

		ReferenceImageSet set = new ImageOnlyPath(vision, generator, search)
				.run(new GenerationRequest(List.of(PHOTO), null), progress);

		List<ReferenceImage> web = set.getImages().stream().filter(i -> i.getOrigin() == ImageOrigin.WEB).toList();
		assertEquals(List.of(same1, same2), web.stream().map(ReferenceImage::getFile).toList());
		assertEquals("https://shop.example/same1.jpg", web.get(0).getSourceUrl());
		assertEquals(ImageOrigin.UPLOADED, set.getImages().get(0).getOrigin());
		// The uploaded photo is a reference, not the front view: all four views are generated, front first.
		assertEquals(List.of(Angle.FRONT, Angle.LEFT, Angle.RIGHT, Angle.BACK), calledAngles());
		assertEquals(List.of(PHOTO, same1, same2), generator.calls.get(0).refs());
		File front = generatedFile(set, Angle.FRONT);
		assertEquals(List.of(front, PHOTO, same1, same2), generator.calls.get(1).refs());
		assertEquals("Office chair (Furniture), with mesh back", generator.calls.get(0).text());
	}

	@Test
	void tooFewMatchesFallsBackToTheUsersImagesOnly() {
		File same = search.add("same.jpg");
		search.add("other.jpg");
		vision.sameItems.add(same);

		ReferenceImageSet set = new ImageOnlyPath(vision, generator, search)
				.run(new GenerationRequest(List.of(PHOTO), null), progress);

		assertTrue(set.getImages().stream().noneMatch(i -> i.getOrigin() == ImageOrigin.WEB));
		assertEquals(List.of(PHOTO), generator.calls.get(0).refs());
		assertEquals(4, generatedAngles(set).size());
		assertTrue(progress.said("too few"));
	}

	@Test
	void aFailedSearchFallsBackInsteadOfStopping() {
		search.failure = new IllegalStateException("quota exceeded");

		ReferenceImageSet set = new ImageOnlyPath(vision, generator, search)
				.run(new GenerationRequest(List.of(PHOTO), null), progress);

		assertEquals(4, generatedAngles(set).size());
		assertTrue(progress.said("quota exceeded"));
	}

	@Test
	void anUnsureIdentificationSkipsTheSearch() {
		vision.identification = new Identification("Chair?", "Furniture", List.of(), 0.3);
		File same1 = search.add("same1.jpg");
		File same2 = search.add("same2.jpg");
		vision.sameItems.addAll(List.of(same1, same2));

		ReferenceImageSet set = new ImageOnlyPath(vision, generator, search)
				.run(new GenerationRequest(List.of(PHOTO), null), progress);

		assertEquals(0, search.searches);
		assertTrue(set.getImages().stream().noneMatch(i -> i.getOrigin() == ImageOrigin.WEB));
		assertTrue(progress.said("Not sure enough"));
	}

	@Test
	void anInconsistentViewIsGeneratedAgainOnce() {
		// Front passes; the left view fails twice, so it is retried once and then kept with a warning.
		vision.consistency.addAll(List.of(true, false, false));

		new ImageOnlyPath(vision, generator, search).run(new GenerationRequest(List.of(PHOTO), null), progress);

		assertEquals(List.of(Angle.FRONT, Angle.LEFT, Angle.LEFT, Angle.RIGHT, Angle.BACK), calledAngles());
		assertTrue(progress.said("generating it again"));
		assertTrue(progress.said("Keeping the last left view"));
	}

	@Test
	void imageAndDescriptionPassesTheDescriptionAlong() {
		new ImageAndDescriptionPath(vision, generator, search)
				.run(new GenerationRequest(List.of(PHOTO), "black mesh chair"), progress);

		assertEquals("black mesh chair", vision.identifyHint);
		assertEquals("Office chair (Furniture), with mesh back. The user describes it as: black mesh chair",
				generator.calls.get(0).text());
	}

	@Test
	void imageAndDescriptionTrustsTheDescriptionWhenTheIdentificationIsUnsure() {
		vision.identification = new Identification("Stool?", "Furniture", List.of(), 0.2);

		new ImageAndDescriptionPath(vision, generator, search)
				.run(new GenerationRequest(List.of(PHOTO), "black mesh chair"), progress);

		assertEquals("black mesh chair", generator.calls.get(0).text());
	}

	private static File generatedFile(ReferenceImageSet set, Angle angle) {
		return set.getImages().stream()
				.filter(image -> image.getOrigin() == ImageOrigin.AI_GENERATED && image.getAngle() == angle)
				.findFirst().orElseThrow().getFile();
	}
}
