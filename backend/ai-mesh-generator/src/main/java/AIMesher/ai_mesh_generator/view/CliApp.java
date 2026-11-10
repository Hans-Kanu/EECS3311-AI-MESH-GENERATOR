package AIMesher.ai_mesh_generator.view;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import AIMesher.ai_mesh_generator.agent.ProgressListener;
import AIMesher.ai_mesh_generator.agent.Stage;
import AIMesher.ai_mesh_generator.controller.AppController;
import AIMesher.ai_mesh_generator.exporter.ExportFormat;
import AIMesher.ai_mesh_generator.model.ModelVersion;
import AIMesher.ai_mesh_generator.model.Project;
import AIMesher.ai_mesh_generator.model.ReferenceImage;
import AIMesher.ai_mesh_generator.model.ReferenceImageSet;

// The command-line view. It only parses commands and prints results; all the work goes through
// the same AppController as the GUI. Starts only with the "cli" profile (see application-cli.properties).
@Component
@Profile("cli")
public class CliApp implements CommandLineRunner, ProgressListener {

	// A word, or a "quoted phrase" so names can contain spaces.
	private static final Pattern TOKEN = Pattern.compile("\"([^\"]*)\"|(\\S+)");

	private static final DateTimeFormatter VERSION_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final String RESTORE_USAGE = "versions restore <number>";
	private static final String DELETE_USAGE = "versions delete <number>";
	private static final String EXPORT_USAGE = "export <number> --format obj|stl --out <file>";
	private static final String GENERATE_USAGE = "generate [--image <file>]... [--describe \"<text>\"]";
	private static final String EXCLUDE_USAGE = "images exclude <number>";
	private static final String INCLUDE_USAGE = "images include <number>";

	private final AppController controller;
	private final BufferedReader in;
	private final PrintStream out;
	private boolean running;

	@Autowired
	public CliApp(AppController controller) {
		this(controller, new InputStreamReader(System.in), System.out);
	}

	CliApp(AppController controller, Reader in, PrintStream out) {
		this.controller = controller;
		this.in = new BufferedReader(in);
		this.out = out;
	}

	@Override
	public void run(String... args) {
		out.println("AI Mesh Generator. Type help for the list of commands.");
		// Observer: the agent's progress is printed as it happens (onProgress / onError below).
		controller.addProgressListener(this);
		running = true;
		try {
			while (running) {
				out.print("> ");
				out.flush();
				String line = readLine();
				if (line == null) {
					break;
				}
				parseCommand(line);
			}
		} finally {
			controller.removeProgressListener(this);
		}
	}

	@Override
	public void onProgress(Stage stage, String message) {
		out.println("[" + stage + "] " + message);
	}

	@Override
	public void onError(Stage stage, String error) {
		out.println("[" + stage + "] Error: " + error);
	}

	private void parseCommand(String line) {
		List<String> words = tokenize(line);
		if (words.isEmpty()) {
			return;
		}
		try {
			switch (words.get(0).toLowerCase()) {
				case "projects" -> projects(words.subList(1, words.size()));
				case "versions" -> versions(words.subList(1, words.size()));
				case "export" -> export(words.subList(1, words.size()));
				case "generate" -> generate(words.subList(1, words.size()));
				case "images" -> images(words.subList(1, words.size()));
				case "build" -> build();
				case "help" -> printHelp();
				case "exit", "quit" -> running = false;
				default -> out.println("Unknown command \"" + words.get(0) + "\". Type help for the list of commands.");
			}
		} catch (IllegalArgumentException | UncheckedIOException e) {
			out.println("Error: " + e.getMessage());
		}
	}

	// F02: generate --image a.png --image b.jpg --describe "red stool", with any mix of the options.
	private void generate(List<String> args) {
		List<File> images = new ArrayList<>();
		String description = null;
		if (args.isEmpty() || args.size() % 2 != 0) {
			throw new IllegalArgumentException("Usage: " + GENERATE_USAGE);
		}
		for (int i = 0; i < args.size(); i += 2) {
			switch (args.get(i).toLowerCase()) {
				case "--image" -> images.add(new File(args.get(i + 1)));
				case "--describe" -> description = args.get(i + 1);
				default -> throw new IllegalArgumentException("Usage: " + GENERATE_USAGE);
			}
		}
		controller.getCurrentProject();
		try {
			ReferenceImageSet set = controller.generateReferences(images, description);
			out.println("Generated " + set.getImages().size() + " reference images:");
			listImages(set);
			out.println("Review them with images exclude/include, then run build.");
		} catch (IllegalStateException e) {
			// The agent has already printed the error through onError().
			out.println("Generation stopped. The project's reference images were not changed.");
		}
	}

