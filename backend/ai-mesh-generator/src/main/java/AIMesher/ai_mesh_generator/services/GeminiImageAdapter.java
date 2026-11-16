package AIMesher.ai_mesh_generator.services;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import AIMesher.ai_mesh_generator.model.Angle;
import AIMesher.ai_mesh_generator.services.GeminiApi.GeneratedImage;

// Adapter: implements ImageGenerator on top of the Gemini image model (the adaptee, through GeminiApi).
// Every view is its own request with a prompt written for that angle, and every image is asked for in the same
// style (whole object, plain white background, even light), because the mesh generator works best on clean views.
@Component
public class GeminiImageAdapter implements ImageGenerator {

	static final String STYLE = """
			Show the whole object, centred and filling most of the frame, on a plain white background, with even
			studio lighting and no perspective distortion. The camera is level with the middle of the object and
			looks at it horizontally, not down on it from above. No text, no watermark, no hands, no other objects.""";

	// Where the camera is for each view. The side views are described against the front image (the first
	// reference, see ImageGenerator.generateAngle()) in terms of its edges, because "the object's left" is easily
	// read as the viewer's left. An object facing the camera has its own left side at the right edge of the image.
	static final Map<Angle, String> VIEWPOINTS = Map.of(
			Angle.FRONT, "straight from the front, looking at the side people normally see first",
			Angle.LEFT, "from its left side. Turn the object a quarter turn: the side at the RIGHT edge of the front "
					+ "image now faces the camera, the part that faces the camera in the front image is now at the LEFT "
					+ "edge, and the side at the left edge of the front image is hidden",
			Angle.RIGHT, "from its right side. Turn the object a quarter turn: the side at the LEFT edge of the front "
					+ "image now faces the camera, the part that faces the camera in the front image is now at the RIGHT "
					+ "edge, and the side at the right edge of the front image is hidden",
			Angle.BACK, "from directly behind. Turn the object half way round: the side that faces away in the front "
					+ "image now faces the camera, and what is at the left edge of the front image is now at the right "
					+ "edge");

	private static final Map<String, String> EXTENSIONS = Map.of(
			"image/png", ".png",
			"image/jpeg", ".jpg",
			"image/webp", ".webp");

	private final GeminiApi client;
	private final Path outputDir;

	@Autowired
	public GeminiImageAdapter(GeminiApi client) {
		this(client, Path.of(System.getProperty("java.io.tmpdir"), "ai-mesh-generator"));
	}

	// Tests write into a temp folder.
	GeminiImageAdapter(GeminiApi client, Path outputDir) {
		this.client = client;
		this.outputDir = outputDir;
	}

	@Override
	public File generateFromPrompt(String prompt) {
		if (prompt == null || prompt.isBlank()) {
			throw new IllegalArgumentException("An image prompt can't be empty.");
		}
		String full = prompt.strip() + "\nPhotograph it " + VIEWPOINTS.get(Angle.FRONT) + ".\n" + STYLE;
		return save(client.generateImage(full, List.of()), Angle.FRONT);
	}

	@Override
	public File generateAngle(List<File> refs, String subject, Angle angle) {
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("Say what the object is before generating a view of it.");
		}
		if (angle != Angle.FRONT && refs.isEmpty()) {
			throw new IllegalArgumentException("The " + angle.name().toLowerCase()
					+ " view is turned from the front view, so the front view must be its first reference.");
		}
		return save(client.generateImage(anglePrompt(refs.size(), subject.strip(), angle), refs), angle);
	}

	static String anglePrompt(int refCount, String subject, Angle angle) {
		// The references set what the object looks like, never the camera: copying their angle made every view
		// look like the front, and copying a photo taken from above tilted every view.
		String references = refCount == 0 ? "" : """
				The %d attached image(s) show this exact object. Keep its shape, proportions, parts, colours and \
				materials as they appear there; do not redesign or simplify it. Do not copy their camera angle or \
				framing: use only the viewpoint described below.
				""".formatted(refCount);
		String front = angle == Angle.FRONT ? "" : """
				The first attached image is the front view of the object. Do not repeat it: show the side described \
				below.
				""";
		return """
				Create a product photo of this object: %s.
				%s%sPhotograph it %s.
				%s""".formatted(subject, references, front, VIEWPOINTS.get(angle), STYLE);
	}

	private File save(GeneratedImage image, Angle angle) {
		String extension = EXTENSIONS.getOrDefault(image.mimeType(), ".png");
		try {
			Files.createDirectories(outputDir);
			Path file = Files.createTempFile(outputDir, "view-" + angle.name().toLowerCase() + "-", extension);
			Files.write(file, image.data());
			return file.toFile();
		} catch (IOException e) {
			throw new UncheckedIOException("Could not save the generated " + angle.name().toLowerCase() + " view", e);
		}
	}
}
