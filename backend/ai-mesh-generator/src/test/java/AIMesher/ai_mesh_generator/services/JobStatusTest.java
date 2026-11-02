package AIMesher.ai_mesh_generator.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JobStatusTest {

	@Test
	void onlyDoneAndFailedAreFinished() {
		assertFalse(JobStatus.QUEUED.isFinished());
		assertFalse(JobStatus.RUNNING.isFinished());
		assertTrue(JobStatus.DONE.isFinished());
		assertTrue(JobStatus.FAILED.isFinished());
	}
}
