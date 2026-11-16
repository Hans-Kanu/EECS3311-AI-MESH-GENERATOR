# Stage 2: Changes from the Stage 1 Design

This file records every difference between the Stage 1 design ([Stage1_Report.md](Stage1_Report.md)) and the
implemented system, as required by Section 3 of the Stage 2 instructions. It is the source for Sections 2-4 and 6
of the Stage 2 report. Class and method names refer to `backend/ai-mesh-generator/src/main/java/AIMesher/ai_mesh_generator/`.

## 1. Feature status

| ID | Feature | Status | Summary of the change |
|---|---|---|---|
| F01 | Project library | Implemented | As designed. Saving is now atomic and a damaged `project.json` gives an error instead of a crash. |
| F02 | Flexible input | Implemented | As designed: `ModelAgent.choosePath()` picks one of three `GenerationPath` strategies. |
| F03 | Object identification | Modified | No "Identified as" panel and no confirm/correct step. The result is reported as a progress message. |
| F04 | Reverse image search | Modified | Implemented with Google Cloud Vision web detection, which searches by the picture only (not by keywords). Needs its own key; without one the search is skipped. |
| F05 | Reference source viewer | Modified | Origin labels, source links and exclusion work. Only AI-generated views can be excluded, since only they are sent to Hunyuan 3D. |
| F06 | Image from description only | Modified | All four views are generated in one run. There is no Accept / Regenerate step for the first image. |
| F07 | Missing-angle generation | Modified | Front, left, right and back are always generated; uploaded and web photos are references only. TOP was dropped. |
| F08 | Multi-view mesh generation | Implemented | Started only with the Build button, after review. Network drops while waiting are retried. |
| F09 | Version history | Implemented | As designed. |
| F10 | Export | Implemented | As designed (GLB, OBJ, STL). OBJ and STL keep the shape but not the textures. |
| F11 | 3D preview | Modified | Implemented in the GUI. No simplified preview for very large models. |

No features were added or removed. All eleven Stage 1 features are implemented.

## 2. Feature changes

### F03 Object identification (Modified)

1. **Original (F03, UC03):** The identification appears in an "Identified as" panel (`IdentificationPanel`), where
   the user confirms or corrects it (`AppController.confirmIdentification()`). For a low-confidence result, the user
   is asked for a description and the search is skipped.
2. **Implemented:** `ImageOnlyPath.run()` calls `VisionModel.identify()` and reports
   "Identified: name (category, N% sure)" through `ProgressListener.onProgress()`. The CLI prints it and the GUI
   shows it in the status bar and Activity log. A low-confidence result skips the search and the views are generated
   from the photo (and description, if given) without asking.
3. **What changed and why:** The pipeline runs as one background call (`AppController.generateReferences()`), so it
   never stops halfway to wait for the user. Pausing would need the agent to keep state between two controller calls
   and two extra dialogs in both views. The identification only steers the image prompts, and the user reviews the
   generated views anyway (F05), which is where a wrong identification becomes visible.
4. **User need:** The user still sees what the agent thinks the object is before building. To correct it, they add or
   change the description and generate again; with `ImageAndDescriptionPath` the description guides identification
   and is trusted over an unsure result.
5. **Affected:** UC03 (confirm step removed); SD01; `IdentificationPanel` (not implemented),
   `AppController.submitRequest()` and `confirmIdentification()` (replaced by `generateReferences()`). No pattern
   change.

### F04 Reverse image search (Modified)

1. **Original (F04, UC04):** `ReverseImageSearchAdapter` wraps `ReverseImageSearchApi` behind `ImageSearch`. The search
   may use the identified name as keywords, the image itself, or both, "depending on the search service behind the
   adapter". The agent keeps the photos that `VisionModel.isSameItem()` says show the same item and labels their angle.
2. **Implemented:** `ReverseImageSearchApi.searchByImage(image)` sends the user's photo to Google Cloud Vision's
   `WEB_DETECTION` and returns its `webDetection` reply. `ReverseImageSearchAdapter.search()` turns that into
   `SearchResult`s: look-alike pictures first (they can show other angles), then pictures containing part of the
   photo, with the page they were found on. Exact copies of the photo are left out, duplicates are merged and only
   http(s) addresses are kept. `ImageOnlyPath.collectWebPhotos()` then downloads up to 8 results
   (`SearchResult.download()`, with a size and time limit), keeps matches with `isSameItem()` and labels them with
   `labelAngle()`. With fewer than 2 matches, or when the search fails, it says why and generates the views from the
   user's input.
