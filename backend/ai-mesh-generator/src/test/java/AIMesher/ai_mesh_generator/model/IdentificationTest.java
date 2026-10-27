package AIMesher.ai_mesh_generator.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class IdentificationTest {

	@Test
	void confidenceBelowThresholdIsLow() {
		assertTrue(new Identification("chair", "furniture", List.of(), 0.3).isLowConfidence());
	}

	@Test
	void confidenceAtThresholdIsNotLow() {
		double threshold = Identification.LOW_CONFIDENCE_THRESHOLD;
		assertFalse(new Identification("chair", "furniture", List.of("four legs"), threshold).isLowConfidence());
	}
}
