package AIMesher.ai_mesh_generator.agent;

// The step of the generation pipeline that a progress update refers to.
public enum Stage {
	IDENTIFYING,
	SEARCHING,
	GENERATING_IMAGE,
	GENERATING_ANGLES,
	BUILDING_MESH,
	DONE
}
