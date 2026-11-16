package AIMesher.ai_mesh_generator.services;

import java.io.File;
import java.util.List;

import AIMesher.ai_mesh_generator.model.Angle;
import AIMesher.ai_mesh_generator.model.Identification;

// Adapter target: the agent's reasoning model (F03, F04, F07). The agent calls only this interface,
// so the provider behind it (Gemini today) can be replaced without changing the agent.
public interface VisionModel {

	// Works out what object the images show; the description, if any, is used as a hint.
	Identification identify(List<File> images, String description);

	// Whether a photo found online shows the same item as the user's reference image.
	boolean isSameItem(File candidate, File original);

	Angle labelAngle(File image);

	// Whether a generated view shows the same object as the references, seen from the given angle. For any angle
	// other than FRONT, refs.get(0) is the front view that the angle is measured from.
	boolean isConsistent(File view, List<File> refs, Angle angle);

	// Turns the user's description into a detailed prompt for the image generator (F06).
	String writeImagePrompt(String description);
}
