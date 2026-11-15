package AIMesher.ai_mesh_generator.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

import AIMesher.ai_mesh_generator.config.VisionProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReverseImageSearchAdapterTest {

	private static List<SearchResult> search(String webDetection) {
		JsonNode web = JsonMapper.builder().build().readTree(webDetection);
		ReverseImageSearchApi api = new ReverseImageSearchApi(new VisionProperties("key"), "http://unused") {
			@Override
			public JsonNode searchByImage(File image) {
				return web;
			}
		};
		return new ReverseImageSearchAdapter(api).search(new File("mug.png"), null);
	}

	private static List<String> images(List<SearchResult> results) {
		return results.stream().map(SearchResult::getImageUrl).toList();
	}

	@Test
	void lookAlikesComeFirstThenPartsOfThePhotoWithTheirPage() {
		List<SearchResult> results = search("""
				{
				  "fullMatchingImages": [{"url": "https://copy.com/same.jpg"}],
				  "visuallySimilarImages": [{"url": "https://a.com/side.jpg"}, {"url": "https://b.com/back.jpg"}],
				  "pagesWithMatchingImages": [{
				    "url": "https://shop.com/mug",
				    "fullMatchingImages": [{"url": "https://shop.com/same.jpg"}],
				    "partialMatchingImages": [{"url": "https://shop.com/crop.jpg"}]
				  }],
				  "partialMatchingImages": [{"url": "https://c.com/crop.jpg"}]
				}""");

		assertEquals(List.of("https://a.com/side.jpg", "https://b.com/back.jpg", "https://shop.com/crop.jpg",
				"https://c.com/crop.jpg"), images(results));
		assertEquals("https://a.com/side.jpg", results.get(0).getSourceUrl());
		assertEquals("https://shop.com/mug", results.get(2).getSourceUrl());
	}

	@Test
	void aPictureFoundTwiceIsCheckedOnceAndKeepsItsPage() {
		List<SearchResult> results = search("""
				{
				  "visuallySimilarImages": [{"url": "https://shop.com/crop.jpg"}],
				  "pagesWithMatchingImages": [{"url": "https://shop.com/mug", "partialMatchingImages": [{"url": "https://shop.com/crop.jpg"}]}]
				}""");

		assertEquals(1, results.size());
		assertEquals("https://shop.com/mug", results.get(0).getSourceUrl());
	}

	@Test
	void addressesThatCantBeDownloadedAreLeftOut() {
		List<SearchResult> results = search("""
				{"visuallySimilarImages": [{"url": "x-raw-image:///abc"}, {}, {"url": "http://a.com/ok.png"}]}""");

		assertEquals(List.of("http://a.com/ok.png"), images(results));
	}

	@Test
	void nothingFoundGivesNoResults() {
		assertEquals(List.of(), search("{}"));
	}
}
