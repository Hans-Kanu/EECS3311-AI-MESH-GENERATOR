package AIMesher.ai_mesh_generator.exporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObjExporterTest {

	@TempDir
	Path dir;

	private final ObjExporter exporter = new ObjExporter(new GlbReader());

	private List<String> export(TestGlb glb) throws Exception {
		Path out = dir.resolve("out.obj");
		exporter.export(glb.write(dir), out);
		return Files.readAllLines(out);
	}

	private static List<String> lines(List<String> obj, String prefix) {
		return obj.stream().filter(line -> line.startsWith(prefix + " ")).toList();
	}

	@Test
	void writesPositionsUvsNormalsAndFaces() throws Exception {
		List<String> obj = export(TestGlb.triangle());

		assertEquals(List.of("v 0 0 0", "v 1 0 0", "v 0 1 0"), lines(obj, "v"));
		assertEquals(List.of("vn 0 0 1", "vn 0 0 1", "vn 0 0 1"), lines(obj, "vn"));
		assertEquals(List.of("f 1/1/1 2/2/2 3/3/3"), lines(obj, "f"));
	}

	@Test
	void flipsTheVTextureCoordinate() throws Exception {
		// glTF uvs (0,0) (1,0) (0,1) become (0,1) (1,1) (0,0) in OBJ.
		assertEquals(List.of("vt 0 1", "vt 1 1", "vt 0 0"), lines(export(TestGlb.triangle()), "vt"));
	}

	@Test
	void facesWithoutUvsOrNormalsUseJustThePosition() throws Exception {
		TestGlb glb = new TestGlb();
		glb.mesh(glb.floats("VEC3", 0, 0, 0, 1, 0, 0, 0, 1, 0), null, null, null);

		List<String> obj = export(glb);

		assertEquals(List.of("f 1 2 3"), lines(obj, "f"));
		assertTrue(lines(obj, "vt").isEmpty());
		assertTrue(lines(obj, "vn").isEmpty());
	}

	@Test
	void everyFaceRefersToARealVertex() throws Exception {
		TestGlb glb = TestGlb.triangle();
		glb.mesh(glb.floats("VEC3", 0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0), glb.floats("VEC3", 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1),
				glb.floats("VEC2", 0, 0, 1, 0, 0, 1, 1, 1), glb.indices(5121, 0, 1, 2, 2, 1, 3));

		List<String> obj = export(glb);

		int vertices = lines(obj, "v").size();
		assertEquals(7, vertices);
		assertEquals(3, lines(obj, "f").size());
		for (String face : lines(obj, "f")) {
			for (String corner : face.substring(2).split(" ")) {
				for (String number : corner.split("/")) {
					int index = Integer.parseInt(number);
					assertTrue(index >= 1 && index <= vertices, face);
				}
			}
		}
	}

	@Test
	void usesADotDecimalPointWhateverTheSystemLanguage() throws Exception {
		TestGlb glb = TestGlb.triangle();
		glb.node(0).putArray("translation").add(0.5).add(0).add(0);
		Locale original = Locale.getDefault();
		Locale.setDefault(Locale.GERMANY);
		try {
			assertEquals("v 0.5 0 0", lines(export(glb), "v").get(0));
		} finally {
			Locale.setDefault(original);
		}
	}

	@Test
	void modelThatCantBeConvertedLeavesAnExistingFileUntouched() throws Exception {
		Path out = dir.resolve("out.obj");
		Files.writeString(out, "previous export");
		File broken = TestGlb.write(dir, "not a model".getBytes(StandardCharsets.UTF_8));

		assertThrows(IllegalArgumentException.class, () -> exporter.export(broken, out));

		assertEquals("previous export", Files.readString(out));
	}

	@Test
	void unwritableLocationThrows() throws Exception {
		File model = TestGlb.triangle().write(dir);

		assertThrows(UncheckedIOException.class, () -> exporter.export(model, dir.resolve("missing/out.obj")));
	}
}
