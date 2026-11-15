package AIMesher.ai_mesh_generator.services;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import AIMesher.ai_mesh_generator.model.Identification;
import tools.jackson.databind.JsonNode;

// Adapter: makes Cloud Vision's web detection (ReverseImageSearchApi) fit the ImageSearch interface the agent uses
// (F04). The agent then downloads the results, keeps those Gemini says show the same item and labels their angle.
//
// Order matters, because the agent checks only the first few results. Pictures that look like the user's come first,
// since they can show the item from other angles. Pictures that contain part of the user's photo come next, with the
// page they were found on. Exact copies of the user's photo are left out: they add no new angle.
@Component
public class ReverseImageSearchAdapter implements ImageSearch {

	private final ReverseImageSearchApi api;

	public ReverseImageSearchAdapter(ReverseImageSearchApi api) {
		this.api = api;
	}

	// Cloud Vision searches by the picture alone, so the identification isn't sent; the agent's same-item check
	// uses it afterwards.
	@Override
	public List<SearchResult> search(File image, Identification id) {
		JsonNode web = api.searchByImage(image);
		// Keyed by image address, so a picture found in several places is checked once.
		Map<String, String> sources = new LinkedHashMap<>();
		for (JsonNode similar : elements(web.path("visuallySimilarImages"))) {
			add(sources, similar.path("url").asString(""), null);
		}
		for (JsonNode page : elements(web.path("pagesWithMatchingImages"))) {
			String pageUrl = page.path("url").asString("");
			for (JsonNode partial : elements(page.path("partialMatchingImages"))) {
				add(sources, partial.path("url").asString(""), pageUrl);
			}
		}
		for (JsonNode partial : elements(web.path("partialMatchingImages"))) {
			add(sources, partial.path("url").asString(""), null);
		}
		List<SearchResult> results = new ArrayList<>();
		sources.forEach((imageUrl, pageUrl) -> results.add(new SearchResult(imageUrl, pageUrl)));
		return results;
	}

	// Only web addresses can be downloaded. Without a known page, the picture's own address is its source.
	private static void add(Map<String, String> sources, String imageUrl, String pageUrl) {
		if (!isWebAddress(imageUrl)) {
			return;
		}
		String source = isWebAddress(pageUrl) ? pageUrl : imageUrl;
		sources.merge(imageUrl, source, (first, second) -> first.equals(imageUrl) ? second : first);
	}

	// The items of a JSON array; none when the field is missing.
	private static List<JsonNode> elements(JsonNode array) {
		List<JsonNode> items = new ArrayList<>();
		for (int i = 0; i < array.size(); i++) {
			items.add(array.get(i));
		}
		return items;
	}

	private static boolean isWebAddress(String url) {
		return url != null && (url.startsWith("https://") || url.startsWith("http://"));
	}
}
