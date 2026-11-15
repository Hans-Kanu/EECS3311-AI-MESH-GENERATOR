package AIMesher.ai_mesh_generator.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// Prototype: copy() snapshots the set for each ModelVersion (F09).
public class ReferenceImageSet {

	private final List<ReferenceImage> images = new ArrayList<>();

	public void add(ReferenceImage image) {
		images.add(image);
	}

	// Leaves a generated view out of the next build (F05). Only AI-generated views are sent to the mesh generator, so
	// only they can be left out; the user's photos and web photos are references only. The change is rejected if it
	// would leave no generated view or no generated front view, since the mesh generator needs a front view.
	// The controller shows the message to the user.
	public void exclude(String imageId) {
		ReferenceImage image = find(imageId);
		if (image.isExcluded()) {
			return;
		}
		if (image.getOrigin() != ImageOrigin.AI_GENERATED) {
			throw new IllegalStateException("Only the AI-generated views are sent to the 3D generator. Your photos and "
					+ "web photos are references only, so there is nothing to leave out.");
		}
		List<ReferenceImage> remaining = getIncludedViews().stream().filter(other -> other != image).toList();
		if (remaining.isEmpty()) {
			throw new IllegalStateException("At least one generated view is required.");
		}
		if (image.getAngle() == Angle.FRONT && remaining.stream().noneMatch(other -> other.getAngle() == Angle.FRONT)) {
			throw new IllegalStateException("The front view can't be excluded: 3D generation requires a front view.");
		}
		image.setExcluded(true);
	}

	// The included AI-generated views: what the mesh generator is sent.
	private List<ReferenceImage> getIncludedViews() {
		return getIncluded().stream().filter(image -> image.getOrigin() == ImageOrigin.AI_GENERATED).toList();
	}

	public void include(String imageId) {
		find(imageId).setExcluded(false);
	}

	public List<ReferenceImage> getImages() {
		return Collections.unmodifiableList(images);
	}

	public List<ReferenceImage> getIncluded() {
		return images.stream().filter(image -> !image.isExcluded()).toList();
	}

	// True when an included generated front view exists, which building a model needs.
	public boolean hasFrontView() {
		return getIncludedViews().stream().anyMatch(image -> image.getAngle() == Angle.FRONT);
	}

	public List<Angle> missingAngles() {
		Set<Angle> missing = EnumSet.allOf(Angle.class);
		getIncluded().forEach(image -> missing.remove(image.getAngle()));
		return new ArrayList<>(missing);
	}

	// Deep copy, so later edits to the working set don't change older versions.
	public ReferenceImageSet copy() {
		ReferenceImageSet copy = new ReferenceImageSet();
		images.forEach(image -> copy.add(image.copy()));
		return copy;
	}

	private ReferenceImage find(String imageId) {
		return images.stream()
				.filter(image -> image.getId().equals(imageId))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("No image with id " + imageId));
	}
}
