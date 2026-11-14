package AIMesher.ai_mesh_generator.agent;

import java.io.File;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import AIMesher.ai_mesh_generator.model.Angle;
import AIMesher.ai_mesh_generator.model.GenerationRequest;
import AIMesher.ai_mesh_generator.model.ImageOrigin;
import AIMesher.ai_mesh_generator.model.ReferenceImage;
import AIMesher.ai_mesh_generator.model.ReferenceImageSet;
import AIMesher.ai_mesh_generator.services.ImageGenerator;
import AIMesher.ai_mesh_generator.services.ImageSearch;
import AIMesher.ai_mesh_generator.services.JobStatus;
import AIMesher.ai_mesh_generator.services.MeshGenerator;
import AIMesher.ai_mesh_generator.services.VisionModel;

// Facade: the controller's single entry point to the AI pipeline. startGeneration() picks a GenerationPath
// (Strategy) for the input and runs it to build the reference set; buildMesh() sends the generated views to the
// mesh generator and waits for the job. The controller never sees the four services or the order they run in.
//
// Observer subject: the views register a ProgressListener and get every step as it happens, without the agent
// knowing whether it is talking to the GUI or the CLI.
@Component
public class ModelAgent {

	static final Duration POLL_INTERVAL = Duration.ofSeconds(10);
	static final Duration MAX_MESH_WAIT = Duration.ofMinutes(15);
	static final int MAX_NETWORK_ATTEMPTS = 3;

	// Waits between status checks. Tests pass one that records the wait instead of sleeping.
	interface Sleeper {
		void sleep(Duration delay) throws InterruptedException;
	}

	private final VisionModel vision;
	private final ImageGenerator generator;
	private final ImageSearch search;
	private final MeshGenerator mesh;
	private final Sleeper sleeper;
	private final List<ProgressListener> listeners = new CopyOnWriteArrayList<>();
	// Forwards a path's updates to every listener and remembers the stage, so a failure is reported against it.
	private final Broadcaster broadcaster = new Broadcaster();
	private GenerationPath path;

	// Search and mesh generation are optional services: without a search the paths fall back to generating the
	// views from the user's input, and without a mesh generator buildMesh() explains what is missing.
	@Autowired
	public ModelAgent(VisionModel vision, ImageGenerator generator, Optional<ImageSearch> search,
			Optional<MeshGenerator> mesh) {
		this(vision, generator, search.orElse(null), mesh.orElse(null), Thread::sleep);
	}

	ModelAgent(VisionModel vision, ImageGenerator generator, ImageSearch search, MeshGenerator mesh, Sleeper sleeper) {
		this.vision = vision;
		this.generator = generator;
		this.search = search != null ? search : (image, id) -> List.of();
		this.mesh = mesh;
		this.sleeper = sleeper;
	}

	public void addListener(ProgressListener listener) {
		listeners.add(listener);
	}

	public void removeListener(ProgressListener listener) {
		listeners.remove(listener);
	}

	// Runs the path that fits the input (F02) and returns the new reference set: the user's images, any matching
	// web photos, and the four generated views (F03, F04, F06, F07). Failures are reported to the listeners and
	// rethrown for the controller.
	public ReferenceImageSet startGeneration(GenerationRequest request) {
		request.validate();
		path = choosePath(request);
		try {
			ReferenceImageSet set = path.run(request, broadcaster);
			notifyProgress(Stage.DONE, "The reference images are ready. Review them, then build the 3D model.");
			return set;
		} catch (RuntimeException e) {
			notifyError(broadcaster.stage, e.getMessage());
			throw e;
		}
	}

	GenerationPath choosePath(GenerationRequest request) {
		if (!request.hasImage()) {
			return new DescriptionOnlyPath(vision, generator);
		}
		if (!request.hasDescription()) {
			return new ImageOnlyPath(vision, generator, search);
		}
		return new ImageAndDescriptionPath(vision, generator, search);
	}

