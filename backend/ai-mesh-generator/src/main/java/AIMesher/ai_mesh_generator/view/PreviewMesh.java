package AIMesher.ai_mesh_generator.view;

import AIMesher.ai_mesh_generator.exporter.GlbModel;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.shape.VertexFormat;

// The model's triangles in the form JavaFX draws them (F11). The model is centred on the origin and scaled to SIZE
// across, so every model fits the same camera. glTF has y up and z towards the viewer; JavaFX has y down and the
// camera looking along +z, so y and z are flipped. That is a 180 degree turn, so normals stay pointing outwards.
final class PreviewMesh {

	static final float SIZE = 200;

	final float[] points;
	final float[] normals;
	final float[] texCoords;
	// Three vertices per triangle, each as point, normal and texture coordinate index (VertexFormat.POINT_NORMAL_TEXCOORD).
	final int[] faces;
	final boolean textured;

	private PreviewMesh(float[] points, float[] normals, float[] texCoords, int[] faces, boolean textured) {
		this.points = points;
		this.normals = normals;
		this.texCoords = texCoords;
		this.faces = faces;
		this.textured = textured;
	}

	static PreviewMesh of(GlbModel model) {
		float[] p = model.positions();
		float[] min = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE };
		float[] max = { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
		for (int i = 0; i < p.length; i++) {
			min[i % 3] = Math.min(min[i % 3], p[i]);
			max[i % 3] = Math.max(max[i % 3], p[i]);
		}
		float extent = Math.max(max[0] - min[0], Math.max(max[1] - min[1], max[2] - min[2]));
		float scale = extent > 0 ? SIZE / extent : 1;
		float[] flip = { 1, -1, -1 };
		float[] points = new float[p.length];
		for (int i = 0; i < p.length; i++) {
			int axis = i % 3;
			points[i] = (p[i] - (min[axis] + max[axis]) / 2) * scale * flip[axis];
		}
		float[] normals;
		if (model.hasNormals()) {
			normals = model.normals().clone();
			for (int i = 0; i < normals.length; i++) {
				normals[i] *= flip[i % 3];
			}
		} else {
			normals = smoothNormals(points, model.indices());
		}
		boolean textured = model.hasUvs() && model.texture() != null;
		float[] texCoords = model.hasUvs() ? model.uvs() : new float[] { 0, 0 };
		int[] indices = model.indices();
		int[] faces = new int[indices.length * 3];
		for (int i = 0; i < indices.length; i++) {
			faces[i * 3] = indices[i];
			faces[i * 3 + 1] = indices[i];
			faces[i * 3 + 2] = model.hasUvs() ? indices[i] : 0;
		}
		return new PreviewMesh(points, normals, texCoords, faces, textured);
	}

	// For models without normals: each vertex gets the average direction of the triangles around it.
	private static float[] smoothNormals(float[] points, int[] indices) {
		float[] normals = new float[points.length];
		for (int t = 0; t < indices.length; t += 3) {
			int a = indices[t] * 3;
			int b = indices[t + 1] * 3;
			int c = indices[t + 2] * 3;
			float ux = points[b] - points[a], uy = points[b + 1] - points[a + 1], uz = points[b + 2] - points[a + 2];
			float vx = points[c] - points[a], vy = points[c + 1] - points[a + 1], vz = points[c + 2] - points[a + 2];
			float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			for (int v : new int[] { a, b, c }) {
				normals[v] += nx;
				normals[v + 1] += ny;
				normals[v + 2] += nz;
			}
		}
		for (int i = 0; i < normals.length; i += 3) {
			float length = (float) Math.sqrt(normals[i] * normals[i] + normals[i + 1] * normals[i + 1] + normals[i + 2] * normals[i + 2]);
			if (length > 0) {
				normals[i] /= length;
				normals[i + 1] /= length;
				normals[i + 2] /= length;
			} else {
				normals[i + 2] = -1;
			}
		}
		return normals;
	}

	int triangleCount() {
		return faces.length / 9;
	}

	TriangleMesh toTriangleMesh() {
		TriangleMesh mesh = new TriangleMesh(VertexFormat.POINT_NORMAL_TEXCOORD);
		mesh.getPoints().setAll(points);
		mesh.getNormals().setAll(normals);
		mesh.getTexCoords().setAll(texCoords);
		mesh.getFaces().setAll(faces);
		return mesh;
	}
}