3. **What changed and why:** The search is by picture only; the identification isn't sent, because Cloud Vision
   doesn't take text. It is a true reverse image search that accepts the uploaded file itself (most image search APIs
   need a public image URL), and its first 1,000 searches a month are free. It needs its own key
   (`GOOGLE_VISION_API_KEY`, bound by the new `VisionProperties`), because Cloud Vision refuses Gemini keys from AI
   Studio. `searchByImage()` returns the parsed JSON (`JsonNode`) instead of a `String`.
4. **User need:** As designed: photos of the same item from other angles are collected, checked and shown as web
   references with their source page (F05). Without a key the app still works and says the web wasn't searched.
5. **Affected:** UC04, SD01; `ReverseImageSearchAdapter`, `ReverseImageSearchApi`, `VisionProperties` (new). Adapter
   pattern as designed.

### F05 Reference source viewer (Modified)

1. **Original (F05, UC07):** A Sources tab shows each photo with its origin, a source link and an Exclude checkbox.
2. **Implemented:** GUI: `SourcesPanel.showSources()` shows a card per image with a thumbnail, its role, its origin
   and, for web photos, a link that opens in the browser; double-clicking opens the image. CLI: `images list`,
   `images exclude <n>`, `images include <n>`. Excluding goes through `AppController.excludeImage()` and
   `ReferenceImageSet.exclude()`, which refuses to lose the last generated front view or the last generated view.
   Choices are saved.
