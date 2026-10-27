package AIMesher.ai_mesh_generator.model;

import java.util.List;

// The vision model's answer to "what is this object?" (F03).
public record Identification(String name, String category, List<String> keyFeatures, double confidence) {

	// Below this, the agent asks the user for a description instead of searching online.
	static final double LOW_CONFIDENCE_THRESHOLD = 0.6;

	public Identification {
		keyFeatures = List.copyOf(keyFeatures);
	}

	public boolean isLowConfidence() {
		return confidence < LOW_CONFIDENCE_THRESHOLD;
	}
}
