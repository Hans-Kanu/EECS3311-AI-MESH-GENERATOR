package AIMesher.ai_mesh_generator.agent;

import AIMesher.ai_mesh_generator.model.GenerationRequest;
import AIMesher.ai_mesh_generator.model.Identification;
import AIMesher.ai_mesh_generator.services.ImageGenerator;
import AIMesher.ai_mesh_generator.services.ImageSearch;
import AIMesher.ai_mesh_generator.services.VisionModel;

// Concrete strategy for images plus a description. Same steps as ImageOnlyPath, but the description guides the
// identification, and the image generator is given the user's own words as well. When the identification is
// unsure, the description is trusted over it.
public class ImageAndDescriptionPath extends ImageOnlyPath {

	public ImageAndDescriptionPath(VisionModel vision, ImageGenerator generator, ImageSearch search) {
		super(vision, generator, search);
	}

	@Override
	protected String hint(GenerationRequest request) {
		return request.getDescription();
	}

	@Override
	protected String subject(Identification id, GenerationRequest request) {
		if (id.isLowConfidence()) {
			return request.getDescription();
		}
		return describe(id) + ". The user describes it as: " + request.getDescription();
	}
}
