package AIMesher.ai_mesh_generator.exporter;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Reads a GLB file (binary glTF 2.0, what the mesh generator returns) into one list of triangles for the exporters.
// Anything it can't convert faithfully is rejected with IllegalArgumentException instead of being guessed at.
class GlbReader {

	private static final int MAGIC = 0x46546C67; // "glTF"
	private static final int JSON_CHUNK = 0x4E4F534A;
	private static final int BIN_CHUNK = 0x004E4942;
	private static final int FLOAT = 5126;
	private static final int UNSIGNED_BYTE = 5121;
	private static final int UNSIGNED_SHORT = 5123;
	private static final int UNSIGNED_INT = 5125;
	private static final int TRIANGLES = 4;
	private static final double[] IDENTITY = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };

	private final JsonMapper mapper = JsonMapper.builder().build();

	GlbModel read(File file) {
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(file.toPath());
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + file.getName(), e);
		}
		try {
			return parse(bytes).model();
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Can't convert " + file.getName() + ": " + e.getMessage(), e);
		}
	}

	private Document parse(byte[] bytes) {
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		if (bytes.length < 12 || buffer.getInt(0) != MAGIC) {
			throw new IllegalArgumentException("it is not a GLB file.");
		}
		if (buffer.getInt(4) != 2) {
			throw new IllegalArgumentException("only glTF version 2 is supported.");
		}
		long declaredLength = Integer.toUnsignedLong(buffer.getInt(8));
		if (declaredLength > bytes.length) {
			throw new IllegalArgumentException("the file is truncated.");
		}
		JsonNode json = null;
		ByteBuffer bin = null;
		// Each pass moves offset forward by at least the 8-byte chunk header, so the loop always ends.
		long offset = 12;
		while (offset + 8 <= declaredLength) {
			long length = Integer.toUnsignedLong(buffer.getInt((int) offset));
			int type = buffer.getInt((int) offset + 4);
			long start = offset + 8;
			if (start + length > declaredLength) {
				throw new IllegalArgumentException("the file is truncated.");
			}
			if (json == null) {
				if (type != JSON_CHUNK) {
					throw new IllegalArgumentException("the first chunk is not JSON.");
				}
				json = readJson(bytes, (int) start, (int) length);
			} else if (type == BIN_CHUNK && bin == null) {
				// slice() resets the byte order, so it is set afterwards.
				bin = ByteBuffer.wrap(bytes, (int) start, (int) length).slice().order(ByteOrder.LITTLE_ENDIAN);
			}
			// Other chunk types are skipped, as the glTF spec says readers should.
			offset = start + length;
		}
		if (json == null) {
			throw new IllegalArgumentException("it has no JSON chunk.");
		}
		return new Document(json, bin);
	}

	private JsonNode readJson(byte[] bytes, int start, int length) {
		try {
			return mapper.readTree(new String(bytes, start, length, StandardCharsets.UTF_8));
		} catch (JacksonException e) {
			throw new IllegalArgumentException("its JSON is invalid.", e);
		}
	}

	// One part of the model, already transformed. normals and uvs are null when this part doesn't have them.
	private record Part(float[] positions, float[] normals, float[] uvs, int[] indices) {
	}

	// Where an accessor's elements start in the BIN chunk and how far apart they are.
	private record Region(int start, int stride) {
	}

	private static final class Document {

		private final JsonNode json;
		private final ByteBuffer bin;
		private final List<Part> parts = new ArrayList<>();

		Document(JsonNode json, ByteBuffer bin) {
			this.json = json;
			this.bin = bin;
		}

		GlbModel model() {
			JsonNode required = json.path("extensionsRequired");
			if (required.size() > 0) {
				List<String> names = IntStream.range(0, required.size()).mapToObj(i -> required.get(i).asString("?")).toList();
				throw new IllegalArgumentException("it needs " + String.join(", ", names) + ", which isn't supported.");
			}
			// Each node may appear only once in the tree. Checking that also stops a node listed as its
			// own child (or descendant) from being visited forever.
			Set<Integer> visited = new HashSet<>();
			for (int root : roots()) {
				visit(root, IDENTITY, visited);
			}
			return merge();
		}

		private List<Integer> roots() {
			JsonNode scenes = json.path("scenes");
			if (scenes.size() > 0) {
				int scene = json.path("scene").asInt(0);
				return ints(element("scenes", scene).path("nodes"));
			}
			// No scene: every node that isn't another node's child is a root.
			Set<Integer> children = new HashSet<>();
			JsonNode nodes = json.path("nodes");
			for (int i = 0; i < nodes.size(); i++) {
				children.addAll(ints(nodes.get(i).path("children")));
			}
			return IntStream.range(0, nodes.size()).filter(i -> !children.contains(i)).boxed().toList();
		}

		private void visit(int index, double[] parentMatrix, Set<Integer> visited) {
			JsonNode node = element("nodes", index);
			if (!visited.add(index)) {
				throw new IllegalArgumentException("node " + index + " appears more than once in the node tree.");
			}
			double[] matrix = multiply(parentMatrix, localMatrix(node));
			if (node.has("mesh")) {
				addMesh(element("meshes", node.path("mesh").asInt(-1)), matrix);
			}
			for (int child : ints(node.path("children"))) {
				visit(child, matrix, visited);
			}
		}

		private void addMesh(JsonNode mesh, double[] matrix) {
			JsonNode primitives = mesh.path("primitives");
			for (int i = 0; i < primitives.size(); i++) {
				JsonNode primitive = primitives.get(i);
				if (primitive.path("mode").asInt(TRIANGLES) != TRIANGLES) {
					throw new IllegalArgumentException("it contains lines or points; only triangle meshes can be converted.");
				}
				JsonNode attributes = primitive.path("attributes");
				if (!attributes.has("POSITION")) {
					throw new IllegalArgumentException("a mesh has no vertex positions.");
				}
				float[] positions = readFloats(attributes.path("POSITION").asInt(-1), "VEC3", false);
				int vertexCount = positions.length / 3;
				float[] normals = attributes.has("NORMAL") ? readFloats(attributes.path("NORMAL").asInt(-1), "VEC3", false) : null;
				// glTF also allows texture coordinates as normalized unsigned bytes or shorts.
				float[] uvs = attributes.has("TEXCOORD_0") ? readFloats(attributes.path("TEXCOORD_0").asInt(-1), "VEC2", true) : null;
				if ((normals != null && normals.length != positions.length) || (uvs != null && uvs.length != vertexCount * 2)) {
					throw new IllegalArgumentException("a mesh has a different number of normals or texture coordinates than vertices.");
				}
				int[] indices = primitive.has("indices")
						? readIndices(primitive.path("indices").asInt(-1), vertexCount)
						: IntStream.range(0, vertexCount).toArray();
				if (indices.length % 3 != 0) {
					throw new IllegalArgumentException("a mesh's index count isn't a multiple of 3.");
				}
				parts.add(transform(new Part(positions, normals, uvs, indices), matrix));
			}
		}

		private float[] readFloats(int accessorIndex, String type, boolean allowNormalized) {
			JsonNode accessor = element("accessors", accessorIndex);
			int components = type.equals("VEC3") ? 3 : 2;
			if (accessor.has("sparse")) {
				throw new IllegalArgumentException("sparse accessors aren't supported.");
			}
			int componentType = accessor.path("componentType").asInt(-1);
			boolean normalized = allowNormalized && accessor.path("normalized").asBoolean(false)
					&& (componentType == UNSIGNED_BYTE || componentType == UNSIGNED_SHORT);
			if ((componentType != FLOAT && !normalized) || !type.equals(accessor.path("type").asString(""))) {
				throw new IllegalArgumentException(allowNormalized
						? "texture coordinates must be floats or normalized unsigned bytes or shorts."
						: "vertex data must be stored as " + type + " floats.");
			}
			int size = componentType == UNSIGNED_BYTE ? 1 : componentType == UNSIGNED_SHORT ? 2 : 4;
			int count = count(accessor);
			Region region = region(accessor, components * size, count);
			float[] values = new float[count * components];
			for (int i = 0; i < count; i++) {
				for (int c = 0; c < components; c++) {
					int at = region.start() + i * region.stride() + c * size;
					values[i * components + c] = switch (size) {
						case 1 -> (bin.get(at) & 0xFF) / 255f;
						case 2 -> (bin.getShort(at) & 0xFFFF) / 65535f;
						default -> bin.getFloat(at);
					};
				}
			}
			return values;
		}

		private int[] readIndices(int accessorIndex, int vertexCount) {
			JsonNode accessor = element("accessors", accessorIndex);
			int size = switch (accessor.path("componentType").asInt(-1)) {
				case UNSIGNED_BYTE -> 1;
				case UNSIGNED_SHORT -> 2;
				case UNSIGNED_INT -> 4;
				default -> throw new IllegalArgumentException("indices must be unsigned bytes, shorts or ints.");
			};
			if (accessor.has("sparse") || !accessor.path("type").asString("").equals("SCALAR")) {
				throw new IllegalArgumentException("indices must be a plain SCALAR accessor.");
			}
			int count = count(accessor);
			Region region = region(accessor, size, count);
			int[] indices = new int[count];
			for (int i = 0; i < count; i++) {
				int at = region.start() + i * region.stride();
				long value = switch (size) {
					case 1 -> bin.get(at) & 0xFF;
					case 2 -> bin.getShort(at) & 0xFFFF;
					default -> Integer.toUnsignedLong(bin.getInt(at));
				};
				// Otherwise the exported file would refer to vertices that don't exist.
				if (value >= vertexCount) {
					throw new IllegalArgumentException("an index points past the last vertex.");
				}
				indices[i] = (int) value;
			}
			return indices;
		}

		private static int count(JsonNode accessor) {
			int count = accessor.path("count").asInt(-1);
			if (count < 0) {
				throw new IllegalArgumentException("an accessor has no element count.");
			}
			return count;
		}

		// Checks the accessor's elements all lie inside its buffer view, and the view inside the BIN chunk,
		// so a bad offset or count is reported instead of reading past the end of the data.
		// Uses long arithmetic, so huge values in the JSON can't overflow the check.
		private Region region(JsonNode accessor, int elementSize, int count) {
			if (!accessor.has("bufferView")) {
				throw new IllegalArgumentException("accessors without data aren't supported.");
			}
			JsonNode view = element("bufferViews", accessor.path("bufferView").asInt(-1));
			if (bin == null || view.path("buffer").asInt(-1) != 0 || json.path("buffers").path(0).has("uri")) {
				throw new IllegalArgumentException("its data is stored outside the file.");
			}
			long viewStart = view.path("byteOffset").asLong(0);
			long viewEnd = viewStart + view.path("byteLength").asLong(-1);
			int stride = view.path("byteStride").asInt(elementSize);
			long start = viewStart + accessor.path("byteOffset").asLong(0);
			if (viewStart < 0 || viewEnd < viewStart || viewEnd > bin.limit() || stride < elementSize || start < viewStart
					|| (count > 0 && start + (long) stride * (count - 1) + elementSize > viewEnd)) {
				throw new IllegalArgumentException("its data runs past the end of the file.");
			}
			return new Region((int) start, stride);
		}

		private JsonNode element(String array, int index) {
			JsonNode element = json.path(array).path(index);
			if (index < 0 || element.isMissingNode()) {
				throw new IllegalArgumentException(array + " " + index + " doesn't exist.");
			}
			return element;
		}

		private static List<Integer> ints(JsonNode array) {
			return IntStream.range(0, array.size()).map(i -> array.get(i).asInt(-1)).boxed().toList();
		}

		// Combines every part into one model. Normals and texture coordinates are kept only if every part has them.
		private GlbModel merge() {
			int vertices = parts.stream().mapToInt(part -> part.positions().length / 3).sum();
			int indexCount = parts.stream().mapToInt(part -> part.indices().length).sum();
			if (indexCount == 0) {
				throw new IllegalArgumentException("it contains no triangles.");
			}
			boolean allNormals = parts.stream().allMatch(part -> part.normals() != null);
			boolean allUvs = parts.stream().allMatch(part -> part.uvs() != null);
			float[] positions = new float[vertices * 3];
			float[] normals = allNormals ? new float[vertices * 3] : null;
			float[] uvs = allUvs ? new float[vertices * 2] : null;
			int[] indices = new int[indexCount];
			int vertexOffset = 0;
			int indexOffset = 0;
			for (Part part : parts) {
				int count = part.positions().length / 3;
				System.arraycopy(part.positions(), 0, positions, vertexOffset * 3, count * 3);
				if (allNormals) {
					System.arraycopy(part.normals(), 0, normals, vertexOffset * 3, count * 3);
				}
				if (allUvs) {
					System.arraycopy(part.uvs(), 0, uvs, vertexOffset * 2, count * 2);
				}
				for (int index : part.indices()) {
					indices[indexOffset++] = index + vertexOffset;
				}
				vertexOffset += count;
			}
			return new GlbModel(positions, normals, uvs, indices);
		}
	}

	// Matrices are 4x4, column-major like glTF: element (row, col) is at col * 4 + row.

	private static double[] localMatrix(JsonNode node) {
		JsonNode matrix = node.path("matrix");
		if (matrix.size() == 16) {
			double[] values = new double[16];
			for (int i = 0; i < 16; i++) {
				values[i] = matrix.get(i).asDouble(0);
			}
			return values;
		}
		double[] t = vector(node.path("translation"), 0, 0, 0);
		double[] q = vector(node.path("rotation"), 0, 0, 0, 1);
		double[] s = vector(node.path("scale"), 1, 1, 1);
		double x = q[0], y = q[1], z = q[2], w = q[3];
		// translation * rotation * scale
		return new double[] {
				(1 - 2 * (y * y + z * z)) * s[0], 2 * (x * y + z * w) * s[0], 2 * (x * z - y * w) * s[0], 0,
				2 * (x * y - z * w) * s[1], (1 - 2 * (x * x + z * z)) * s[1], 2 * (y * z + x * w) * s[1], 0,
				2 * (x * z + y * w) * s[2], 2 * (y * z - x * w) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0,
				t[0], t[1], t[2], 1 };
	}

	private static double[] vector(JsonNode array, double... defaults) {
		if (array.size() != defaults.length) {
			return defaults;
		}
		double[] values = new double[defaults.length];
		for (int i = 0; i < values.length; i++) {
			values[i] = array.get(i).asDouble(defaults[i]);
		}
		return values;
	}

	private static double[] multiply(double[] a, double[] b) {
		double[] result = new double[16];
		for (int col = 0; col < 4; col++) {
			for (int row = 0; row < 4; row++) {
				double sum = 0;
				for (int k = 0; k < 4; k++) {
					sum += a[k * 4 + row] * b[col * 4 + k];
				}
				result[col * 4 + row] = sum;
			}
		}
		return result;
	}

	private static Part transform(Part part, double[] m) {
		float[] positions = new float[part.positions().length];
		for (int i = 0; i < positions.length; i += 3) {
			double x = part.positions()[i], y = part.positions()[i + 1], z = part.positions()[i + 2];
			positions[i] = (float) (m[0] * x + m[4] * y + m[8] * z + m[12]);
			positions[i + 1] = (float) (m[1] * x + m[5] * y + m[9] * z + m[13]);
			positions[i + 2] = (float) (m[2] * x + m[6] * y + m[10] * z + m[14]);
		}
		// Normals use the inverse transpose of the 3x3 part, so non-uniform scaling keeps them perpendicular.
		// The cofactor matrix equals determinant * inverse transpose, which is the same direction once normalised.
		double a00 = m[0], a01 = m[4], a02 = m[8];
		double a10 = m[1], a11 = m[5], a12 = m[9];
		double a20 = m[2], a21 = m[6], a22 = m[10];
		double c00 = a11 * a22 - a12 * a21, c01 = a12 * a20 - a10 * a22, c02 = a10 * a21 - a11 * a20;
		double c10 = a02 * a21 - a01 * a22, c11 = a00 * a22 - a02 * a20, c12 = a01 * a20 - a00 * a21;
		double c20 = a01 * a12 - a02 * a11, c21 = a02 * a10 - a00 * a12, c22 = a00 * a11 - a01 * a10;
		double determinant = a00 * c00 + a01 * c01 + a02 * c02;
		double sign = determinant < 0 ? -1 : 1;
		float[] normals = null;
		if (part.normals() != null) {
			normals = new float[part.normals().length];
			for (int i = 0; i < normals.length; i += 3) {
				double x = part.normals()[i], y = part.normals()[i + 1], z = part.normals()[i + 2];
				double nx = sign * (c00 * x + c01 * y + c02 * z);
				double ny = sign * (c10 * x + c11 * y + c12 * z);
				double nz = sign * (c20 * x + c21 * y + c22 * z);
				double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (length > 0) {
					normals[i] = (float) (nx / length);
					normals[i + 1] = (float) (ny / length);
					normals[i + 2] = (float) (nz / length);
				}
			}
		}
		// Checked after transforming: a huge scale can also overflow a valid coordinate to infinity. Either way the
		// file would be unusable (OBJ would contain "∞", STL raw NaN values).
		requireFinite(positions);
		requireFinite(normals);
		requireFinite(part.uvs());
		// A mirroring transform turns triangles inside out, so the glTF spec says to reverse their winding.
		int[] indices = part.indices().clone();
		if (determinant < 0) {
			for (int i = 0; i < indices.length; i += 3) {
				int swap = indices[i + 1];
				indices[i + 1] = indices[i + 2];
				indices[i + 2] = swap;
			}
		}
		return new Part(positions, normals, part.uvs(), indices);
	}

	private static void requireFinite(float[] values) {
		if (values == null) {
			return;
		}
		for (float value : values) {
			if (!Float.isFinite(value)) {
				throw new IllegalArgumentException("it contains invalid (NaN or infinite) coordinates.");
			}
		}
	}
}
