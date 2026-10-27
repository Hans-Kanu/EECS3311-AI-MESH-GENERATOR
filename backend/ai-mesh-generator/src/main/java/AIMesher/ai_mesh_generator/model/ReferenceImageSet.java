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

	// Rejects the change if it would leave the set without a front view or without any image,
	// since the mesh generator needs both. The controller shows the message to the user.
	public void exclude(String imageId) {
		ReferenceImage image = find(imageId);
		if (image.isExcluded()) {
			return;
		}
		List<ReferenceImage> remaining = getIncluded().stream().filter(other -> other != image).toList();
		if (remaining.isEmpty()) {
			throw new IllegalStateException("At least one reference image is required.");
		}
		if (image.getAngle() == Angle.FRONT && remaining.stream().noneMatch(other -> other.getAngle() == Angle.FRONT)) {
			throw new IllegalStateException("The front view can't be excluded: 3D generation requires a front view.");
		}
		image.setExcluded(true);
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

	public boolean hasFrontView() {
		return getIncluded().stream().anyMatch(image -> image.getAngle() == Angle.FRONT);
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
