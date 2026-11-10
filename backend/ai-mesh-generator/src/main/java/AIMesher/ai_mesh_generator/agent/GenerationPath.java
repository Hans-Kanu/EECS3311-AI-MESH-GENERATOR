package AIMesher.ai_mesh_generator.agent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import AIMesher.ai_mesh_generator.model.Angle;
import AIMesher.ai_mesh_generator.model.GenerationRequest;
import AIMesher.ai_mesh_generator.model.ImageOrigin;
import AIMesher.ai_mesh_generator.model.ReferenceImage;
import AIMesher.ai_mesh_generator.model.ReferenceImageSet;
import AIMesher.ai_mesh_generator.services.ImageGenerator;
import AIMesher.ai_mesh_generator.services.VisionModel;

// Strategy: one way of turning the user's input (F02) into a reference set. Each subclass gathers references
// differently in run(), and all of them finish with generateViews(), which makes the four views sent to the mesh
// generator. ModelAgent (the context) picks the subclass that fits the request.
public abstract class GenerationPath {

	// The views the mesh generator needs. FRONT comes first: the other views are generated from it.
	static final List<Angle> VIEW_ORDER = List.of(Angle.FRONT, Angle.LEFT, Angle.RIGHT, Angle.BACK);

	// A view that fails the consistency check is generated again, up to this many times in total.
	static final int MAX_VIEW_ATTEMPTS = 2;

	protected final VisionModel vision;
	protected final ImageGenerator generator;

	protected GenerationPath(VisionModel vision, ImageGenerator generator) {
		this.vision = vision;
		this.generator = generator;
	}

	public abstract ReferenceImageSet run(GenerationRequest request, ProgressListener progress);

	// Generates FRONT, LEFT, RIGHT and BACK as separate requests, in that order, and adds them to the set.
	// The front is made from the references (or from frontPrompt when there are none); each later view is made
	// from the references plus the generated front, so all four show the same object.
	protected void generateViews(ReferenceImageSet set, List<File> refs, String subject, String frontPrompt,
			ProgressListener progress) {
		File front = null;
		for (Angle angle : VIEW_ORDER) {
			progress.onProgress(Stage.GENERATING_ANGLES, "Generating the " + name(angle) + " view...");
			List<File> inputs = new ArrayList<>(refs);
			if (front != null) {
				inputs.add(0, front);
			}
			// The front is checked against the references, the other views against the front.
			List<File> checkAgainst = front == null ? refs : List.of(front);
			File view = generateChecked(inputs, subject, angle, front == null ? frontPrompt : null, checkAgainst,
					progress);
			if (angle == Angle.FRONT) {
				front = view;
			}
			set.add(new ReferenceImage(view, angle, ImageOrigin.AI_GENERATED, null));
		}
	}

	private File generateChecked(List<File> inputs, String subject, Angle angle, String prompt, List<File> checkAgainst,
			ProgressListener progress) {
		File view = null;
		for (int attempt = 1; attempt <= MAX_VIEW_ATTEMPTS; attempt++) {
			view = prompt != null && inputs.isEmpty()
					? generator.generateFromPrompt(prompt)
					: generator.generateAngle(inputs, subject, angle);
			if (checkAgainst.isEmpty() || vision.isConsistent(view, checkAgainst)) {
				return view;
			}
			if (attempt < MAX_VIEW_ATTEMPTS) {
				progress.onProgress(Stage.GENERATING_ANGLES,
						"The " + name(angle) + " view doesn't match the references; generating it again...");
			}
		}
		progress.onProgress(Stage.GENERATING_ANGLES, "Keeping the last " + name(angle)
				+ " view, which still differs from the references. Review it before building the model.");
		return view;
	}

	static String name(Angle angle) {
		return angle.name().toLowerCase();
	}
}
