package AIMesher.ai_mesh_generator.view;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import AIMesher.ai_mesh_generator.exporter.GlbModel;

class PreviewMeshTest {

	// A triangle 2 wide and 1 high, facing the viewer (+Z in glTF).
	private static final float[] POSITIONS = { 0, 0, 0, 2, 0, 0, 0, 1, 0 };
	private static final float[] UVS = { 0, 0, 1, 0, 0, 1 };
	private static final int[] INDICES = { 0, 1, 2 };

	@Test
	void theModelIsCentredScaledAndTurnedToJavaFxAxes() {
		PreviewMesh mesh = PreviewMesh.of(new GlbModel(POSITIONS, new float[] { 0, 0, 1, 0, 0, 1, 0, 0, 1 }, UVS, INDICES, null));

		// Centre (1, 0.5, 0), widest side 2 -> 200 units, y and z flipped.
		assertArrayEquals(new float[] { -100, 50, 0, 100, 50, 0, -100, -50, 0 }, mesh.points, 1e-4f);
		// Still facing the camera, which looks along +z from -z.
		assertArrayEquals(new float[] { 0, 0, -1, 0, 0, -1, 0, 0, -1 }, mesh.normals, 1e-6f);
		assertArrayEquals(UVS, mesh.texCoords);
		assertArrayEquals(new int[] { 0, 0, 0, 1, 1, 1, 2, 2, 2 }, mesh.faces);
		assertEquals(1, mesh.triangleCount());
		assertFalse(mesh.textured);
	}

	@Test
	void missingNormalsAreWorkedOutFacingTheSameWayAsGivenOnes() {
		PreviewMesh mesh = PreviewMesh.of(new GlbModel(POSITIONS, null, null, INDICES, null));

		for (int i = 0; i < 9; i += 3) {
			assertEquals(0, mesh.normals[i], 1e-6);
			assertEquals(0, mesh.normals[i + 1], 1e-6);
			assertEquals(-1, mesh.normals[i + 2], 1e-6);
		}
		// Without texture coordinates every vertex uses the single placeholder one.
		assertArrayEquals(new int[] { 0, 0, 0, 1, 1, 0, 2, 2, 0 }, mesh.faces);
	}

	@Test
	void itIsTexturedOnlyWithBothAnImageAndTextureCoordinates() {
		byte[] image = { 1 };

		assertTrue(PreviewMesh.of(new GlbModel(POSITIONS, null, UVS, INDICES, image)).textured);
		assertFalse(PreviewMesh.of(new GlbModel(POSITIONS, null, null, INDICES, image)).textured);
	}
}
