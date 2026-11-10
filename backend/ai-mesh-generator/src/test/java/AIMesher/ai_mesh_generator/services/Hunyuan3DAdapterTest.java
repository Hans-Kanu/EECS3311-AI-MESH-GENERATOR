package AIMesher.ai_mesh_generator.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import AIMesher.ai_mesh_generator.config.HunyuanProperties;
import AIMesher.ai_mesh_generator.model.Angle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// Uses a stub Hunyuan3DApi that records requests and serves canned replies and files, so no key or network is needed.
class Hunyuan3DAdapterTest {

	@TempDir
	Path dir;

	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final byte[] GLB = { 'g', 'l', 'T', 'F', 2 };

	private static final class StubApi extends Hunyuan3DApi {

		ObjectNode submitted;
		String reply;
		// What download() writes, by URL.
		final Map<String, byte[]> files = new java.util.HashMap<>();
		final List<String> downloaded = new ArrayList<>();

		StubApi() {
			super(new HunyuanProperties("id", "key", "ap-singapore", "https://unused", "2023-09-01"));
		}

		@Override
		public String submitProJob(ObjectNode params) {
			submitted = params;
			return "job-1";
		}

		@Override
		public JsonNode queryProJob(String jobId) {
			return MAPPER.readTree(reply);
		}

		@Override
		public void download(String url, Path target) {
			downloaded.add(url);
			try {
				Files.write(target, files.get(url));
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}
	}

	private final StubApi api = new StubApi();
	private final Hunyuan3DAdapter adapter = new Hunyuan3DAdapter(api);

	private File png(String name, int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		File file = dir.resolve(name).toFile();
		ImageIO.write(image, "png", file);
		return file;
	}

	private static BufferedImage decode(String base64) throws IOException {
		return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
	}

	private Map<Angle, File> fourViews() throws IOException {
		Map<Angle, File> views = new EnumMap<>(Angle.class);
		for (Angle angle : Angle.values()) {
			views.put(angle, png(angle.name() + ".png", 400, 300));
		}
		return views;
	}

	@Test
	void submitSendsTheFrontAsTheMainImageAndTheOtherViewsByType() throws Exception {
		String jobId = adapter.submitJob(fourViews());

		assertEquals("job-1", jobId);
		assertEquals(400, decode(api.submitted.path("ImageBase64").asString()).getWidth());
		JsonNode views = api.submitted.path("MultiViewImages");
		assertEquals(List.of("left", "right", "back"),
				List.of(views.get(0).path("ViewType").asString(), views.get(1).path("ViewType").asString(),
						views.get(2).path("ViewType").asString()));
		assertEquals(300, decode(views.get(2).path("ViewImageBase64").asString()).getHeight());
	}

	@Test
	void largeViewsAreShrunkToTheMaximumSideAsJpeg() throws Exception {
		Map<Angle, File> views = Map.of(Angle.FRONT, png("big.png", 3000, 1500));

		adapter.submitJob(views);

		byte[] jpeg = Base64.getDecoder().decode(api.submitted.path("ImageBase64").asString());
		assertEquals((byte) 0xFF, jpeg[0]);
		assertEquals((byte) 0xD8, jpeg[1]);
		BufferedImage sent = ImageIO.read(new ByteArrayInputStream(jpeg));
		assertEquals(Hunyuan3DAdapter.MAX_SIDE, sent.getWidth());
		assertEquals(Hunyuan3DAdapter.MAX_SIDE / 2, sent.getHeight());
		assertFalse(api.submitted.has("MultiViewImages"));
	}

	@Test
	void aFrontViewIsRequired() throws Exception {
		Map<Angle, File> views = Map.of(Angle.BACK, png("back.png", 400, 400));

		assertThrows(IllegalArgumentException.class, () -> adapter.submitJob(views));
	}

	@Test
	void tooSmallOrUnreadableImagesAreRejected() throws Exception {
		File tiny = png("tiny.png", 100, 100);
		File text = Files.writeString(dir.resolve("not-an-image.png"), "hello").toFile();

		assertThrows(IllegalArgumentException.class, () -> adapter.submitJob(Map.of(Angle.FRONT, tiny)));
		assertThrows(IllegalArgumentException.class, () -> adapter.submitJob(Map.of(Angle.FRONT, text)));
	}

