package AIMesher.ai_mesh_generator.services;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;

import AIMesher.ai_mesh_generator.model.Angle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// Adapter: implements the system's MeshGenerator (submitJob / getStatus / download) on top of Tencent's Hunyuan 3D
// Pro API (the adaptee, Hunyuan3DApi). It also prepares the views the way the service needs them: JPEG, at most
// MAX_SIDE pixels, with the front view as the main image and the other views as MultiViewImages.
@Component
public class Hunyuan3DAdapter implements MeshGenerator {

	// Keeps four views well under the API's 8 MB limit on the encoded images.
	static final int MAX_SIDE = 1024;
	static final int MIN_SIDE = 128;
	static final long MAX_ENCODED_BYTES = 8L * 1024 * 1024;
	// A model inside the result zip larger than this is refused rather than filling the disk.
	static final long MAX_MODEL_BYTES = 500L * 1024 * 1024;

	private static final Map<Angle, String> VIEW_TYPES = Map.of(Angle.LEFT, "left", Angle.RIGHT, "right", Angle.BACK, "back");

	private final Hunyuan3DApi api;

	public Hunyuan3DAdapter(Hunyuan3DApi api) {
		this.api = api;
	}

	@Override
	public String submitJob(Map<Angle, File> views) {
		File front = views.get(Angle.FRONT);
		if (front == null) {
			throw new IllegalArgumentException("Hunyuan 3D needs a front view.");
		}
		ObjectNode params = api.newParams();
		String frontBase64 = base64Jpeg(front);
		params.put("ImageBase64", frontBase64);
		long encoded = frontBase64.length();
		ArrayNode others = params.putArray("MultiViewImages");
		for (Angle angle : List.of(Angle.LEFT, Angle.RIGHT, Angle.BACK)) {
			File view = views.get(angle);
			if (view != null) {
				String base64 = base64Jpeg(view);
				encoded += base64.length();
				others.addObject().put("ViewType", VIEW_TYPES.get(angle)).put("ViewImageBase64", base64);
			}
		}
		if (others.isEmpty()) {
			params.remove("MultiViewImages");
		}
		if (encoded > MAX_ENCODED_BYTES) {
			throw new IllegalArgumentException("The views are too large for Hunyuan 3D even after resizing.");
		}
		return api.submitProJob(params);
	}

	@Override
	public JobStatus getStatus(String jobId) {
		JsonNode job = api.queryProJob(jobId);
		String status = job.path("Status").asString("");
		return switch (status) {
			case "WAIT" -> JobStatus.QUEUED;
			case "RUN" -> JobStatus.RUNNING;
			case "DONE" -> JobStatus.DONE;
			// The reason is only in this reply, so it is reported here instead of as a bare FAILED.
			case "FAIL" -> throw new IllegalStateException("Hunyuan 3D could not build the model ("
					+ job.path("ErrorCode").asString("no code") + "): " + job.path("ErrorMessage").asString("no message"));
			default -> throw new IllegalStateException("Hunyuan 3D returned an unknown job status \"" + status + "\".");
		};
	}

	// The finished job lists its files in ResultFile3Ds. A GLB link is used as is; the default result is a zip
	// holding OBJ and GLB files, and the GLB is taken out of it.
	@Override
	public File download(String jobId) {
		JsonNode files = api.queryProJob(jobId).path("ResultFile3Ds");
		String glbUrl = null;
		String zipUrl = null;
		List<String> seen = new ArrayList<>();
		for (int i = 0; i < files.size(); i++) {
			JsonNode file = files.get(i);
			String url = file.path("Url").asString("");
			String type = file.path("Type").asString("");
			String path = pathOf(url);
			seen.add(type + " " + path.substring(path.lastIndexOf('/') + 1));
			if (glbUrl == null && (type.equalsIgnoreCase("GLB") || path.endsWith(".glb"))) {
				glbUrl = url;
			} else if (zipUrl == null && path.endsWith(".zip")) {
				zipUrl = url;
			}
		}
		if (glbUrl != null) {
			Path model = Hunyuan3DApi.tempFile("model-", ".glb");
			api.download(glbUrl, model);
			return model.toFile();
		}
		if (zipUrl != null) {
			Path zip = Hunyuan3DApi.tempFile("model-", ".zip");
			try {
				api.download(zipUrl, zip);
				return extractGlb(zip).toFile();
			} finally {
				zip.toFile().delete();
			}
		}
		throw new IllegalStateException("Hunyuan 3D returned no GLB model or zip (files: " + seen + ").");
	}

	// Takes the first .glb file out of the zip. Its name inside the zip is never used as a path, so a crafted
	// entry name like ../../x can't write outside the temp folder.
	static Path extractGlb(Path zip) {
		List<String> names = new ArrayList<>();
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				names.add(entry.getName());
				if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".glb")) {
					Path model = Hunyuan3DApi.tempFile("model-", ".glb");
					copyLimited(in, model);
					return model;
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read the model zip from Hunyuan 3D.", e);
		}
		throw new IllegalStateException("The model zip from Hunyuan 3D has no GLB file (it has " + names + ").");
	}

	private static void copyLimited(InputStream in, Path target) throws IOException {
		byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, MAX_MODEL_BYTES + 1));
		if (bytes.length > MAX_MODEL_BYTES) {
			throw new IllegalStateException("The model from Hunyuan 3D is larger than "
					+ MAX_MODEL_BYTES / (1024 * 1024) + " MB.");
		}
		Files.write(target, bytes);
	}

	// Re-encodes a view as a JPEG no larger than MAX_SIDE on its long side. Transparent areas become white,
	// matching the plain white background the views are generated on.
	static String base64Jpeg(File image) {
		BufferedImage source;
		try {
			source = ImageIO.read(image);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + image.getName(), e);
		}
		if (source == null) {
			throw new IllegalArgumentException(image.getName() + " can't be sent to Hunyuan 3D: use a PNG or JPEG image.");
		}
		double scale = Math.min(1.0, MAX_SIDE / (double) Math.max(source.getWidth(), source.getHeight()));
		int width = (int) Math.round(source.getWidth() * scale);
		int height = (int) Math.round(source.getHeight() * scale);
		if (Math.min(width, height) < MIN_SIDE) {
			throw new IllegalArgumentException(image.getName() + " is too small or too narrow for Hunyuan 3D (at least "
					+ MIN_SIDE + " pixels on each side).");
		}
		BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = rgb.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, width, height);
			g.drawImage(source, 0, 0, width, height, null);
		} finally {
			g.dispose();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			ImageIO.write(rgb, "jpg", out);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not convert " + image.getName() + " to JPEG", e);
		}
		return Base64.getEncoder().encodeToString(out.toByteArray());
	}

	private static String pathOf(String url) {
		try {
			String path = URI.create(url).getPath();
			return path == null ? "" : path.toLowerCase(Locale.ROOT);
		} catch (IllegalArgumentException e) {
			return url.toLowerCase(Locale.ROOT);
		}
	}
}
