package AIMesher.ai_mesh_generator.services;

import java.io.File;
import java.util.List;

import AIMesher.ai_mesh_generator.model.Identification;

// Adapter target: reverse image search (F04). The identification narrows the search to the right item.
public interface ImageSearch {

	List<SearchResult> search(File image, Identification id);
}
