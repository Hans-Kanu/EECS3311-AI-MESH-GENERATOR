package AIMesher.ai_mesh_generator.agent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import AIMesher.ai_mesh_generator.model.GenerationRequest;
import AIMesher.ai_mesh_generator.model.Identification;
import AIMesher.ai_mesh_generator.model.ImageOrigin;
import AIMesher.ai_mesh_generator.model.ReferenceImage;
import AIMesher.ai_mesh_generator.model.ReferenceImageSet;
import AIMesher.ai_mesh_generator.services.ImageGenerator;
import AIMesher.ai_mesh_generator.services.ImageSearch;
import AIMesher.ai_mesh_generator.services.SearchResult;
import AIMesher.ai_mesh_generator.services.VisionModel;

// Concrete strategy for one or more images with no description: identify the object (F03), search the web for
// more photos of it and keep only those that show the same item (F04), then generate the four views from all
// the references. If the search finds too little, the views are generated from the user's images alone.
public class ImageOnlyPath extends GenerationPath {

	// Search results checked per run. Each costs a download and a Gemini call.
	static final int MAX_RESULTS_CHECKED = 8;
	// Fewer matching photos than this counts as the search finding too little.
	static final int MIN_WEB_MATCHES = 2;

	private final ImageSearch search;

	public ImageOnlyPath(VisionModel vision, ImageGenerator generator, ImageSearch search) {
		super(vision, generator);
		this.search = search;
	}

	@Override
	public ReferenceImageSet run(GenerationRequest request, ProgressListener progress) {
		List<File> uploads = request.getImages();
		progress.onProgress(Stage.IDENTIFYING, "Identifying the object...");
		Identification id = vision.identify(uploads, hint(request));
		progress.onProgress(Stage.IDENTIFYING, "Identified: " + id.name() + " (" + id.category() + ", "
				+ Math.round(id.confidence() * 100) + "% sure)");

		ReferenceImageSet set = new ReferenceImageSet();
		for (File upload : uploads) {
			set.add(new ReferenceImage(upload, vision.labelAngle(upload), ImageOrigin.UPLOADED, null));
		}

		List<File> refs = new ArrayList<>(uploads);
		// An unsure identification would search for the wrong item, so the search is skipped.
		List<ReferenceImage> matches = id.isLowConfidence() ? List.of() : collectWebPhotos(uploads.get(0), id, progress);
		if (id.isLowConfidence()) {
			progress.onProgress(Stage.SEARCHING, "Not sure enough what the object is to search for it; "
					+ "generating the views from your image(s) only. Adding a description helps.");
		} else if (matches.size() < MIN_WEB_MATCHES) {
			progress.onProgress(Stage.SEARCHING, "The search found " + matches.size()
					+ " matching photo(s), which is too few; generating the views from your image(s) only.");
		} else {
			progress.onProgress(Stage.SEARCHING, "Found " + matches.size() + " matching photos online.");
			matches.forEach(set::add);
			matches.forEach(match -> refs.add(match.getFile()));
		}
		generateViews(set, refs, subject(id, request), null, progress);
		return set;
	}

	// The description passed to identify(); null when there is none.
	protected String hint(GenerationRequest request) {
		return null;
	}

	// What the image generator is told the object is.
	protected String subject(Identification id, GenerationRequest request) {
		return describe(id);
	}

	static String describe(Identification id) {
		String text = id.name() + " (" + id.category() + ")";
		return id.keyFeatures().isEmpty() ? text : text + ", with " + String.join(", ", id.keyFeatures());
	}

	// Downloads search results and keeps the ones the reasoning model says show the same item.
	// A result that can't be downloaded or checked is skipped rather than failing the whole run.
	private List<ReferenceImage> collectWebPhotos(File original, Identification id, ProgressListener progress) {
		progress.onProgress(Stage.SEARCHING, "Searching the web for more photos of the " + id.name() + "...");
		List<SearchResult> results;
		try {
			results = search.search(original, id);
		} catch (RuntimeException e) {
			progress.onProgress(Stage.SEARCHING, "The image search failed: " + e.getMessage());
			return List.of();
		}
		List<ReferenceImage> matches = new ArrayList<>();
		for (SearchResult result : results.stream().limit(MAX_RESULTS_CHECKED).toList()) {
			try {
				File photo = result.download();
				if (vision.isSameItem(photo, original)) {
					matches.add(new ReferenceImage(photo, vision.labelAngle(photo), ImageOrigin.WEB,
							result.getSourceUrl()));
				}
			} catch (RuntimeException e) {
				progress.onProgress(Stage.SEARCHING, "Skipped a photo from " + result.getSourceUrl() + ": "
						+ e.getMessage());
			}
		}
		return matches;
	}
}
