package AIMesher.ai_mesh_generator.exporter;

// A model as one list of triangles, with every node transform already applied (see GlbReader).
// positions and normals hold x, y, z per vertex; uvs hold u, v per vertex as stored in glTF (origin top left).
// normals and uvs are null when the model doesn't have them. indices hold three vertex numbers per triangle.
record GlbModel(float[] positions, float[] normals, float[] uvs, int[] indices) {

	int vertexCount() {
		return positions.length / 3;
	}

	int triangleCount() {
		return indices.length / 3;
	}

	boolean hasNormals() {
		return normals != null;
	}

	boolean hasUvs() {
		return uvs != null;
	}
}
