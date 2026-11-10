package AIMesher.ai_mesh_generator.agent;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import AIMesher.ai_mesh_generator.model.Angle;
import AIMesher.ai_mesh_generator.services.JobStatus;
import AIMesher.ai_mesh_generator.services.MeshGenerator;

// A ModelAgent on fake services that write real files into a folder, for controller and view tests.
public final class TestAgent {

	public final ModelAgent agent;
	// The views the last buildMesh() sent, and the model file it returned.
	public Map<Angle, File> meshViews;
	public final File model;

	public TestAgent(Path dir) {
		Fakes.Generator generator = new Fakes.Generator();
		generator.outputDir = dir;
		model = dir.resolve("model.glb").toFile();
		MeshGenerator mesh = new MeshGenerator() {
			@Override
			public String submitJob(Map<Angle, File> views) {
				meshViews = views;
				return "job";
			}

			@Override
			public JobStatus getStatus(String jobId) {
				return JobStatus.DONE;
			}

			@Override
			public File download(String jobId) {
				try {
					return Files.write(model.toPath(), new byte[] { 7 }).toFile();
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
		};
		agent = new ModelAgent(new Fakes.Vision(), generator, null, mesh, delay -> {
		});
	}
}
