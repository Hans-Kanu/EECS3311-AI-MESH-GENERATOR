package AIMesher.ai_mesh_generator.agent;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import AIMesher.ai_mesh_generator.model.Angle;
import AIMesher.ai_mesh_generator.model.Identification;
import AIMesher.ai_mesh_generator.services.ImageGenerator;
import AIMesher.ai_mesh_generator.services.ImageSearch;
import AIMesher.ai_mesh_generator.services.SearchResult;
import AIMesher.ai_mesh_generator.services.VisionModel;

// In-memory stand-ins for the AI services, so the agent's logic can be tested without keys or network.
final class Fakes {

	private Fakes() {
	}

	static final class Vision implements VisionModel {

		Identification identification = new Identification("Office chair", "Furniture", List.of("mesh back"), 0.9);
		// Files isSameItem() accepts; everything else is rejected.
		final List<File> sameItems = new ArrayList<>();
		// Answers for isConsistent(), in order; true once they run out.
		final Deque<Boolean> consistency = new ArrayDeque<>();
		String identifyHint;
		String promptDescription;

		@Override
		public Identification identify(List<File> images, String description) {
			identifyHint = description;
			return identification;
		}

		@Override
		public boolean isSameItem(File candidate, File original) {
			return sameItems.contains(candidate);
		}

		@Override
		public Angle labelAngle(File image) {
			return Angle.FRONT;
		}

		@Override
		public boolean isConsistent(File view, List<File> refs) {
			return consistency.isEmpty() || consistency.poll();
		}

		@Override
		public String writeImagePrompt(String description) {
			promptDescription = description;
			return "PROMPT: " + description;
		}
	}

	record Call(String method, Angle angle, List<File> refs, String text) {
	}

	static final class Generator implements ImageGenerator {

		final List<Call> calls = new ArrayList<>();

		@Override
		public File generateFromPrompt(String prompt) {
			calls.add(new Call("prompt", Angle.FRONT, List.of(), prompt));
			return new File("gen-" + calls.size() + "-front.png");
		}

		@Override
		public File generateAngle(List<File> refs, String subject, Angle angle) {
			calls.add(new Call("angle", angle, List.copyOf(refs), subject));
			return new File("gen-" + calls.size() + "-" + angle.name().toLowerCase() + ".png");
		}
	}

	static final class Search implements ImageSearch {

		final List<SearchResult> results = new ArrayList<>();
		RuntimeException failure;
		int searches;

		@Override
		public List<SearchResult> search(File image, Identification id) {
			searches++;
			if (failure != null) {
				throw failure;
			}
			return results;
		}

		// A result whose download returns the given file without any network access.
		File add(String name) {
			File file = new File(name);
			results.add(new SearchResult("https://img.example/" + name, "https://shop.example/" + name) {
				@Override
				public File download() {
					return file;
				}
			});
			return file;
		}
	}

	static final class Recorder implements ProgressListener {

		final List<String> messages = new ArrayList<>();
		final List<String> errors = new ArrayList<>();

		@Override
		public void onProgress(Stage stage, String message) {
			messages.add(stage + ": " + message);
		}

		@Override
		public void onError(Stage stage, String error) {
			errors.add(stage + ": " + error);
		}

		boolean said(String text) {
			return messages.stream().anyMatch(message -> message.contains(text));
		}
	}
}
