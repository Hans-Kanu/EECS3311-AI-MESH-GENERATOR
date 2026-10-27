package AIMesher.ai_mesh_generator.agent;

// Observer: the views implement this to receive updates from the agent during long jobs,
// without the agent knowing whether it is talking to the GUI or the CLI.
public interface ProgressListener {

	void onProgress(Stage stage, String message);

	void onError(Stage stage, String error);
}
