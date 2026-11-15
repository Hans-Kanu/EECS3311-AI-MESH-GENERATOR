# AI Mesh Generator

EECS3311 (Fall 2026) course project. A desktop app with an AI agent that turns a photo of an object, a text
description, or both into a 3D model. The agent identifies the object, generates a consistent set of front, left,
right and back views, lets you review them, then sends them to a 3D generation service and keeps every model as a
version you can preview and export.

- **Agent "brain":** a Gemini model identifies the object, writes image prompts and checks that generated views
  match the references.
- **Image generation:** a Gemini image model draws the four views.
- **3D generation:** Tencent Hunyuan 3D builds a textured mesh (GLB) from the views.

Both a **GUI** (JavaFX) and a **CLI** are included. They call the same controller, so they do the same things.

## Features

| ID | Feature | Status |
|---|---|---|
| F01 | Project library (create, open, rename, delete) | Implemented |
| F02 | Flexible input: photo, description, or both | Implemented |
| F03 | Object identification | Implemented (shown in the progress log; no confirm step) |
| F04 | Reverse image search | Not implemented: no search service is connected yet |
| F05 | Reference source viewer, with excluding images | Implemented (no web photos until F04 exists) |
| F06 | Reference image from a description only | Implemented (no Accept / Regenerate step) |
| F07 | Angle generation (front, left, right, back) | Implemented, changed: all four views are always generated |
| F08 | Multi-view 3D mesh generation | Implemented |
| F09 | Version history (browse, restore, delete) | Implemented |
| F10 | Export to GLB, OBJ or STL | Implemented |
| F11 | 3D preview (turn, zoom, reset) | Implemented in the GUI |

Every difference from the Stage 1 design is explained in
[stageDocuments/Stage2_Changes.md](stageDocuments/Stage2_Changes.md).

## Prerequisites

- **Java 21** (JDK). Maven doesn't need to be installed: the project includes the Maven wrapper (`mvnw`), which
  downloads Maven and every dependency (Spring Boot, JavaFX) the first time it runs.
- An internet connection.
- **A Gemini API key** from [Google AI Studio](https://aistudio.google.com/apikey). The image model
  (`gemini-2.5-flash-image`) needs a key with **billing turned on**; on the free tier its quota is 0.
- **A Tencent Cloud API key pair** for Hunyuan 3D, from the international console (console.tencentcloud.com). Use
  a CAM sub-user with only the `QcloudHunYuanFullAccess` and `QcloudAI3DFullAccess` policies, not your main
  account's keys.

The build includes the JavaFX libraries for the operating system it is built on, so build the jar on the kind of
computer you run it on.

## Setup: API keys

Keys stay in a local `.env` file, which is git-ignored and never committed.

```powershell
cd backend\ai-mesh-generator
copy .env.example .env      # Git Bash / macOS / Linux: cp .env.example .env
```

Open `.env` and replace the placeholders:

| Variable | Required | Meaning |
|---|---|---|
| `GEMINI_API_KEY` | Yes | Your Gemini key |
| `GEMINI_MODEL` | No | Reasoning model, default `gemini-3.5-flash` |
| `GEMINI_IMAGE_MODEL` | No | Image model, default `gemini-2.5-flash-image` |
| `HUNYUAN_SECRET_ID`, `HUNYUAN_SECRET_KEY` | Yes, to build models | Tencent Cloud key pair |
| `HUNYUAN_REGION` | No | Default `ap-singapore` |
| `HUNYUAN_ENDPOINT`, `HUNYUAN_VERSION` | No | Only for the China site; the defaults are for the international site |
| `PROJECTS_DIR` | No | Where projects are saved, default `projects/` |

Without the Hunyuan keys the app still runs and generates reference images; only building the 3D model is
unavailable.

## Run

All commands run from `backend/ai-mesh-generator`. In PowerShell use `.\mvnw.cmd`; in Git Bash, macOS or Linux
use `./mvnw`.

**GUI** (the default):

```powershell
.\mvnw.cmd spring-boot:run
```

or build once and run the jar:

```powershell
.\mvnw.cmd package -DskipTests
java -jar target\ai-mesh-generator-0.0.1-SNAPSHOT.jar
```

**CLI:** the same jar with the `cli` profile:

```powershell
java -jar target\ai-mesh-generator-0.0.1-SNAPSHOT.jar --spring.profiles.active=cli
```

## Example usage

### GUI

1. **Projects** (left): click **New** and name the project, e.g. `Stool`.
2. **1. Input**: drop a photo on the list or click **Add photos...**, and/or type a description such as
   `a red wooden stool with three legs`. The hint under the inputs says which path the agent will take. Click
   **Generate reference images**. Progress appears in the status bar and the **Activity** log.
3. **2. Review the reference images**: check the four generated views. Untick **Use in build** on any view you
   don't want sent. Double-click a picture to open it full size.
4. Click **Build 3D model**. Building takes a few minutes and uses Tencent Cloud credits.
5. **3. 3D preview** shows the new version: drag to turn, scroll to zoom, **Reset view** to go back.
6. **Versions**: select a version to preview it, **Make current**, **Delete**, or **Export...** to GLB (keeps
   colours and textures), OBJ or STL.

### CLI

Type `help` for the full list of commands. Put names with spaces in quotes.

```text
> projects new Stool
Created and opened project "Stool".
> generate --image C:\photos\stool.jpg --describe "red wooden stool"
[IDENTIFYING] Identifying the object...
...
Generated 5 reference images:
  1. your image (reference only)
  2. front view (AI-generated, used for the model)
  3. left view (AI-generated, used for the model)
  4. right view (AI-generated, used for the model)
  5. back view (AI-generated, used for the model)
Review them with images exclude/include, then run build.
> images exclude 5
Image 5 will be left out of the next build.
> build
[BUILDING_MESH] Sending 3 views to the 3D generator...
...
Saved the model as version 1.
> versions list
  v1  2026-11-14 15:49  (current)
> export 1 --format glb --out C:\models\stool.glb
Exported version 1 to C:\models\stool.glb.
> exit
```

(Each image line is followed by the image's file path, left out here.)

## Tests

```powershell
.\mvnw.cmd test
```

The tests use fake AI services and never call Gemini or Tencent Cloud. Four live Gemini tests are skipped unless
run with `-Dgemini.live=true`; they use the real key in `.env`.

## Project layout

```text
backend/ai-mesh-generator/src/main/java/AIMesher/ai_mesh_generator/
  view/        GUI (MainWindow and its panels, GuiApp) and CLI (CliApp)
  controller/  AppController, shared by both views
  agent/       ModelAgent (Facade), GenerationPath strategies, ProgressListener (Observer)
  services/    AI service interfaces, Gemini and Hunyuan adapters and API clients, ProjectRepository
  model/       Project, ReferenceImageSet, ReferenceImage, ModelVersion, GenerationRequest
  exporter/    ExporterFactory and the GLB, OBJ and STL exporters
stageDocuments/  Stage 1 report, UML diagrams, Stage 2 change record
```

## Known limitations

- Reverse image search (F04) has no service connected, so web photos never appear and the views are generated
  from your photo and/or description.
- OBJ and STL exports keep the shape (and texture coordinates for OBJ) but not the textures; use GLB for those.
- Each generation run makes at least four image-generation requests, and each build is a paid Hunyuan 3D job.
