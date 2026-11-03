package AIMesher.ai_mesh_generator.exporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StlExporterTest {

	private static final float TOLERANCE = 1e-6f;

	@TempDir
	Path dir;

	private final StlExporter exporter = new StlExporter(new GlbReader());

	private ByteBuffer export(TestGlb glb) throws Exception {
		Path out = dir.resolve("out.stl");
		exporter.export(glb.write(dir), out);
		return ByteBuffer.wrap(Files.readAllBytes(out)).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static void assertVector(float x, float y, float z, ByteBuffer stl, int offset) {
		assertEquals(x, stl.getFloat(offset), TOLERANCE, "x at " + offset);
		assertEquals(y, stl.getFloat(offset + 4), TOLERANCE, "y at " + offset);
		assertEquals(z, stl.getFloat(offset + 8), TOLERANCE, "z at " + offset);
	}

	@Test
	void sizeAndTriangleCountMatchTheModel() throws Exception {
		TestGlb glb = new TestGlb();
		glb.mesh(glb.floats("VEC3", 0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0), null, null, glb.indices(5123, 0, 1, 2, 2, 1, 3));

		ByteBuffer stl = export(glb);

		assertEquals(StlExporter.HEADER_BYTES + 4 + 2 * StlExporter.TRIANGLE_BYTES, stl.capacity());
		assertEquals(2, stl.getInt(StlExporter.HEADER_BYTES));
	}

	@Test
	void headerDoesNotStartWithSolid() throws Exception {
		ByteBuffer stl = export(TestGlb.triangle());

		String header = new String(stl.array(), 0, 5, StandardCharsets.US_ASCII);
		assertFalse(header.equalsIgnoreCase("solid"));
	}

	@Test
	void turnsYUpIntoZUpAndComputesTheNormal() throws Exception {
		// A triangle lying flat on the glTF ground (y = 0), facing up (+Y).
		TestGlb glb = new TestGlb();
		glb.mesh(glb.floats("VEC3", 0, 0, 0, 0, 0, 1, 1, 0, 0), null, null, null);

		ByteBuffer stl = export(glb);

		int triangle = StlExporter.HEADER_BYTES + 4;
		// In STL it lies on z = 0 and faces up (+Z), the way a slicer expects.
		assertVector(0, 0, 1, stl, triangle);
		assertVector(0, 0, 0, stl, triangle + 12);
		assertVector(0, -1, 0, stl, triangle + 24);
		assertVector(1, 0, 0, stl, triangle + 36);
		assertEquals(0, stl.getShort(triangle + 48));
	}

	@Test
	void modelThatCantBeConvertedLeavesAnExistingFileUntouched() throws Exception {
		Path out = dir.resolve("out.stl");
		Files.writeString(out, "previous export");
		File broken = TestGlb.write(dir, "not a model".getBytes(StandardCharsets.UTF_8));

		assertThrows(IllegalArgumentException.class, () -> exporter.export(broken, out));

		assertEquals("previous export", Files.readString(out));
	}

	@Test
	void unwritableLocationThrows() throws Exception {
		File model = TestGlb.triangle().write(dir);

		assertThrows(UncheckedIOException.class, () -> exporter.export(model, dir.resolve("missing/out.stl")));
	}
}
