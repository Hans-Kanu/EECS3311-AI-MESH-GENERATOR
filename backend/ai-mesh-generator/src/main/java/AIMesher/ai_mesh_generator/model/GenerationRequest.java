package AIMesher.ai_mesh_generator.model;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// The user's input from F02: reference images, a description, or both.
public class GenerationRequest {

	static final Set<String> SUPPORTED_FORMATS = Set.of("png", "jpg", "jpeg", "webp");
	static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

	private final List<File> images;
	private final String description;

	public GenerationRequest(List<File> images, String description) {
		this.images = images == null ? List.of() : List.copyOf(images);
		this.description = description == null ? "" : description.strip();
	}

	public List<File> getImages() {
		return images;
	}

	public String getDescription() {
		return description;
	}

	public boolean hasImage() {
		return !images.isEmpty();
	}

	public boolean hasDescription() {
		return !description.isEmpty();
	}

	// Throws with a message the view can show as is.
	public void validate() {
		if (!hasImage() && !hasDescription()) {
			throw new IllegalArgumentException("Give at least one reference image or a description.");
		}
		for (File image : images) {
			if (!SUPPORTED_FORMATS.contains(extensionOf(image))) {
				throw new IllegalArgumentException(image.getName() + " is not a supported format. Use one of: "
						+ String.join(", ", SUPPORTED_FORMATS.stream().sorted().toList()) + ".");
			}
			if (!image.isFile() || !image.canRead()) {
				throw new IllegalArgumentException(image.getName() + " can't be read. Choose a different image.");
			}
			if (image.length() > MAX_FILE_BYTES) {
				throw new IllegalArgumentException(image.getName() + " is larger than the 10 MB limit.");
			}
		}
	}

	private static String extensionOf(File file) {
		String name = file.getName();
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}
}