	// Sends the included AI-generated views to the mesh generator and waits for the model file (F08).
	// Web and uploaded photos are references only; they never go to the mesh generator.
	public File buildMesh(ReferenceImageSet set) {
		try {
			Map<Angle, File> views = meshViews(set);
			if (mesh == null) {
				throw new IllegalStateException("No 3D generation service is configured. Set the Hunyuan 3D keys in .env.");
			}
			notifyProgress(Stage.BUILDING_MESH, "Sending " + views.size() + " views to the 3D generator...");
			String jobId = mesh.submitJob(views);
			notifyProgress(Stage.BUILDING_MESH, "The 3D generator accepted the job (id " + jobId + ").");
			waitFor(jobId);
			notifyProgress(Stage.BUILDING_MESH, "Downloading the model...");
			File model = surviveNetworkErrors(jobId, "Downloading the model", () -> mesh.download(jobId));
			notifyProgress(Stage.DONE, "The 3D model is ready.");
			return model;
		} catch (RuntimeException e) {
			notifyError(Stage.BUILDING_MESH, e.getMessage());
			throw e;
		}
	}

	static Map<Angle, File> meshViews(ReferenceImageSet set) {
		Map<Angle, File> views = new EnumMap<>(Angle.class);
		for (ReferenceImage image : set.getIncluded()) {
			if (image.getOrigin() == ImageOrigin.AI_GENERATED) {
				views.put(image.getAngle(), image.getFile());
			}
		}
		if (!views.containsKey(Angle.FRONT)) {
			throw new IllegalStateException("There is no generated front view. Generate the reference images first.");
		}
		return views;
	}

	private void waitFor(String jobId) {
		Duration waited = Duration.ZERO;
		JobStatus status = checkStatus(jobId);
		while (!status.isFinished()) {
			if (waited.compareTo(MAX_MESH_WAIT) >= 0) {
				throw new IllegalStateException("The 3D generator took longer than " + MAX_MESH_WAIT.toMinutes()
						+ " minutes. Try building again later.");
			}
			notifyProgress(Stage.BUILDING_MESH, "Building the 3D model (" + status.name().toLowerCase() + ", "
					+ waited.toSeconds() + "s so far)...");
			pause();
			waited = waited.plus(POLL_INTERVAL);
			status = checkStatus(jobId);
		}
		if (status == JobStatus.FAILED) {
			throw new IllegalStateException("The 3D generator could not build a model from these views.");
		}
	}

	private JobStatus checkStatus(String jobId) {
		return surviveNetworkErrors(jobId, "Checking the job", () -> mesh.getStatus(jobId));
	}

	// The job is already paid for and keeps running at the service, so a dropped connection while checking on it
	// or downloading it is tried again, up to MAX_NETWORK_ATTEMPTS times in a row, instead of losing the model.
	private <T> T surviveNetworkErrors(String jobId, String what, Supplier<T> call) {
		for (int attempt = 1;; attempt++) {
			try {
				return call.get();
			} catch (UncheckedIOException e) {
				if (attempt >= MAX_NETWORK_ATTEMPTS) {
					throw new IllegalStateException(what + " failed " + attempt + " times in a row (" + e.getMessage()
							+ "). The job (id " + jobId + ") may still finish at the 3D service.", e);
				}
				notifyProgress(Stage.BUILDING_MESH, what + " failed (" + e.getMessage() + "); trying again in "
						+ POLL_INTERVAL.toSeconds() + "s...");
				pause();
			}
		}
	}

	private void pause() {
		try {
			sleeper.sleep(POLL_INTERVAL);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Building the model was interrupted.", e);
		}
	}

	GenerationPath getPath() {
		return path;
	}

	private void notifyProgress(Stage stage, String message) {
		listeners.forEach(listener -> listener.onProgress(stage, message));
	}

	private void notifyError(Stage stage, String error) {
		listeners.forEach(listener -> listener.onError(stage, error));
	}

	private final class Broadcaster implements ProgressListener {

		private Stage stage = Stage.IDENTIFYING;

		@Override
		public void onProgress(Stage stage, String message) {
			this.stage = stage;
			notifyProgress(stage, message);
		}

		@Override
		public void onError(Stage stage, String error) {
			notifyError(stage, error);
		}
	}
}
