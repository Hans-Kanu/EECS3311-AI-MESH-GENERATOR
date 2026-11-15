package AIMesher.ai_mesh_generator.exporter;

// A model as one list of triangles, with every node transform already applied (see GlbReader).
// positions and normals hold x, y, z per vertex; uvs hold u, v per vertex as stored in glTF (origin top left).
// normals and uvs are null when the model doesn't have them. indices hold three vertex numbers per triangle.
// texture is the base colour image (PNG or JPEG bytes) when the whole model uses one textured material, else null.
// The exporters ignore it; the 3D preview (F11) draws the model with it.
public record GlbModel(float[] positions, float[] normals, float[] uvs, int[] indices, byte[] texture) {

	public int vertexCount() {
		return positions.length / 3;
	}

	public int triangleCount() {
		return indices.length / 3;
	}

	public boolean hasNormals() {
		return normals != null;
	}

	public boolean hasUvs() {
		return uvs != null;
	}
}
