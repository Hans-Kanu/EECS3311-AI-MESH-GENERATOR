package AIMesher.ai_mesh_generator.services;

import java.io.File;
import java.util.List;

import AIMesher.ai_mesh_generator.model.Angle;

// Adapter target: AI image generation. Returned files are new images on disk.
public interface ImageGenerator {

	// Creates a reference image from a text prompt (F06).
	File generateFromPrompt(String prompt);

	// Creates the given view of the object shown in refs (F07).
	File generateAngle(List<File> refs, Angle angle);
}
