package AIMesher.ai_mesh_generator.services;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import AIMesher.ai_mesh_generator.model.Angle;
import AIMesher.ai_mesh_generator.model.Identification;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Adapter: implements the agent's VisionModel on top of the raw Gemini API (the adaptee). Each method asks Gemini
// for a reply in a fixed JSON shape and checks it before turning it into domain values, so a malformed answer
// fails here with a clear message instead of confusing the agent later. Errors from Gemini itself come from GeminiApi.
@Component
public class GeminiVisionAdapter implements VisionModel {

	private static final String ANGLE_NAMES = Arrays.stream(Angle.values()).map(Angle::name).collect(Collectors.joining(", "));

	private final GeminiApi client;
	private final JsonMapper mapper = JsonMapper.builder().build();

	public GeminiVisionAdapter(GeminiApi client) {
		this.client = client;
	}

	@Override
	public Identification identify(List<File> images, String description) {
		boolean hasDescription = description != null && !description.isBlank();
		if (images.isEmpty() && !hasDescription) {
			throw new IllegalArgumentException("Give at least one image or a description to identify.");
		}
		String prompt = """
				Identify the main object %s, so that it can be modelled in 3D.
				%s
				Reply with a JSON object:
				{"name": a short, specific name for the object,
				 "category": the general kind of object,
				 "keyFeatures": a list of features that set it apart (shape, parts, materials, colours),
				 "confidence": a number from 0 to 1 for how sure you are of the name}
				""".formatted(images.isEmpty() ? "described below" : "shown in the image(s)",
				hasDescription ? "The user describes it as: " + description.strip() : "");
		JsonNode reply = ask(prompt, images, "identification");
		double confidence = number(reply, "confidence", "identification");
		if (confidence < 0 || confidence > 1) {
			throw unusable("identification", "confidence " + confidence + " is outside 0-1");
		}
		return new Identification(text(reply, "name", "identification"), text(reply, "category", "identification"),
				strings(reply, "keyFeatures", "identification"), confidence);
	}

	@Override
	public boolean isSameItem(File candidate, File original) {
		String prompt = """
				Image 1 is the user's photo of an object. Image 2 was found online.
				Does image 2 show the same item: the same product or model, not just the same kind of object?
				Reply with a JSON object: {"sameItem": true or false, "reason": one short sentence}
				""";
		return bool(ask(prompt, List.of(original, candidate), "match check"), "sameItem", "match check");
	}

	@Override
	public Angle labelAngle(File image) {
		String prompt = """
				Which side of the object does this photo mainly show? Choose the closest of: %s.
				LEFT means the photo shows the object's own left side, which is on your right when you look at its front.
				RIGHT means it shows the object's own right side.
				Reply with a JSON object: {"angle": one of %s}
				""".formatted(ANGLE_NAMES, ANGLE_NAMES);
		String angle = text(ask(prompt, List.of(image), "angle label"), "angle", "angle label");
		try {
			return Angle.valueOf(angle.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw unusable("angle label", "\"" + angle + "\" is not one of " + ANGLE_NAMES);
		}
	}

	@Override
	public boolean isConsistent(File view, List<File> refs) {
		if (refs.isEmpty()) {
			throw new IllegalArgumentException("A consistency check needs at least one reference image.");
		}
		String prompt = """
				Image 1 is an AI-generated view of an object. Images 2 to %d are reference photos of that object.
				Does image 1 show the same object, with the same shape, proportions, colours and details?
				Reply with a JSON object: {"consistent": true or false, "problems": a list of differences, empty if none}
				""".formatted(refs.size() + 1);
		List<File> images = new ArrayList<>();
		images.add(view);
		images.addAll(refs);
		return bool(ask(prompt, images, "consistency check"), "consistent", "consistency check");
	}

	@Override
	public String writeImagePrompt(String description) {
		if (description == null || description.isBlank()) {
			throw new IllegalArgumentException("A description is needed to write an image prompt.");
		}
		String prompt = """
				Write a prompt for an image generation model to create a reference photo of this object for 3D modelling:
				%s
				The image should show the whole object from the front, on a plain white background, with even lighting
				and no text. Describe its shape, parts, materials and colours precisely.
				Reply with a JSON object: {"prompt": the prompt}
				""".formatted(description.strip());
		return text(ask(prompt, List.of(), "image prompt"), "prompt", "image prompt");
	}

	private JsonNode ask(String prompt, List<File> images, String what) {
		String reply = client.generateJson(prompt, images);
		JsonNode node;
		try {
			node = mapper.readTree(reply);
		} catch (JacksonException e) {
			throw unusable(what, "the reply is not valid JSON");
		}
		if (!node.isObject()) {
			throw unusable(what, "the reply is not a JSON object");
		}
		return node;
	}

	private static String text(JsonNode reply, String field, String what) {
		JsonNode value = reply.path(field);
		if (!value.isString() || value.asString().isBlank()) {
			throw unusable(what, "\"" + field + "\" is missing or empty");
		}
		return value.asString().strip();
	}

	private static double number(JsonNode reply, String field, String what) {
		JsonNode value = reply.path(field);
		if (!value.isNumber()) {
			throw unusable(what, "\"" + field + "\" is missing or not a number");
		}
		return value.doubleValue();
	}

	private static boolean bool(JsonNode reply, String field, String what) {
		JsonNode value = reply.path(field);
		if (!value.isBoolean()) {
			throw unusable(what, "\"" + field + "\" is missing or not true/false");
		}
		return value.booleanValue();
	}

	private static List<String> strings(JsonNode reply, String field, String what) {
		JsonNode value = reply.path(field);
		if (!value.isArray()) {
			throw unusable(what, "\"" + field + "\" is missing or not a list");
		}
		List<String> items = new ArrayList<>();
		for (int i = 0; i < value.size(); i++) {
			if (!value.get(i).isString()) {
				throw unusable(what, "\"" + field + "\" contains something that isn't text");
			}
			items.add(value.get(i).asString().strip());
		}
		return items;
	}

	private static IllegalStateException unusable(String what, String problem) {
		return new IllegalStateException("Gemini gave an unusable " + what + ": " + problem + ".");
	}
}
