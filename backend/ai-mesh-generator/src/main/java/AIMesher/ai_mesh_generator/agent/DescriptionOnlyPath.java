package AIMesher.ai_mesh_generator.agent;

import java.util.List;

import AIMesher.ai_mesh_generator.model.GenerationRequest;
import AIMesher.ai_mesh_generator.model.ReferenceImageSet;
import AIMesher.ai_mesh_generator.services.ImageGenerator;
import AIMesher.ai_mesh_generator.services.VisionModel;

// Concrete strategy for a description with no image (F06). There is nothing to identify or search for, so the
// reasoning model turns the description into a detailed image prompt, the front view is generated from that
// prompt, and the other views are generated from the front.
public class DescriptionOnlyPath extends GenerationPath {

	public DescriptionOnlyPath(VisionModel vision, ImageGenerator generator) {
		super(vision, generator);
	}

	@Override
	public ReferenceImageSet run(GenerationRequest request, ProgressListener progress) {
		progress.onProgress(Stage.GENERATING_IMAGE, "Writing an image prompt from your description...");
		String frontPrompt = vision.writeImagePrompt(request.getDescription());
		ReferenceImageSet set = new ReferenceImageSet();
		generateViews(set, List.of(), request.getDescription(), frontPrompt, progress);
		return set;
	}
}
