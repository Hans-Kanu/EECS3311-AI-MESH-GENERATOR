package AIMesher.ai_mesh_generator.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

class ReferenceImageSetTest {

	// A generated view: the only kind that can be left out of a build.
	private static ReferenceImage image(Angle angle) {
		return new ReferenceImage(new File(angle + ".png"), angle, ImageOrigin.AI_GENERATED, null);
	}

	@Test
	void excludedImageIsHiddenFromIncludedButKeptInImages() {
		ReferenceImageSet set = new ReferenceImageSet();
		ReferenceImage front = image(Angle.FRONT);
		ReferenceImage back = image(Angle.BACK);
		set.add(front);
		set.add(back);

		set.exclude(back.getId());

		assertEquals(List.of(front), set.getIncluded());
		assertEquals(2, set.getImages().size());
		assertTrue(set.missingAngles().contains(Angle.BACK));
	}

	@Test
	void includeUndoesExclude() {
		ReferenceImageSet set = new ReferenceImageSet();
		ReferenceImage back = image(Angle.BACK);
		set.add(image(Angle.FRONT));
		set.add(back);

		set.exclude(back.getId());
		set.include(back.getId());

		assertFalse(back.isExcluded());
		assertEquals(2, set.getIncluded().size());
	}

	@Test
	void cannotExcludeTheOnlyFrontView() {
		ReferenceImageSet set = new ReferenceImageSet();
		ReferenceImage front = image(Angle.FRONT);
		set.add(front);
		set.add(image(Angle.BACK));

		assertThrows(IllegalStateException.class, () -> set.exclude(front.getId()));
		assertTrue(set.hasFrontView());
	}

	@Test
	void canExcludeOneOfTwoFrontViews() {
		ReferenceImageSet set = new ReferenceImageSet();
		ReferenceImage front = image(Angle.FRONT);
		set.add(front);
		set.add(image(Angle.FRONT));

		set.exclude(front.getId());

		assertTrue(set.hasFrontView());
	}

	@Test
	void photosAreReferencesOnlySoTheyCannotBeExcluded() {
		ReferenceImageSet set = new ReferenceImageSet();
		ReferenceImage upload = new ReferenceImage(new File("mine.png"), Angle.LEFT, ImageOrigin.UPLOADED, null);
		ReferenceImage web = new ReferenceImage(new File("web.png"), Angle.BACK, ImageOrigin.WEB, "https://example.com");
		set.add(upload);
		set.add(web);
		set.add(image(Angle.FRONT));

		IllegalStateException error = assertThrows(IllegalStateException.class, () -> set.exclude(upload.getId()));
		assertThrows(IllegalStateException.class, () -> set.exclude(web.getId()));

		assertTrue(error.getMessage().contains("references only"));
		assertEquals(3, set.getIncluded().size());
	}

	// The case that used to slip through: an uploaded photo labelled as the front made the generated front look
	// optional, and the build then failed for want of a generated front view.
	@Test
	void anUploadedFrontPhotoDoesNotStandInForTheGeneratedFrontView() {
		ReferenceImageSet set = new ReferenceImageSet();
		set.add(new ReferenceImage(new File("mine.png"), Angle.FRONT, ImageOrigin.UPLOADED, null));
		ReferenceImage front = image(Angle.FRONT);
		set.add(front);
		set.add(image(Angle.BACK));

		assertThrows(IllegalStateException.class, () -> set.exclude(front.getId()));
		assertTrue(set.hasFrontView());
	}

	@Test
	void cannotExcludeTheLastImage() {
		ReferenceImageSet set = new ReferenceImageSet();
		ReferenceImage back = image(Angle.BACK);
		set.add(back);

		assertThrows(IllegalStateException.class, () -> set.exclude(back.getId()));
	}
}
