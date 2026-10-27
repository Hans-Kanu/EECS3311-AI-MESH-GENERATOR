# EECS3311-AI-MESH-GENERATOR

An AI agent that turns a reference image and/or a text description into a 3D mesh. It identifies the object, collects or generates views from several angles, lets the user review them, and then builds the mesh. The design is in [stageDocuments/Stage1_Report.md](stageDocuments/Stage1_Report.md).

## Prerequisites

- JDK 21
- No Maven install needed: the project ships with the Maven wrapper (`mvnw` / `mvnw.cmd`).
- A Gemini API key from [Google AI Studio](https://aistudio.google.com/apikey).

## Local configuration (API keys)

Keys are never committed. They live in a local `.env` file, which is git-ignored.

```sh
cd backend/ai-mesh-generator
cp .env.example .env        # Windows: copy .env.example .env
```

Open `.env` and replace `YOUR_LOCAL_API_KEY_HERE` with your key. Environment variables with the same names (`GEMINI_API_KEY`, `GEMINI_MODEL`) override the file. To keep the file somewhere else, set `ENV_FILE` to its path.

## Build, test and run

From `backend/ai-mesh-generator`:

```sh
./mvnw test                 # Windows: mvnw.cmd test
./mvnw spring-boot:run
```

In IntelliJ, set the run configuration's working directory to `backend/ai-mesh-generator` so the app finds `.env`.