3. **What changed and why:** Only AI-generated views are sent to Hunyuan 3D (see F07), so only they can be excluded.
   `ReferenceImageSet.exclude()` rejects an uploaded or web photo ("references only, so there is nothing to leave
   out"), and only a generated front view counts as the front view. Before this, an uploaded photo labelled "front"
   made the generated front look optional, and the build then failed. In the GUI only generated views have a "Use in
   build" box. `AppController.includeImage()` was added so an exclusion can be undone.
4. **User need:** The user still sees where every image came from and controls which views build the model.
5. **Affected:** UC07 (its alternative flows now refer to generated views), SD06; `SourcesPanel`,
   `AppController.includeImage()`, `ReferenceImageSet.exclude()`, `include()`, `hasFrontView()`.

### F06 Reference image generation from a description (Modified)

1. **Original (F06, UC05):** The agent writes an image prompt, generates one image, and shows it with Accept and
   Regenerate buttons (`acceptGeneratedImage()`, `regenerateImage()`). A blocked request asks the user to reword.
2. **Implemented:** `DescriptionOnlyPath.run()` gets a prompt from `VisionModel.writeImagePrompt()`, generates the
   front view with `ImageGenerator.generateFromPrompt()`, then the left, right and back views from the front, all in
   one run. A blocked request stops the run with Gemini's reason, shown by `onError()`.
3. **Why:** Same reason as F03: one uninterrupted run. Reviewing all four views together (F05) replaces accepting the
   first one.
4. **User need:** To regenerate, the user edits the description and clicks Generate again; built versions keep their
   own images.
5. **Affected:** UC05, SD02; `AppController.acceptGeneratedImage()` and `regenerateImage()` (not implemented),
   `ModelAgent.generateFromDescription()` (moved into `DescriptionOnlyPath.run()`).

### F07 Angle generation (Modified)

1. **Original (F07, UC06):** The agent finds which angles (front, back, left, right, top) are missing from the
   uploaded and web photos and generates only those.
2. **Implemented:** `GenerationPath.generateViews()` always generates FRONT, then LEFT, RIGHT and BACK, as separate
   image requests. The front is made from all references; each later view is made from the references plus the
   generated front. Side views are described as a turn from the front image in terms of its edges ("the side at the
   right edge of the front image now faces the camera"), since "the object's left" was often read as the viewer's
   left. `VisionModel.isConsistent(view, refs, angle)` checks both the object and the viewpoint, and a view is
   generated again once if either doesn't match (`MAX_VIEW_ATTEMPTS = 2`). Only these four AI views go to Hunyuan 3D (`ModelAgent.meshViews()`); uploaded and web
   photos are references only.
3. **Why:** Web and uploaded photos differ in lighting, background, scale and product variant, and are rarely exact
   front or side shots. A generated set is consistent and always covers all four views Hunyuan 3D's multi-view input
   takes. Hunyuan 3D has no top view input, so `Angle.TOP` was removed. Two attempts instead of three
   (`maxRetries = 3`) limits paid image generations.
4. **User need:** The user still gets a complete, consistent multi-view set. Each run costs at least four image
   generations.
5. **Affected:** UC06, SD01, SD02; `GenerationPath.generateViews()` (replaces `fillAndReview()`), `Angle`,
   `ImageGenerator.generateAngle(refs, subject, angle)` (gained `subject`), `ModelAgent.meshViews()`. Strategy is
   unchanged; `generateViews()` is the shared step every strategy ends with.

### F08 Multi-view mesh generation (Implemented, details changed)

- **Trigger:** only the Build button (GUI) or `build` (CLI), never automatically, so the user always reviews first
  (UC08 precondition).
- **Hunyuan request:** `Hunyuan3DAdapter.submitJob()` scales each view to at most 1024 px and sends the front as
  `ImageBase64` and the others as `MultiViewImages` to the Pro job API; the result zip's GLB is downloaded.
- **Reliability (new):** `ModelAgent.surviveNetworkErrors()` retries a status check or download up to 3 times, since
  the paid job keeps running at Tencent. The model is copied into the project and the downloaded temp file deleted.

### F11 3D preview (Modified)

1. **Original (F11, UC11):** `ModelViewerPanel.loadModel(modelFile)` renders the model; a large model gets a
   simplified preview; a model that can't be shown shows the version's details instead.
2. **Implemented:** `ModelViewerPanel.loadModel(ModelVersion)` reads the GLB with `GlbReader` on a background thread,
   converts it with `PreviewMesh` (centre, scale, glTF to JavaFX axes) and shows it in a JavaFX 3D `SubScene` with
   the model's texture. Drag turns it, scroll zooms, Reset view restores the start angle. A missing or unreadable
   model calls `showDetails(version, problem)`.
3. **What changed and why:** `loadModel()` takes the version, not the file, so it can show the version's details on
   failure. There is no simplified preview: a real 500,000-triangle, 24 MB Hunyuan model loads in a few seconds.
4. **Affected:** UC11, SD03, SD06; `ModelViewerPanel`, `PreviewMesh`, `GlbReader`/`GlbModel` (made public and given
   the texture).

## 3. Design patterns

All Stage 1 patterns are implemented. None was replaced; the changes are in participants and method signatures.

| Pattern | Stage 1 | Implemented | Change |
|---|---|---|---|
| Facade | `ModelAgent` with `identifyObject()`, `collectReferences()`, `generateFromDescription()`, `fillMissingAngles()`, `buildMesh()` | `ModelAgent.startGeneration(request) : ReferenceImageSet` and `buildMesh(set) : File` | The step methods moved into the strategies, so the facade's public API is two calls. The controller still sees nothing behind it. |
| Strategy | `GenerationPath.run(agent, request, set)`, shared `fillAndReview()` | `GenerationPath.run(request, progress)`, shared `generateViews()`; `ImageAndDescriptionPath` extends `ImageOnlyPath` | Strategies hold the services they need instead of calling back into the agent. |
| Observer | `ProgressListener`; `MainWindow`, `CliApp` | Same | `ModelAgent` forwards path updates through an inner `Broadcaster` that remembers the stage, so failures are reported at the right stage. `MainWindow` moves updates to the JavaFX thread. |
| Adapter | `GeminiVisionAdapter`, `GeminiImageAdapter`, `ReverseImageSearchAdapter`, `Hunyuan3DAdapter` | All four | `GeminiVisionAdapter` and `GeminiImageAdapter` share one `GeminiApi` client; `ReverseImageSearchAdapter` turns Cloud Vision's reply into `SearchResult`s; `Hunyuan3DAdapter` turns Tencent's job states into `JobStatus`. |
| Factory | `ExporterFactory.create(format)` | Same | `ObjExporter` and `StlExporter` share one `GlbReader`; `GlbExporter` copies the file. |
| Prototype | `ReferenceImageSet.copy()` | Same, plus `ReferenceImage.copy()` | Each version keeps a deep copy of the set it was built from. |
| MVC | Views, `AppController`, model | Same | GUI is JavaFX: `GuiApp` starts it; the panels pass user actions to `MainWindow`, the only GUI class that calls the controller. |

## 4. Class and method changes

Structure: **Original design -> Implemented change -> Reason -> Impact.**

| Original design | Implemented change | Reason | Impact |
|---|---|---|---|
| `AppController.submitRequest(request)`, `confirmIdentification()`, `acceptGeneratedImage()`, `regenerateImage()` | `generateReferences(images, description) : ReferenceImageSet` | One uninterrupted run (F03, F06) | Views make one call; the result is copied into the project folder and saved |
| `AppController.buildModel()` | Returns the new `ModelVersion` | The views show and select it | GUI previews it right away |
| (none) | `AppController.includeImage(id)`, `removeProgressListener()`, `getCurrentProject()` | Undo an exclusion; detach a closed view; views read the open project | Used by both views |
| `ModelAgent.startGeneration(request, set)` | `startGeneration(request) : ReferenceImageSet` | The path builds a new set; the controller decides where it is stored | The project keeps its old set if generation fails |
| `ModelAgent.maxRetries = 3` | `GenerationPath.MAX_VIEW_ATTEMPTS = 2`; `ModelAgent.MAX_NETWORK_ATTEMPTS = 3` | Each retry is a paid image generation; network retries protect paid mesh jobs | See F07, F08 |
| `GenerationPath.run(agent, request, set)`, `fillAndReview(agent, set)` | `run(request, progress)`, `generateViews(set, refs, subject, frontPrompt, progress)` | Strategies use the services directly; review happens after the run | SD01, SD02 messages change |
| `Angle` with `TOP` | FRONT, BACK, LEFT, RIGHT | Hunyuan 3D takes no top view | F07 |
| `ImageGenerator.generateAngle(refs, angle)` | `generateAngle(refs, subject, angle)` | The prompt names the object so every view shows the same item | `GeminiImageAdapter` |
| `VisionModel.isConsistent(view, refs)` | `isConsistent(view, refs, angle)` | A copy of the front passed a check that only compared the object | Side views are checked for their viewpoint |
| `GeminiApi.generateContent(prompt, images)` | Plus `generateJson()` and `generateImage()`; retries busy/temporary errors with backoff; 120 s timeout | Structured answers and images need different requests; Gemini models are often busy | Clear "busy" vs "no connection" messages |
| `Hunyuan3DApi.createJob()`, `queryJob()` | `submitProJob()`, `queryProJob()`, `download()`, with TC3-HMAC-SHA256 signing | Matches Tencent's Pro job API | `Hunyuan3DAdapter` maps `JobStatus` |
| `ReverseImageSearchApi.searchByImage(image) : String` | `searchByImage(image) : JsonNode`, calling Cloud Vision `WEB_DETECTION`; new `VisionProperties` for its key | Cloud Vision takes the picture itself; the adapter reads the parsed reply | F04 needs `GOOGLE_VISION_API_KEY` |
| `ProjectRepository.save/load/...` | Plus `importFile(project, file, folder)`; atomic save; damaged files give a clear error | Images and models live inside the project folder so they survive renames; no half-written saves | F01, F09 |
| `Project`, `ReferenceImageSet`, `ReferenceImage` | Plus `Project.setReferenceSet()`, `addSavedVersion()`; `ReferenceImageSet.include()`, `getImages()`; `ReferenceImage.copy()` | Loading saved projects, undoing exclusions, deep copies | Model classes |
| `IdentificationPanel` | Not implemented | See F03 | GUI has five panels |
| `InputPanel.getRequest()`, `showPath(message)` | `getImages()`, `getDescription()`; the path hint updates as the user types | `MainWindow` passes images and text to the controller | F02 |
| `SourcesPanel.onExcludeToggled(imageId)` | A callback with `(imageId, included)` | Ticking and unticking go through one handler | F05 |
| `HistoryPanel.showVersions(versions)`, `ProjectPanel.refreshProjects(names)` | Also take the current version / open project to highlight | The lists show which item is current | F01, F09 |
| `ModelViewerPanel.loadModel(modelFile)` | `loadModel(version)`, `showDetails(version, problem)`, `clear(text)` | See F11 | F11 |
| (none) | New: `GuiApp` (starts JavaFX after Spring builds the controller), `Dialogs` (shared pop-ups), `PreviewMesh` | GUI start-up and preview | Presentation layer |
| (none) | New: `GeminiProperties`, `HunyuanProperties`, `GlbReader`, `GlbModel` | Config from `.env`; one GLB parser for export and preview | Services and export layers |

## 5. Diagram updates

The UMLet sources in `stageDocuments/diagrams/` now match the code:

- **Class diagram:** every row in Section 4.
  - `IdentificationPanel` was removed and `GuiApp` takes its place.
  - Added `PreviewMesh`, `GlbReader`, `GlbModel` and the three `*Properties` records.
  - `ImageAndDescriptionPath` now extends `ImageOnlyPath`.
  - The strategies no longer depend on `ModelAgent`.
  - `TOP` was removed from `Angle`.
- **Use case diagram:** the search actor is now Google Cloud Vision, and UC06 is renamed "Generate the Four Views".
  The use case descriptions still need these changes in the report:
  - UC03: no confirm step.
  - UC04: search by picture only.
  - UC05: no Accept / Regenerate.
  - UC06: always four views.
  - UC07: only generated views can be excluded.
  - UC11: `loadModel(version)`.
- **Sequence diagrams:**
  - SD01: identification without confirmation, search by picture through Cloud Vision, four generated views, and the
    images copied into the project.
  - SD02: no `acceptGeneratedImage()`. The front view comes from `writeImagePrompt()` and `generateFromPrompt()`.
  - SD03: `meshViews()` checks for a generated front view, and polling uses `queryProJob()`. `buildModel()` returns the
    version, which `HistoryPanel` selects and previews.
  - SD04: errors go back to `MainWindow` as exceptions, and the exporter reads the GLB with `GlbReader`.
  - SD06: excluding is refused for a photo or the last generated front view. Restoring uses `restoreVersion()`, which
    returns the version, and `loadModel(version)` uses `GlbReader` and `PreviewMesh`.
  - SD05 is unchanged.

## 6. Weekly commit summary

Meaningful code commits (implementation or test code) per reporting period, in Toronto time. Repository:
<https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR>.

| Period | Commits |
|---|---|
| 1 (Oct 20-26) | [3e12e80](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/3e12e80) Spring Boot backend with Gemini config; [b0f2b84](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/b0f2b84) domain model; [fc7f6cf](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/fc7f6cf) project storage and controller; [5d280fe](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/5d280fe) CLI project management; [e0a4f05](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/e0a4f05) Gemini API client |
| 2 (Oct 27-Nov 2) | [6bf00f2](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/6bf00f2) AI service interfaces; [e35d5ad](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/e35d5ad) Gemini retries; [17801fe](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/17801fe) OBJ/STL export and exporter factory; [d3e8149](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/d3e8149) version restore, delete and export; [dd33432](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/dd33432) Gemini vision adapter |
| 3 (Nov 3-9) | [160e2b4](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/160e2b4) generation strategies; [c36ca9e](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/c36ca9e) Gemini image adapter; [03fb890](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/03fb890) ModelAgent facade; [a37d1d1](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/a37d1d1) controller generation and build; [9250d97](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/9250d97) CLI generate/images/build; [2332f95](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/2332f95) Hunyuan 3D adapter |

After the reporting periods (Nov 14-15): [9af24c3](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/9af24c3)
clearer Gemini errors; [da0fdf0](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/da0fdf0) atomic
saves and damaged-file errors; [b81d0ad](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/b81d0ad)
network-drop retries; [f4084c5](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/f4084c5) GLB export;
[f8c5015](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/f8c5015) temp file cleanup;
[5fd189c](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/5fd189c) JavaFX GUI and 3D preview;
[f8b8428](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/f8b8428) only generated views can be
excluded; [ac050d9](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/ac050d9) reverse image search
through Cloud Vision; [7f1c90f](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/7f1c90f) side views generated as a turn from the front and checked for
their viewpoint.

Documentation and sample-data commits (not code): [6a96a88](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/6a96a88) README and change record; [995a2a9](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/995a2a9) updated UML diagrams; [2b02648](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/2b02648) this report; [43a5bed](https://github.com/Hans-Kanu/EECS3311-AI-MESH-GENERATOR/commit/43a5bed) sample photos and descriptions.
