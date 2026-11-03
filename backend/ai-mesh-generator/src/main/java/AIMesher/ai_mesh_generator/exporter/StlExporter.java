package AIMesher.ai_mesh_generator.exporter;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// Writes the model as a binary STL file for 3D printing: an 80-byte header, the triangle count, then for each
// triangle its normal, its three corners and a 2-byte attribute field. STL has no colours or textures.
public class StlExporter implements ModelExporter {

	static final int HEADER_BYTES = 80;
	static final int TRIANGLE_BYTES = 50;

	// Some programs read a file whose header starts with "solid" as text STL, so this header must not.
	private static final byte[] HEADER = "Binary STL exported by AI Mesh Generator".getBytes(StandardCharsets.US_ASCII);

	private final GlbReader reader;

	StlExporter(GlbReader reader) {
		this.reader = reader;
	}

	@Override
	public void export(File modelFile, Path path) {
		GlbModel model = reader.read(modelFile);
		int triangles = model.triangleCount();
		if (triangles > (Integer.MAX_VALUE - HEADER_BYTES - 4) / TRIANGLE_BYTES) {
			throw new IllegalArgumentException("Can't convert " + modelFile.getName() + ": it is too large for an STL file.");
		}
		// Built in memory first, so a model that can't be converted leaves an existing file untouched.
		ByteBuffer out = ByteBuffer.allocate(HEADER_BYTES + 4 + triangles * TRIANGLE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
		out.put(HEADER).position(HEADER_BYTES);
		out.putInt(triangles);
		float[] p = model.positions();
		int[] indices = model.indices();
		for (int t = 0; t < triangles; t++) {
			float[][] corners = new float[3][];
			for (int c = 0; c < 3; c++) {
				corners[c] = zUp(p, indices[t * 3 + c]);
			}
			// The normal is worked out from the corners rather than copied, since STL needs one per triangle.
			float[] normal = normal(corners[0], corners[1], corners[2]);
			out.putFloat(normal[0]).putFloat(normal[1]).putFloat(normal[2]);
			for (float[] corner : corners) {
				out.putFloat(corner[0]).putFloat(corner[1]).putFloat(corner[2]);
			}
			out.putShort((short) 0);
		}
		try {
			Files.write(path, out.array());
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write " + path, e);
		}
	}

	// glTF is Y-up; slicers treat Z as up, so without this the model would print lying on its side.
	// (x, y, z) -> (x, -z, y) is a rotation, so the triangles keep their winding.
	private static float[] zUp(float[] positions, int vertex) {
		return new float[] { positions[vertex * 3], -positions[vertex * 3 + 2], positions[vertex * 3 + 1] };
	}

	// (b - a) x (c - a), normalised. A zero-area triangle gets (0, 0, 0), which STL readers accept.
	private static float[] normal(float[] a, float[] b, float[] c) {
		double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
		double vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
		double x = uy * vz - uz * vy, y = uz * vx - ux * vz, z = ux * vy - uy * vx;
		double length = Math.sqrt(x * x + y * y + z * z);
		if (length == 0) {
			return new float[3];
		}
		return new float[] { (float) (x / length), (float) (y / length), (float) (z / length) };
	}
}
