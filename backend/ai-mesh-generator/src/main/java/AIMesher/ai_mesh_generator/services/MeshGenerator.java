package AIMesher.ai_mesh_generator.services;

import java.io.File;
import java.util.Map;

import AIMesher.ai_mesh_generator.model.Angle;

// Adapter target: multi-view 3D mesh generation (F08). Building a mesh takes minutes, so it runs as a job:
// submit it, poll getStatus() until the job is finished, then download the model file.
public interface MeshGenerator {

	// Returns the job id.
	String submitJob(Map<Angle, File> views);

	JobStatus getStatus(String jobId);

	File download(String jobId);
}