	// F05: lists the reference set with where each image came from, and lets the user leave images out.
	private void images(List<String> args) {
		String action = args.isEmpty() ? "list" : args.get(0).toLowerCase();
		switch (action) {
			case "list" -> {
				ReferenceImageSet set = controller.getCurrentProject().getReferenceSet();
				if (set.getImages().isEmpty()) {
					out.println("This project has no reference images yet. Use generate to create them.");
				} else {
					listImages(set);
				}
			}
			case "exclude" -> {
				requireArgs(args, 1, EXCLUDE_USAGE);
				controller.excludeImage(imageAt(parseNumber(args.get(1), EXCLUDE_USAGE)).getId());
				out.println("Image " + args.get(1) + " will be left out of the next build.");
			}
			case "include" -> {
				requireArgs(args, 1, INCLUDE_USAGE);
				controller.includeImage(imageAt(parseNumber(args.get(1), INCLUDE_USAGE)).getId());
				out.println("Image " + args.get(1) + " is back in the set.");
			}
			default -> out.println("Unknown images command \"" + args.get(0) + "\". Type help for the list of commands.");
		}
	}

	private void listImages(ReferenceImageSet set) {
		List<ReferenceImage> images = set.getImages();
		for (int i = 0; i < images.size(); i++) {
			ReferenceImage image = images.get(i);
			String role = switch (image.getOrigin()) {
				case AI_GENERATED -> image.getAngle().name().toLowerCase() + " view (AI-generated, used for the model)";
				case UPLOADED -> "your image (reference only)";
				case WEB -> "web photo from " + image.getSourceUrl() + " (reference only)";
			};
			out.println("  " + (i + 1) + ". " + role + (image.isExcluded() ? "  [excluded]" : ""));
			out.println("     " + image.getFile());
		}
	}

	private ReferenceImage imageAt(int number) {
		List<ReferenceImage> images = controller.getCurrentProject().getReferenceSet().getImages();
		if (number < 1 || number > images.size()) {
			throw new IllegalArgumentException("No image " + number + ". Type images list to see the numbers.");
		}
		return images.get(number - 1);
	}

	// F08: builds a model from the generated views and saves it as a new version.
	private void build() {
		try {
			ModelVersion version = controller.buildModel();
			out.println("Saved the model as version " + version.getNumber() + ".");
		} catch (IllegalStateException e) {
			out.println("No model was built.");
		}
	}

	private void projects(List<String> args) {
		String action = args.isEmpty() ? "list" : args.get(0).toLowerCase();
		switch (action) {
			case "list" -> listProjects();
			case "new" -> {
				requireArgs(args, 1, "projects new <name>");
				controller.createProject(args.get(1));
				out.println("Created and opened project \"" + args.get(1).strip() + "\".");
			}
			case "open" -> {
				requireArgs(args, 1, "projects open <name>");
				Project project = controller.openProject(args.get(1));
				out.println("Opened \"" + project.getName() + "\": " + project.getVersions().size() + " version(s), "
						+ project.getReferenceSet().getImages().size() + " reference image(s).");
			}
			case "rename" -> {
				requireArgs(args, 2, "projects rename <old name> <new name>");
				controller.renameProject(args.get(1), args.get(2));
				out.println("Renamed \"" + args.get(1) + "\" to \"" + args.get(2).strip() + "\".");
			}
			case "delete" -> {
				requireArgs(args, 1, "projects delete <name>");
				deleteProject(args.get(1));
			}
			default -> out.println("Unknown projects command \"" + args.get(0) + "\". Type help for the list of commands.");
		}
	}

	private void listProjects() {
		List<String> names = controller.listProjects();
		if (names.isEmpty()) {
			out.println("No projects yet. Create one with: projects new <name>");
			return;
		}
		names.forEach(name -> out.println("  " + name));
	}

	// UC01 3a: deleting removes every image and version, so the user must confirm first.
	private void deleteProject(String name) {
		if (controller.listProjects().stream().noneMatch(name::equalsIgnoreCase)) {
			throw new IllegalArgumentException("No project named \"" + name + "\".");
		}
		if (confirm("Delete project \"" + name + "\" and all its versions?")) {
			controller.deleteProject(name);
			out.println("Deleted project \"" + name + "\".");
		} else {
			out.println("Not deleted.");
		}
	}

	private void versions(List<String> args) {
		String action = args.isEmpty() ? "list" : args.get(0).toLowerCase();
		switch (action) {
			case "list" -> listVersions();
			case "restore" -> {
				requireArgs(args, 1, RESTORE_USAGE);
				ModelVersion version = controller.restoreVersion(parseNumber(args.get(1), RESTORE_USAGE));
				out.println("Version " + version.getNumber() + " is now the current version.");
			}
			case "delete" -> {
				requireArgs(args, 1, DELETE_USAGE);
				deleteVersion(parseNumber(args.get(1), DELETE_USAGE));
			}
			default -> out.println("Unknown versions command \"" + args.get(0) + "\". Type help for the list of commands.");
		}
	}

