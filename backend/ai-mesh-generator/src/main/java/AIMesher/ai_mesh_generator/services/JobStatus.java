package AIMesher.ai_mesh_generator.services;

// The state of a MeshGenerator job.
public enum JobStatus {
	QUEUED,
	RUNNING,
	DONE,
	FAILED;

	// True once polling can stop, whether the job succeeded or not.
	public boolean isFinished() {
		return this == DONE || this == FAILED;
	}
}
