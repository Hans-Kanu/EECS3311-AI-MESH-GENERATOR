package AIMesher.ai_mesh_generator.exporter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.node.ObjectNode;

class GlbReaderTest {

	private static final float TOLERANCE = 1e-6f;

	@TempDir
	Path dir;

	private final GlbReader reader = new GlbReader();

	private GlbModel read(TestGlb glb) throws Exception {
		return reader.read(glb.write(dir));
	}

	private IllegalArgumentException rejected(TestGlb glb) throws Exception {
		File file = glb.write(dir);
		return assertThrows(IllegalArgumentException.class, () -> reader.read(file));
	}

	private static void assertFloats(float[] expected, float[] actual) {
		assertEquals(expected.length, actual.length, "length");
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], actual[i], TOLERANCE, "value " + i + " of " + Arrays.toString(actual));
		}
	}

	@Test
	void readsTriangleWithNormalsUvsAndIndices() throws Exception {
		GlbModel model = read(TestGlb.triangle());

		assertFloats(new float[] { 0, 0, 0, 1, 0, 0, 0, 1, 0 }, model.positions());
		assertFloats(new float[] { 0, 0, 1, 0, 0, 1, 0, 0, 1 }, model.normals());
		assertFloats(new float[] { 0, 0, 1, 0, 0, 1 }, model.uvs());
		assertArrayEquals(new int[] { 0, 1, 2 }, model.indices());
		assertEquals(3, model.vertexCount());
		assertEquals(1, model.triangleCount());
	}

	@Test
	void readsByteShortAndIntIndices() throws Exception {
		for (int componentType : new int[] { 5121, 5123, 5125 }) {
			TestGlb glb = new TestGlb();
			glb.mesh(glb.floats("VEC3", 0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0), null, null,
					glb.indices(componentType, 0, 1, 2, 2, 1, 3));

			assertArrayEquals(new int[] { 0, 1, 2, 2, 1, 3 }, read(glb).indices(), "component type " + componentType);
		}
	}

	@Test
	void withoutIndicesUsesTheVerticesInOrder() throws Exception {
		TestGlb glb = new TestGlb();
		glb.mesh(glb.floats("VEC3", 0, 0, 0, 1, 0, 0, 0, 1, 0), null, null, null);

		GlbModel model = read(glb);

		assertArrayEquals(new int[] { 0, 1, 2 }, model.indices());
		assertFalse(model.hasNormals());
		assertFalse(model.hasUvs());
	}

	@Test
	void readsInterleavedPositionsAndNormals() throws Exception {
		TestGlb glb = new TestGlb();
		int[] accessors = glb.interleaved(new float[] { 0, 0, 0, 1, 0, 0, 0, 1, 0 }, new float[] { 0, 0, 1, 0, 0, 1, 0, 0, 1 });
		glb.mesh(accessors[0], accessors[1], null, null);

		GlbModel model = read(glb);

		assertFloats(new float[] { 0, 0, 0, 1, 0, 0, 0, 1, 0 }, model.positions());
		assertFloats(new float[] { 0, 0, 1, 0, 0, 1, 0, 0, 1 }, model.normals());
	}

	@Test
	void appliesNodeTranslationAndScale() throws Exception {
		TestGlb glb = TestGlb.triangle();
		glb.node(0).putArray("translation").add(1).add(2).add(3);
		glb.node(0).putArray("scale").add(2).add(2).add(2);

		assertFloats(new float[] { 1, 2, 3, 3, 2, 3, 1, 4, 3 }, read(glb).positions());
	}

	@Test
	void appliesNodeRotationToPositionsAndNormals() throws Exception {
		TestGlb glb = TestGlb.triangle();
		// 90 degrees about X: +Y turns into +Z, and +Z into -Y.
		float half = (float) Math.sqrt(0.5);
		glb.node(0).putArray("rotation").add(half).add(0).add(0).add(half);

		GlbModel model = read(glb);

		assertFloats(new float[] { 0, 0, 0, 1, 0, 0, 0, 0, 1 }, model.positions());
		assertFloats(new float[] { 0, -1, 0, 0, -1, 0, 0, -1, 0 }, model.normals());
	}

	@Test
	void appliesAMatrixGivenInsteadOfTranslationRotationScale() throws Exception {
		TestGlb glb = TestGlb.triangle();
		// Column-major: translate by (5, 0, 0).
		var matrix = glb.node(0).putArray("matrix");
		for (double value : new double[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 5, 0, 0, 1 }) {
			matrix.add(value);
		}

		assertFloats(new float[] { 5, 0, 0, 6, 0, 0, 5, 1, 0 }, read(glb).positions());
	}

	@Test
	void parentTransformAppliesToChildren() throws Exception {
		TestGlb glb = TestGlb.triangle();
		// A new root node 1 moved by (10, 0, 0), with the mesh node 0 as its child.
		ObjectNode parent = glb.addNode();
		parent.putArray("translation").add(10).add(0).add(0);
		parent.putArray("children").add(0);
		glb.sceneNodes().removeAll().add(1);

		assertFloats(new float[] { 10, 0, 0, 11, 0, 0, 10, 1, 0 }, read(glb).positions());
	}

	@Test
	void nonUniformScaleKeepsNormalsPerpendicular() throws Exception {
		TestGlb glb = new TestGlb();
		// A slanted face whose normal is (1, 1, 0) / sqrt(2).
		float n = (float) Math.sqrt(0.5);
		glb.mesh(glb.floats("VEC3", 1, 0, 0, 0, 1, 0, 0, 1, 1), glb.floats("VEC3", n, n, 0, n, n, 0, n, n, 0), null, null);
		glb.node(0).putArray("scale").add(2).add(1).add(1);

		GlbModel model = read(glb);

		// Stretched along X the face tilts towards Y, so the normal becomes (1, 2, 0) / sqrt(5), not (2, 1, 0).
		float s = (float) Math.sqrt(5);
		assertFloats(new float[] { 1 / s, 2 / s, 0 }, Arrays.copyOf(model.normals(), 3));
	}

	@Test
	void mirroringReversesTheWinding() throws Exception {
		TestGlb glb = TestGlb.triangle();
		glb.node(0).putArray("scale").add(-1).add(1).add(1);

		GlbModel model = read(glb);

		assertArrayEquals(new int[] { 0, 2, 1 }, model.indices());
		assertFloats(new float[] { 0, 0, 1 }, Arrays.copyOf(model.normals(), 3));
	}

	@Test
	void mergesMeshesAndShiftsTheirIndices() throws Exception {
		TestGlb glb = TestGlb.triangle();
		glb.mesh(glb.floats("VEC3", 5, 5, 5, 6, 5, 5, 5, 6, 5), glb.floats("VEC3", 0, 0, 1, 0, 0, 1, 0, 0, 1),
				glb.floats("VEC2", 0, 0, 1, 0, 0, 1), glb.indices(5123, 0, 1, 2));

		GlbModel model = read(glb);

		assertEquals(6, model.vertexCount());
		assertArrayEquals(new int[] { 0, 1, 2, 3, 4, 5 }, model.indices());
	}

	@Test
	void dropsNormalsWhenOnlySomeMeshesHaveThem() throws Exception {
		TestGlb glb = TestGlb.triangle();
		glb.mesh(glb.floats("VEC3", 5, 5, 5, 6, 5, 5, 5, 6, 5), null, glb.floats("VEC2", 0, 0, 1, 0, 0, 1), null);

		GlbModel model = read(glb);

		assertNull(model.normals());
		assertTrue(model.hasUvs());
	}

	@Test
	void readsNormalizedByteAndShortTextureCoordinates() throws Exception {
		TestGlb bytes = new TestGlb();
		bytes.mesh(bytes.floats("VEC3", 0, 0, 0, 1, 0, 0, 0, 1, 0), null, bytes.normalized(5121, "VEC2", 0, 0, 255, 0, 0, 51), null);
		TestGlb shorts = new TestGlb();
		shorts.mesh(shorts.floats("VEC3", 0, 0, 0, 1, 0, 0, 0, 1, 0), null, shorts.normalized(5123, "VEC2", 0, 0, 65535, 0, 0, 0),
				null);

		assertFloats(new float[] { 0, 0, 1, 0, 0, 0.2f }, read(bytes).uvs());
		assertFloats(new float[] { 0, 0, 1, 0, 0, 0 }, read(shorts).uvs());
	}

	@Test
	void rejectsIntegerTextureCoordinatesThatAreNotNormalized() throws Exception {
		TestGlb glb = new TestGlb();
		glb.mesh(glb.floats("VEC3", 0, 0, 0, 1, 0, 0, 0, 1, 0), null, glb.normalized(5121, "VEC2", 0, 0, 255, 0, 0, 255), null);
		glb.accessor(1).put("normalized", false);

		assertTrue(rejected(glb).getMessage().contains("texture coordinates"));
	}

	@Test
	void rejectsInfiniteOrNaNCoordinates() throws Exception {
		TestGlb infinite = new TestGlb();
		infinite.mesh(infinite.floats("VEC3", 0, 0, 0, Float.POSITIVE_INFINITY, 0, 0, 0, 1, 0), null, null, null);
		TestGlb nan = new TestGlb();
		nan.mesh(nan.floats("VEC3", 0, 0, 0, 1, 0, 0, 0, Float.NaN, 0), null, null, null);

		assertTrue(rejected(infinite).getMessage().contains("NaN or infinite"));
		assertTrue(rejected(nan).getMessage().contains("NaN or infinite"));
	}

	@Test
	void rejectsCoordinatesThatOverflowWhenScaled() throws Exception {
		// Each value is a valid float, but 1e30 * 1e30 doesn't fit in one.
		TestGlb glb = new TestGlb();
		glb.mesh(glb.floats("VEC3", 0, 0, 0, 1e30f, 0, 0, 0, 1, 0), null, null, null);
		glb.node(0).putArray("scale").add(1e30).add(1).add(1);

		assertTrue(rejected(glb).getMessage().contains("NaN or infinite"));
	}

	@Test
	void rejectsAFileThatIsNotGlb() throws Exception {
		File file = TestGlb.write(dir, "just some text".getBytes(StandardCharsets.UTF_8));

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> reader.read(file));

		assertTrue(error.getMessage().contains("model.glb"), error.getMessage());
		assertTrue(error.getMessage().contains("not a GLB file"), error.getMessage());
	}

	@Test
	void rejectsATruncatedFile() throws Exception {
		byte[] bytes = TestGlb.triangle().build();
		File file = TestGlb.write(dir, Arrays.copyOf(bytes, bytes.length - 10));

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> reader.read(file));

		assertTrue(error.getMessage().contains("truncated"), error.getMessage());
	}

	@Test
	void rejectsRequiredExtensionsSuchAsDraco() throws Exception {
		TestGlb glb = TestGlb.triangle();
		glb.json.putArray("extensionsRequired").add("KHR_draco_mesh_compression");

		assertTrue(rejected(glb).getMessage().contains("KHR_draco_mesh_compression"));
	}

	@Test
	void rejectsLinesAndPoints() throws Exception {
		TestGlb glb = TestGlb.triangle();
		glb.primitive(0).put("mode", 1);

		assertTrue(rejected(glb).getMessage().contains("only triangle meshes"));
	}

	@Test
	void rejectsANodeThatIsItsOwnChild() throws Exception {
		TestGlb glb = TestGlb.triangle();
		glb.node(0).putArray("children").add(0);

		assertTrue(rejected(glb).getMessage().contains("more than once"));
	}

	@Test
	void rejectsAnIndexPastTheLastVertex() throws Exception {
		TestGlb glb = new TestGlb();
		glb.mesh(glb.floats("VEC3", 0, 0, 0, 1, 0, 0, 0, 1, 0), null, null, glb.indices(5123, 0, 1, 3));

		assertTrue(rejected(glb).getMessage().contains("past the last vertex"));
	}

	@Test
	void rejectsDataThatRunsPastTheEndOfTheFile() throws Exception {
		TestGlb glb = TestGlb.triangle();
		glb.accessor(0).put("count", 1000);

		assertTrue(rejected(glb).getMessage().contains("past the end"));
	}

	@Test
	void rejectsPositionsThatAreNotFloats() throws Exception {
		TestGlb glb = TestGlb.triangle();
		glb.accessor(0).put("componentType", 5123);

		assertTrue(rejected(glb).getMessage().contains("floats"));
	}

	@Test
	void rejectsAModelWithNoTriangles() throws Exception {
		assertTrue(rejected(new TestGlb()).getMessage().contains("no triangles"));
	}
}