	private void listVersions() {
		Project project = controller.getCurrentProject();
		if (project.getVersions().isEmpty()) {
			out.println("No models have been generated in this project yet.");
			return;
		}
		for (ModelVersion version : project.getVersions()) {
			out.println("  v" + version.getNumber() + "  " + version.getCreatedAt().format(VERSION_TIME)
					+ (version.getNumber() == project.getCurrentVersion() ? "  (current)" : "")
					+ (version.isAvailable() ? "" : "  (model file missing)"));
		}
	}

	// UC09 3a: the user must confirm before a version is deleted.
	private void deleteVersion(int number) {
		// Fails here for an unknown number, so the user isn't asked about a version that doesn't exist.
		controller.getCurrentProject().getVersion(number);
		if (confirm("Delete version " + number + "?")) {
			controller.deleteVersion(number);
			out.println("Deleted version " + number + ".");
		} else {
			out.println("Not deleted.");
		}
	}

	// export <number> --format obj|stl --out <file>, with the two options in either order.
	private void export(List<String> args) {
		if (args.size() != 5) {
			throw new IllegalArgumentException("Usage: " + EXPORT_USAGE);
		}
		int number = parseNumber(args.get(0), EXPORT_USAGE);
		String format = null;
		String file = null;
		for (int i = 1; i < args.size(); i += 2) {
			switch (args.get(i).toLowerCase()) {
				case "--format" -> format = args.get(i + 1);
				case "--out" -> file = args.get(i + 1);
				default -> throw new IllegalArgumentException("Usage: " + EXPORT_USAGE);
			}
		}
		if (format == null || file == null) {
			throw new IllegalArgumentException("Usage: " + EXPORT_USAGE);
		}
		ExportFormat exportFormat = parseFormat(format);
		Path path = withExtension(Path.of(file), exportFormat);
		controller.getCurrentProject().getVersion(number);
		// UC10 2b: ask before replacing an existing file.
		if (Files.exists(path) && !confirm(path + " already exists. Overwrite it?")) {
			out.println("Not exported.");
			return;
		}
		if (exportFormat == ExportFormat.STL) {
			out.println("Note: STL files don't store colours or textures.");
		}
		controller.exportVersion(number, exportFormat, path);
		out.println("Exported version " + number + " to " + path + ".");
	}

	private static ExportFormat parseFormat(String format) {
		try {
			return ExportFormat.valueOf(format.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Unknown format \"" + format + "\". Use obj or stl.");
		}
	}

	// Adds the format's extension when the file name has none, as a save dialog would.
	private static Path withExtension(Path path, ExportFormat format) {
		Path name = path.getFileName();
		if (name == null || name.toString().contains(".")) {
			return path;
		}
		return path.resolveSibling(name + "." + format.extension());
	}

	private static int parseNumber(String text, String usage) {
		try {
			return Integer.parseInt(text);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Usage: " + usage);
		}
	}

	private boolean confirm(String question) {
		out.print(question + " (y/n) ");
		out.flush();
		String answer = readLine();
		return answer != null && answer.strip().equalsIgnoreCase("y");
	}

	private void printHelp() {
		out.println("""
				Commands:
				  projects list                           List saved projects
				  projects new <name>                     Create a project and open it
				  projects open <name>                    Open a project
				  projects rename <old name> <new name>   Rename a project
				  projects delete <name>                  Delete a project (asks first)
				  versions list                           List the open project's model versions
				  versions restore <number>               Make a version the current one
				  versions delete <number>                Delete a version (asks first)
				  export <number> --format obj|stl --out <file>
				                                          Export a version to an OBJ or STL file
				  generate [--image <file>]... [--describe "<text>"]
				                                          Identify the object, collect photos and generate
				                                          the front, left, right and back views
				  images list                             List the reference images and where they came from
				  images exclude <number>                 Leave an image out of the next build
				  images include <number>                 Put an excluded image back
				  build                                   Build a 3D model from the views (new version)
				  help                                    Show this list
				  exit                                    Quit
				Put names that contain spaces in quotes, for example: projects new "Office chair\"""");
	}

	private static void requireArgs(List<String> args, int count, String usage) {
		if (args.size() != count + 1) {
			throw new IllegalArgumentException("Usage: " + usage + " (put names with spaces in quotes)");
		}
	}

	private static List<String> tokenize(String line) {
		List<String> words = new ArrayList<>();
		Matcher matcher = TOKEN.matcher(line);
		while (matcher.find()) {
			words.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
		}
		return words;
	}

	private String readLine() {
		try {
			return in.readLine();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
