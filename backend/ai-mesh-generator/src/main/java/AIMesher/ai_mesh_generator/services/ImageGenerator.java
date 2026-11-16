package AIMesher.ai_mesh_generator.services;

import java.io.File;
import java.util.List;

import AIMesher.ai_mesh_generator.model.Angle;

// Adapter target: AI image generation. Returned files are new images on disk. Each call is one request for one
// image, so every view gets its own prompt.
public interface ImageGenerator {

	// Creates a front reference image from a text prompt (F06).
	File generateFromPrompt(String prompt);

	// Creates the given view of the object shown in refs. subject says what the object is (F07). For any angle
	// other than FRONT, refs.get(0) must be the front view: the new view is described as a turn from it.
	File generateAngle(List<File> refs, String subject, Angle angle);
}