	@Test
	void statusesMapToJobStatus() {
		api.reply = "{\"Status\":\"WAIT\"}";
		assertEquals(JobStatus.QUEUED, adapter.getStatus("job-1"));
		api.reply = "{\"Status\":\"RUN\"}";
		assertEquals(JobStatus.RUNNING, adapter.getStatus("job-1"));
		api.reply = "{\"Status\":\"DONE\"}";
		assertEquals(JobStatus.DONE, adapter.getStatus("job-1"));
	}

	@Test
	void aFailedJobReportsTencentsReason() {
		api.reply = """
				{"Status":"FAIL","ErrorCode":"InvalidParameter","ErrorMessage":"image has no object"}""";

		IllegalStateException error = assertThrows(IllegalStateException.class, () -> adapter.getStatus("job-1"));

		assertTrue(error.getMessage().contains("InvalidParameter"));
		assertTrue(error.getMessage().contains("image has no object"));
	}

	@Test
	void downloadTakesTheGlbOutOfTheResultZip() throws Exception {
		String zipUrl = "https://cos.example/result/xxx.zip?sign=abc";
		api.files.put(zipUrl, zip(ordered("model/model.obj", new byte[] { 1 }, "model/model.glb", GLB)));
		api.reply = """
				{"Status":"DONE","ResultFile3Ds":[{"Type":"OBJ","Url":"%s","PreviewImageUrl":"https://cos.example/p.png"}]}"""
				.formatted(zipUrl);

		File model = adapter.download("job-1");

		assertArrayEquals(GLB, Files.readAllBytes(model.toPath()));
		assertTrue(model.getName().endsWith(".glb"));
	}

	@Test
	void aDirectGlbLinkIsPreferred() throws Exception {
		api.files.put("https://cos.example/m.glb", GLB);
		api.reply = """
				{"Status":"DONE","ResultFile3Ds":[{"Type":"OBJ","Url":"https://cos.example/m.zip"},{"Type":"GLB","Url":"https://cos.example/m.glb"}]}""";

		File model = adapter.download("job-1");

		assertArrayEquals(GLB, Files.readAllBytes(model.toPath()));
		assertEquals(List.of("https://cos.example/m.glb"), api.downloaded);
	}

	@Test
	void aZipWithoutAGlbIsReported() throws Exception {
		api.files.put("https://cos.example/m.zip", zip(Map.of("model.obj", new byte[] { 1 })));
		api.reply = """
				{"Status":"DONE","ResultFile3Ds":[{"Type":"OBJ","Url":"https://cos.example/m.zip"}]}""";

		IllegalStateException error = assertThrows(IllegalStateException.class, () -> adapter.download("job-1"));

		assertTrue(error.getMessage().contains("no GLB file"));
		assertTrue(error.getMessage().contains("model.obj"));
	}

	@Test
	void noUsableFileIsReported() {
		api.reply = """
				{"Status":"DONE","ResultFile3Ds":[{"Type":"Image","Url":"https://cos.example/p.png"}]}""";

		IllegalStateException error = assertThrows(IllegalStateException.class, () -> adapter.download("job-1"));

		assertTrue(error.getMessage().contains("no GLB model or zip"));
	}

	@Test
	void zipEntryNamesAreNeverUsedAsPaths() throws Exception {
		Path zip = dir.resolve("evil.zip");
		Files.write(zip, zip(Map.of("../../escape.glb", GLB)));

		Path model = Hunyuan3DAdapter.extractGlb(zip);

		assertFalse(Files.exists(dir.getParent().getParent().resolve("escape.glb")));
		assertArrayEquals(GLB, Files.readAllBytes(model));
	}

	private static Map<String, byte[]> ordered(String name1, byte[] data1, String name2, byte[] data2) {
		Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
		entries.put(name1, data1);
		entries.put(name2, data2);
		return entries;
	}

	// Entries are written in the order given.
	private static byte[] zip(Map<String, byte[]> entries) throws IOException {
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		try (ZipOutputStream out = new ZipOutputStream(bytes)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				out.putNextEntry(new ZipEntry(entry.getKey()));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
		return bytes.toByteArray();
	}
}
