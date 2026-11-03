package AIMesher.ai_mesh_generator.exporter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

// Writes the model as a Wavefront OBJ text file: positions (v), texture coordinates (vt), normals (vn) and
// triangles (f). Materials and textures (.mtl) are not written yet.
public class ObjExporter implements ModelExporter {

	private final GlbReader reader;

	ObjExporter(GlbReader reader) {
		this.reader = reader;
	}

	@Override
	public void export(File modelFile, Path path) {
		// Read before opening the output, so a model that can't be converted leaves an existing file untouched.
		GlbModel model = reader.read(modelFile);
		// Always a '.' decimal point: with the default locale, a German or French system would write "0,5".
		DecimalFormat number = new DecimalFormat("0.######", DecimalFormatSymbols.getInstance(Locale.ROOT));
		try (BufferedWriter out = Files.newBufferedWriter(path)) {
			out.write("# Exported by AI Mesh Generator\n");
			float[] p = model.positions();
			for (int i = 0; i < p.length; i += 3) {
				out.write("v " + number.format(p[i]) + " " + number.format(p[i + 1]) + " " + number.format(p[i + 2]) + "\n");
			}
			if (model.hasUvs()) {
				float[] uv = model.uvs();
				for (int i = 0; i < uv.length; i += 2) {
					// glTF puts the texture origin at the top left, OBJ at the bottom left.
					out.write("vt " + number.format(uv[i]) + " " + number.format(1 - uv[i + 1]) + "\n");
				}
			}
			if (model.hasNormals()) {
				float[] n = model.normals();
				for (int i = 0; i < n.length; i += 3) {
					out.write("vn " + number.format(n[i]) + " " + number.format(n[i + 1]) + " " + number.format(n[i + 2]) + "\n");
				}
			}
			int[] indices = model.indices();
			for (int i = 0; i < indices.length; i += 3) {
				out.write("f " + corner(model, indices[i]) + " " + corner(model, indices[i + 1]) + " "
						+ corner(model, indices[i + 2]) + "\n");
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write " + path, e);
		}
	}

	// OBJ numbers vertices from 1. Every vertex has its own position, uv and normal, so one number serves all three.
	private static String corner(GlbModel model, int index) {
		String n = Integer.toString(index + 1);
		if (model.hasUvs() && model.hasNormals()) {
			return n + "/" + n + "/" + n;
		}
		if (model.hasUvs()) {
			return n + "/" + n;
		}
		if (model.hasNormals()) {
			return n + "//" + n;
		}
		return n;
	}
}
