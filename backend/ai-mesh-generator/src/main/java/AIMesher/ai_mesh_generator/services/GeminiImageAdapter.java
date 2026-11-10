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
			studio lighting, the camera level with the middle of the object and no perspective distortion.
			No text, no watermark, no hands, no other objects.""";

	// Where the camera is for each view, measured from the object's front.
	static final Map<Angle, String> VIEWPOINTS = Map.of(
			Angle.FRONT, "straight from the front, looking at the side people normally see first",
			Angle.LEFT, "from its left side: the camera is turned 90 degrees to the object's left from the front view, "
					+ "so only the left side is visible",
			Angle.RIGHT, "from its right side: the camera is turned 90 degrees to the object's right from the front "
					+ "view, so only the right side is visible",
			Angle.BACK, "from directly behind: the camera is turned 180 degrees from the front view");

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
		return save(client.generateImage(anglePrompt(refs.size(), subject.strip(), angle), refs), angle);
	}

	static String anglePrompt(int refCount, String subject, Angle angle) {
		String references = refCount == 0 ? "" : """
				The %d attached image(s) show this exact object. Keep its shape, proportions, parts, colours and \
				materials exactly as they appear there; do not redesign or simplify it. If one image is already a \
				clean view on a white background, treat it as the most accurate reference.
				""".formatted(refCount);
		return """
				Create a product photo of this object: %s.
				%sPhotograph it %s.
				%s""".formatted(subject, references, VIEWPOINTS.get(angle), STYLE);
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
