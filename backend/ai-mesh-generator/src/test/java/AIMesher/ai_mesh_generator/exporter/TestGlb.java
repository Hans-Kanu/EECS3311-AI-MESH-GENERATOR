package AIMesher.ai_mesh_generator.exporter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// Builds small GLB files in code for the exporter tests. Tests can edit json before build() to make broken files.
final class TestGlb {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	final ObjectNode json = MAPPER.createObjectNode();
	private final ArrayNode bufferViews = json.putArray("bufferViews");
	private final ArrayNode accessors = json.putArray("accessors");
	private final ArrayNode meshes = json.putArray("meshes");
	private final ArrayNode nodes = json.putArray("nodes");
	private final ArrayNode sceneNodes;
	private final ByteArrayOutputStream bin = new ByteArrayOutputStream();

	TestGlb() {
		json.putObject("asset").put("version", "2.0");
		json.put("scene", 0);
		sceneNodes = json.putArray("scenes").addObject().putArray("nodes");
	}

	// The triangle (0,0,0) (1,0,0) (0,1,0) facing +Z, with normals, texture coordinates and 16-bit indices.
	static TestGlb triangle() {
		TestGlb glb = new TestGlb();
		glb.mesh(glb.floats("VEC3", 0, 0, 0, 1, 0, 0, 0, 1, 0),
				glb.floats("VEC3", 0, 0, 1, 0, 0, 1, 0, 0, 1),
				glb.floats("VEC2", 0, 0, 1, 0, 0, 1),
				glb.indices(5123, 0, 1, 2));
		return glb;
	}

	int floats(String type, float... values) {
		ByteBuffer data = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (float value : values) {
			data.putFloat(value);
		}
		return accessor(data.array(), 5126, type, values.length / (type.equals("VEC3") ? 3 : 2));
	}

	int indices(int componentType, int... values) {
		int size = componentType == 5121 ? 1 : componentType == 5123 ? 2 : 4;
		ByteBuffer data = ByteBuffer.allocate(values.length * size).order(ByteOrder.LITTLE_ENDIAN);
		for (int value : values) {
			switch (size) {
				case 1 -> data.put((byte) value);
				case 2 -> data.putShort((short) value);
				default -> data.putInt(value);
			}
		}
		return accessor(data.array(), componentType, "SCALAR", values.length);
	}

	// Integer vertex data marked normalized, e.g. texture coordinates stored as unsigned bytes (5121) or shorts (5123).
	int normalized(int componentType, String type, int... values) {
		int size = componentType == 5121 ? 1 : 2;
		ByteBuffer data = ByteBuffer.allocate(values.length * size).order(ByteOrder.LITTLE_ENDIAN);
		for (int value : values) {
			if (size == 1) {
				data.put((byte) value);
			} else {
				data.putShort((short) value);
			}
		}
		int index = accessor(data.array(), componentType, type, values.length / (type.equals("VEC3") ? 3 : 2));
		accessor(index).put("normalized", true);
		return index;
	}

	// Positions and normals packed together in one buffer view (x y z nx ny nz per vertex); returns both accessors.
	int[] interleaved(float[] positions, float[] normals) {
		int count = positions.length / 3;
		ByteBuffer data = ByteBuffer.allocate(count * 24).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < count; i++) {
			data.putFloat(positions[i * 3]).putFloat(positions[i * 3 + 1]).putFloat(positions[i * 3 + 2]);
			data.putFloat(normals[i * 3]).putFloat(normals[i * 3 + 1]).putFloat(normals[i * 3 + 2]);
		}
		int view = view(data.array());
		((ObjectNode) bufferViews.get(view)).put("byteStride", 24);
		accessors.addObject().put("bufferView", view).put("componentType", 5126).put("count", count).put("type", "VEC3");
		accessors.addObject().put("bufferView", view).put("byteOffset", 12).put("componentType", 5126).put("count", count)
				.put("type", "VEC3");
		return new int[] { accessors.size() - 2, accessors.size() - 1 };
	}

	// Adds a mesh with one primitive and a root node showing it. normal, uv and indices may be null.
	void mesh(int position, Integer normal, Integer uv, Integer indices) {
		ObjectNode primitive = meshes.addObject().putArray("primitives").addObject();
		ObjectNode attributes = primitive.putObject("attributes").put("POSITION", position);
		if (normal != null) {
			attributes.put("NORMAL", normal);
		}
		if (uv != null) {
			attributes.put("TEXCOORD_0", uv);
		}
		if (indices != null) {
			primitive.put("indices", indices);
		}
		nodes.addObject().put("mesh", meshes.size() - 1);
		sceneNodes.add(nodes.size() - 1);
	}

	ObjectNode node(int index) {
		return (ObjectNode) nodes.get(index);
	}

	// A node with no mesh, not yet part of the scene.
	ObjectNode addNode() {
		return nodes.addObject();
	}

	ObjectNode primitive(int mesh) {
		return (ObjectNode) meshes.get(mesh).path("primitives").get(0);
	}

	ObjectNode accessor(int index) {
		return (ObjectNode) accessors.get(index);
	}

	ArrayNode sceneNodes() {
		return sceneNodes;
	}

	byte[] build() {
		json.putArray("buffers").addObject().put("byteLength", bin.size());
		byte[] jsonBytes = pad(MAPPER.writeValueAsString(json).getBytes(StandardCharsets.UTF_8), (byte) ' ');
		byte[] binBytes = pad(bin.toByteArray(), (byte) 0);
		int length = 12 + 8 + jsonBytes.length + 8 + binBytes.length;
		ByteBuffer glb = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
		glb.putInt(0x46546C67).putInt(2).putInt(length);
		glb.putInt(jsonBytes.length).putInt(0x4E4F534A).put(jsonBytes);
		glb.putInt(binBytes.length).putInt(0x004E4942).put(binBytes);
		return glb.array();
	}

	File write(Path dir) throws IOException {
		return write(dir, build());
	}

	static File write(Path dir, byte[] bytes) throws IOException {
		Path file = dir.resolve("model.glb");
		Files.write(file, bytes);
		return file.toFile();
	}

	private int accessor(byte[] data, int componentType, String type, int count) {
		int view = view(data);
		accessors.addObject().put("bufferView", view).put("componentType", componentType).put("count", count).put("type", type);
		return accessors.size() - 1;
	}

	// glTF wants each buffer view to start on a 4-byte boundary.
	private int view(byte[] data) {
		while (bin.size() % 4 != 0) {
			bin.write(0);
		}
		bufferViews.addObject().put("buffer", 0).put("byteOffset", bin.size()).put("byteLength", data.length);
		bin.writeBytes(data);
		return bufferViews.size() - 1;
	}

	private static byte[] pad(byte[] bytes, byte filler) {
		byte[] padded = new byte[(bytes.length + 3) / 4 * 4];
		System.arraycopy(bytes, 0, padded, 0, bytes.length);
		for (int i = bytes.length; i < padded.length; i++) {
			padded[i] = filler;
		}
		return padded;
	}
}
