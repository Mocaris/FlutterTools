# FlutterTools

An IntelliJ IDEA / Android Studio plugin for Flutter asset generation, dependency checks and pub.dev package search. Targets IntelliJ Platform 2023.1–2026 and requires the bundled YAML plugin.

Current version: **2.0.1**. This release shares successful pub package-info responses, avoids periodic parsing in the hidden Package Search page and reuses SDK/output-path work. Source and test packages now follow feature ownership; plugin action IDs and persisted settings names remain unchanged. See [release notes](changeNotes.md).

## Tool window

The **Tools > FlutterTools** menu groups navigation, package commands and settings:

- **Open Tool Window**, **Search Packages…** and **Workspace / Melos** open the existing window; Search Packages focuses the query field, and Workspace / Melos opens the Melos tab.
- **Generate Resources**, **Check Package Updates** and **Run Pub Get** display their target package. The target comes from the current file, then the window's package selection, then the only discovered package. Commands are disabled when no target is available or their operation is running. Generation and update checks open the corresponding page and preserve Save / Discard / Cancel when switching packages with edited asset settings.
- **Run Pub Get** saves the selected package and workspace pubspec/override documents, then uses the existing SDK resolver and IDE Run window. Other unsaved files are left untouched.
- **Project Settings…** opens this project's overrides; **Settings…** opens global defaults.

Open **View > Tool Windows > FlutterTools** or **Tools > FlutterTools > Open Tool Window**. The window is docked on the right with a dedicated light/dark SVG icon and lets you select the Flutter/Dart package to work with.

The package and language selectors use two aligned rows: the package selector fills the available width and the language selector stays compact. Narrow windows wrap the native page tabs so every page remains accessible. Package paths retain their full-path tooltip.

Use the **Language** selector at the top of the window to choose **Follow system**, **中文** or **English**. Follow system uses the current IntelliJ IDEA / Android Studio interface language: Chinese UI locales use Simplified Chinese; English, unsupported or unspecified UI locales use English. It does not use the operating system locale. The same selector is available in **Settings > Other Settings > FlutterTools** and stays synchronized between both entrances. The selection is saved globally for the plugin and takes effect immediately in open FlutterTools windows, action menus and editor hints without discarding unsaved configuration edits. UI labels, dialogs, statuses, notifications and plugin validation errors are localized; file paths, YAML keys, generated Dart identifiers and external error details retain their original values.

- **Assets**: view resource declarations from pubspec, open pubspec to edit them, configure exclusions/entry/generation directory/class name, enable file extensions or automatic generation, and save/reload settings or generate resources. **Resource previews** shows image thumbnails (including SVG and WebP) or file-type icons in generated resources, Dart path strings and resource completion lists; previews default on and can be configured globally or overridden in **Project settings**.
- **Packages**: refresh versions and inspect declared constraints, resolved versions, latest versions and check status. Click a public package name to open pub.dev. Use the **Project settings** tab to override editor and dependency preferences for this project.
- **Package Search**: search pub.dev, compare selected and latest release SDK requirements, choose a historical version, and add or update a dependency in the selected package.
- **Project settings**: choose **Use global settings for this project**, or disable it and apply independent editor/dependency preferences. These overrides are saved in the project workspace; language always remains global.

Configuration edits preserve unrelated YAML, comments and unchanged configuration values. Switching packages with unsaved form changes offers Save / Discard / Cancel. External changes require a reload before overwriting an edited form. Saving configuration is an IDE command and supports Undo.

The Assets panel groups Resources, Generation settings and Status in a compact, top-aligned form. Resource declarations use a bordered, theme-aware six-row read-only viewport, with **Edit pubspec.yaml** beside the heading. Configuration source, output fields, exclusions and options stay together; Save / Reload and Generate now occupy separate rows so controls remain visible in narrow windows. Long lists scroll within their surface, and short windows scroll the form vertically.

The Packages panel uses a bordered dependency table with padded rows and a compact status area. Columns fill wide windows; narrow windows scroll the table horizontally while preserving readable column headers. Hover a cell to read its complete value and update/SDK status.

Package Search separates the query, result list and dependency settings. Drag the divider to resize the result and settings areas; the settings scroll independently and group the target package, dependency type, Pub Get option, documentation and Add/Update action before SDK information. Empty selection uses a neutral prompt; compatibility recommendations and dependency warnings appear after selecting a result. Search, retry, pagination, release selection and dependency writes keep their existing behavior.

Search results use compact three-line rows for package/version, a one-line description and scores/publisher. Truncated rows stay inside the list rather than expanding into a floating overlay. Select a result to read its complete description, publisher and selected/latest Dart/Flutter requirements in **Selected package details** below the dependency actions; long details wrap and scroll within their bordered surface. The version selector stays on each result row.

## IDE settings

Open **Settings > Other Settings > FlutterTools**, or **Tools > FlutterTools > Open settings**, to configure application-wide defaults and the global plugin language. Changes take effect on Apply for all projects using global settings. New projects follow global defaults; previously saved custom project preferences remain project overrides. Edit overrides in the Tool Window’s **Project settings** tab. Global preferences include:

- Language: follow the IDE interface language, Chinese or English, synchronized with the Tool Window language selector. Unmatched IDE languages default to English.
- Resource image previews (on by default), covering gutters and completion icons.
- Automatic resource path suggestions (on), with a delay after typing stops (default 100 ms; range 0–5000 ms). Manual completion remains available when automatic suggestions are off.
- Dependency version hints and update warnings (on), independently of automatic version checks (on).
- Automatic dependency check delay after edits (default 1000 ms; range 0–10000 ms). Manual refresh starts immediately.
- Shared pub metadata cache lifetime (default 720 minutes; range 1–1440 minutes) and whether recommendations include prerelease versions (off).

The edit delay and cache lifetime are separate: the former waits for edits to settle, while the latter controls cached metadata freshness during periodic checks. Resource generation options remain in each package's YAML configuration.

## Asset generation

Use **Tools > FlutterTools > Generate Resources** or **Generate now** in the tool window.

Resource paths always come from the selected package's `pubspec.yaml`: `flutter.assets`, `flutter.fonts[*].fonts[*].asset` and `flutter.shaders`. The resource list in the window is read-only; use **Edit pubspec.yaml** to change declarations.

For generator settings, `flutter_tools.yaml` takes precedence over `pubspec.yaml`. An invalid independent configuration is reported, not silently replaced by a fallback. If neither file contains resource configuration, the tool window creates `flutter_tools.yaml` on first save.

New configuration files use two-space block mappings and multi-line exclusion lists, with YAML-safe string escaping. Project startup detects each package's configuration even with automatic resource watching disabled. Selecting a Package applies its settings to the resource form; external changes reload automatically when the form has no unsaved edits.

```yaml
flutter_tools:
  assets_sync:
    excluded:
      - '.DS_Store'
      - '*.tmp'
      - assets/private/
    out_path: lib/r.dart
    generate_dir: lib/generate
    out_class: R
    out_extension: false
    watch: false
```

Declare resources through Flutter's configuration, for example:

```yaml
# pubspec.yaml
name: your_app
flutter:
  assets:
    - assets/images/
    - assets/images/icons/
  fonts:
    - family: Roboto
      fonts:
        - asset: assets/fonts/Roboto.ttf
  shaders:
    - shaders/effect.frag
```

Directories include only their direct files, as in Flutter; ordinary subdirectories require their own entries. Explicit file declarations retain their exact logical keys. Declaring a base image such as `assets/images/logo.png` generates that logical key, while Flutter selects its `2.0x`/`3.0x` variants. A missing base file is supported when explicitly declared and same-name resolution variants exist, matching Flutter 3.35.3 scanning without an extension allowlist. This generates a logical key; direct `rootBundle.load` still requires a concrete bundled file key. Shader resources contain file paths. Font resources are grouped by family and preserve each file path, weight and style. Conditional asset entries (`path`, flavors/platforms) are included as declared; actual inclusion is determined by the Flutter build.

The default entry is `lib/r.dart`. The main project uses class `R`; other packages derive their default class name from `pubspec.name`. Three part files use the package-relative `generate_dir`, which defaults to `lib/generate/` and can be changed in Assets:

- `lib/generate/assets.g.dart`: resource objects from `flutter.assets`, ordered by logical path.
- `lib/generate/fonts.g.dart`: font families from `flutter.fonts`, ordered by family, then weight, style and path.
- `lib/generate/shaders.g.dart`: resource objects from `flutter.shaders`, ordered by logical path.

Import the entry file and access resource paths and metadata:

```dart
import 'package:your_app/r.dart';

Image.asset(R.assets.images.logo.path);
FragmentProgram.fromAsset(R.shaders.shadersEffect.path);

R.assets.images.logo.meta.package;       // pubspec.name: "your_app"
R.assets.images.logo.meta.path;          // "assets/images/logo.png"
R.assets.images.logo.meta.kind;          // RResourceKind.assets
R.assets.images.logo.meta.sourcePackage; // null, or "foo" for packages/foo/...

final face = R.fonts.roboto.w400;
TextStyle(
  fontFamily: face.family,
  fontWeight: face.fontWeight,
  fontStyle: face.fontStyle,
);
rootBundle.load(face.path);
R.fonts.roboto.family;  // "Roboto"
R.fonts.roboto.package; // "your_app"
R.fonts.roboto.faces;   // immutable list of every declared face
face.meta.package;     // "your_app"
```

Metadata `package` always comes from the selected pubspec's `name`; a missing or invalid name stops generation. `sourcePackage` identifies an explicitly referenced `packages/<name>/...` source without replacing the declaring package. Metadata does not automatically change Flutter's resource loading keys or `package:` arguments. Paths already starting with `packages/<name>/` retain that full key.

Asset access follows directory nesting. The leading `assets/` directory is represented by `R.assets`, and each subsequent directory becomes a property: `assets/images/tab/ic_tab_home.png` becomes `R.assets.images.tab.icTabHome`, while `assets/images/home/bg_home.png` becomes `R.assets.images.home.bgHome`. Files declared as ordinary assets under `assets/fonts/` and `assets/anim/` use `R.assets.fonts` and `R.assets.anim`. Flutter font-family declarations still use `R.fonts`. Other roots retain their names, such as `resources/images/logo.png` becoming `R.assets.resources.images.logo`. Directory declarations remain shallow. Files with the same basename in different directories keep separate fields; extension suffixes resolve conflicts within one directory, including conflicts with directory properties. Update existing callers from flattened field names to these nested properties after regeneration.

Fonts with the same family are merged. Missing weight/style defaults to `FontWeight.w400` / `FontStyle.normal`; weights must be 100 through 900 in increments of 100, and styles must be normal or italic. The original family string is preserved. A face's family, weight and style remain distinct even when multiple declarations use the same file. Shortcut fields use `w400`, `w700`, `w400Italic`, etc. If several files share a weight/style, their path-based names are appended, for example `w400FontsLatin`. Conflicting resource fields automatically include extensions; `out_extension` includes them for every field. Names that remain identical after normalization stop generation. Exclusions apply to individual font paths, removing empty families.

Resource objects and metadata are immutable. Public support types use the configured class name as a prefix (`RResource`, `RResourceMeta`, `RResourceKind`, `RFontFace`) to distinguish generated libraries with different class names. Flutter painting types are imported only when at least one font face is generated; assets/shaders-only output remains usable without Flutter.

Empty sections still generate empty groups, so removed declarations clear stale fields. A custom output path changes the entry location; all three part files use `generate_dir` independently of the entry location. Relative `part` and `part of` references are generated for the actual locations. A custom class name also changes the support types and private group class names. Existing string callers must add `.path` (`R.assets.images.logo.path`); font callers now use family groups (`R.fonts.roboto.w400.path`).

Automatic generation handles creation, deletion, renaming and movement, including changes while the IDE was closed. Saved and unsaved pubspec changes update declarations even when generator settings are stored separately. Single image declarations also watch the parent/variant directories. Missing declared resources report errors before replacing output; removing a declaration removes its constant. Package resources declared with `packages/<name>/...` are resolved through `.dart_tool/package_config.json`; run `flutter pub get` first. Conflicting resource fields automatically gain extension suffixes (for example, `logoPng` and `logoSvg`); unaffected field names stay unchanged. Remaining naming collisions fail before replacing output.

The entry must be a `.dart` file and must not use one of the three part filenames. None of the four output files may replace an explicitly declared resource; all four are excluded from directory resource scans. Existing files are replaced only when their first line is the FlutterTools generation marker; unsaved output edits are protected across the whole batch. Conflicts are checked before replacing any file, and replacement failures attempt to restore earlier outputs.

Regeneration migrates matching old parts from the previous generation directory, `lib/gen/` or beside the entry to the configured `generate_dir` and updates the entry's relative references. Old parts are removed only after successful generation and only when they carry the FlutterTools marker and reference the current entry; unrelated files and parts belonging to other entries are preserved.

Package configuration changes refresh external resource locations. Generation reads Flutter resource declarations; use the resource registration action below to add declarations. Exclusions affect generated constants, not Flutter bundling. Legacy `sync_path` is ignored and removed when generator settings are saved. Without custom settings, manual generation uses `lib/r.dart` and the target package's default class name.

## Resource generation in multiple packages

Select the target package using the existing Package dropdown, then configure and generate its resources on the resource page. Each package reads its own `pubspec.yaml` and `flutter_tools.yaml` settings (standalone settings take precedence), with the usual defaults when no generator settings exist. The entry follows that package's `out_path`; the three `.g.dart` files follow its `generate_dir` (default `lib/generate/`).

The package at the IDE project directory defaults to class `R`. Other packages default to a class derived from their `pubspec.yaml` name, for example `feature_login` becomes `FeatureLoginR` and `shared_ui` becomes `SharedUiR`. Related public resource types use the same prefix. An explicit `out_class` always takes precedence, including an existing `out_class: R`; remove that setting to use the automatic default or choose a different name in the resource configuration page.

Legacy part files alongside the entry are removed after successful generation only when they carry the FlutterTools marker and a matching `part of` reference. Unsaved edits are protected; failed replacements or migration restore earlier files.

## Resource previews

With the Dart plugin installed, **Resource previews** is enabled by default and can be switched off in the Assets panel. It defaults on, is saved per IDE project and takes effect immediately without saving generator YAML. Turning it off removes markers, cancels queued/active preview work, clears thumbnail/metadata caches and closes the current popup.

Resource path strings in FlutterTools-generated `assets.g.dart` show a 16-pixel gutter thumbnail on the path’s line. Hover for the logical path, original dimensions and file size; click for a larger preview with an **Open original file** button. PNG, JPEG, GIF (first frame), BMP, SVG and WebP (lossy, lossless, alpha, animation first frame) are supported. Resolution variants and explicit `packages/<name>/...` resources use the generator's path rules. Custom class names and formatted declarations are supported. Declared resource paths in ordinary Dart strings share these thumbnails; other files show their IDE file-type icon and open directly when clicked. Generated shader fields also use file icons.

Only visible gutter icons enqueue image reads. Decoding runs in the background with at most two readers and 16 pending thumbnail jobs. The LRU stores at most 512 thumbnails, each at most 32×32 ARGB pixels (2 MiB of thumbnail pixel data in total); markers retain cache keys rather than bitmaps. A single popup per project decodes at most 960×720 pixels and is released on close. Decoder buffers and metadata are additional to these pixel budgets. Image reads use subsampling and stop on cancellation where supported by the reader.

Raster files over 20 MiB, images over 16 million source pixels, and GIF/BMP/WebP files over 4 million pixels use a fallback icon rather than decoding. The generated-file index is limited to 2 MiB of source text; image resolution metadata is cached for at most four package roots and package_config files over 1 MiB are not retained for previews. Resource/config VFS changes invalidate affected entries, and completed reads batch gutter repaints without restarting project analysis. Full image data is never retained in the thumbnail cache.

SVG uses the IDE renderer with a bounded viewport before bitmap allocation. Common paths, shapes, gradients, clip paths and internal references are supported. SVG input is limited to 256 KiB, 512 elements and 8,192 path/point numbers; external resources, animation, embedded images and expensive filters/masks/patterns use the fallback icon.

## Resource diagnostics

The YAML editor highlights missing asset/font/shader paths, malformed sections, duplicate declarations, invalid font families/weights/styles and unresolved generated-name collisions. Alt/Option+Enter offers choosing an existing resource for a missing path, adding a directory slash, restoring valid font weight/style values, or removing a duplicate while keeping comments. Edits support IDE Undo. Conditional declarations and distinct font faces remain separate; logical images with resolution variants follow the same rules as generation.

## Quick resource registration

Select files or directories in the Project view and choose **FlutterTools > Add to Flutter assets**. Multiple selections must belong to the same Flutter package. The action adds missing entries to that package's `flutter.assets`, preserves comments and other pubspec settings, saves the declaration with IDE Undo support, and generates resources using the package's current settings.

The original action keeps Flutter's shallow directory inclusion rule; nested directories need separate entries. For folders, **FlutterTools > Add to Flutter assets (recursive)** adds the selected folders and their ordinary subfolders in one undoable update. Recursive scanning runs in the background, supports cancellation, skips resolution-variant folders and symbolic links, and stops at nested package roots. Overlapping selections and existing declarations are deduplicated. Selecting a resolution-variant image registers its base logical image path. Existing declarations and direct files already covered by a declared or selected directory are skipped. Package roots, configuration files and generated output files cannot be registered. Fonts and shaders still use their dedicated pubspec sections.

## SDK compatible versions

The Packages table displays the latest published release and the highest release whose SDK requirements are confirmed against the **destination package's** actual Dart/Flutter SDK and environment constraints. Click a version column or the inline update hint to choose an SDK compatible upgrade or an explicit latest-version upgrade, optionally with pub get. Package Search offers the same recommendation with a button that selects the recommended historical release; adding it remains a separate action. The existing main-project SDK comparison remains advisory.

Prereleases follow the Include prereleases setting. Missing actual SDK versions, unsupported requirements, or incomplete release metadata are reported as unconfirmed; the plugin does not infer compatibility from overlapping ranges alone. SDK compatibility does not guarantee that all transitive dependencies resolve: pub get performs that verification. Release requirements reuse the existing pub API response and disk cache.

## Dependency checks and navigation

Dependency hints also show **(unused)** when no Dart `import` or `export` references a runtime dependency in the selected package, including conditional imports and references from tests/tools. This runs independently of version requests, so the hint can appear without a latest version. SDK declarations and `dev_dependencies` are excluded because build/lint tools often run without Dart imports. Hover explains the scope; this is a direct-reference check, not a recommendation to remove native/configuration-only dependencies.

Usage scanning reads the package once in the background, then updates changed Dart files after a 500 ms quiet period, including unsaved editor contents. Nested packages, build/cache directories, symlinks and automatic plugin registrants are excluded. At most two scans run concurrently; each retains only per-file package references, with limits of 10,000 files, 1 MiB per file and 32 MiB for an initial scan. Incomplete scans do not produce unused hints. Changes refresh open pubspec hints without modifying YAML.

While editing a package name under `dependencies` or `dev_dependencies` in `pubspec.yaml`, typing at least two characters automatically opens package completion with names and versions from pub.dev. Ctrl+Space opens the same completion. Select with Enter or Tab: new/empty entries receive `^<version>`, while existing constraints and comments are preserved. Packages already declared in either section are filtered out; structured SDK, Git, path and hosted entries are left to the YAML editor.

Search starts are throttled to one every 200 ms, keeping the newest input and cancelling stale work. Requests run outside editor read actions, reuse the pub search/detail/rating caches and fallback, and load at most ten candidates with two simultaneous detail requests. The completion cache holds at most 32 queries; transient failures retry after 30 seconds. Candidate versions follow the Include prereleases setting. Each theme-aware row shows the existing FlutterTools icon, package name and version, localized release age, and a heart with the like count aligned on the right. Unavailable dates or like counts display a dash.

In `pubspec.yaml`, Ctrl/Cmd-click a public dependency name to open its pub.dev page. Version hints use the editor font and baseline, with spacing after the declared version. Manual checks invalidate the editor hint cache so latest-version information updates even when the YAML text is unchanged, including split editors. An outdated version gets a wavy underline; clicking its latest-version hint opens **Update to: <version>** and **Update and run pub get**. If inline hints are disabled in IDE settings, the package table and dependency-name navigation still work.

Opening a pubspec reads its saved cache and fetches only declared public dependencies without latest-version information, even when automatic checks are disabled. This also applies to module pubspec files restored when the project opens. With automatic checks enabled, discovery and pubspec changes use the normal cache rules, and a one-minute timer refreshes expired dependency metadata only for packages declared in this IDE project. Unused cache entries are never included in that refresh. Unsaved editor changes are debounced for one second; only dependency changes trigger new checks. Saved/external changes are also watched. Changes to `pubspec.lock` refresh the resolved version, including workspace lockfiles. `dependency_overrides` and `pubspec_overrides.yaml` are respected.

Public hosted dependencies in `dependencies` and `dev_dependencies` are checked. Editing a dependency constraint keeps its latest-version hint visible while that dependency is checked again, bypassing the previous cache entry. Updates can optionally run `dart pub get` for Dart packages or `flutter pub get` for Flutter packages in the selected package directory. Mixed pub workspaces use Flutter when their shared resolution requires the Flutter SDK. Quotes and comments are retained, and effective overrides are updated at their source. Exact constraints stay exact; caret and other constraints become `^<latest>`. FVM or the SDK recorded in package_config is preferred, with PATH as a fallback. Process output appears in the Run window. SDK, Git (including fixed refs), relative/absolute path and custom hosted dependencies are excluded from version checks and results. Sources are classified after applying pubspec and external overrides; exact pub.dev version constraints are still checked. Requests are cached, deduplicated and limited to four concurrent connections. Manual refresh is available through the window or **Tools > FlutterTools > Check Package Updates**.

The displayed information distinguishes the declared constraint, resolved lockfile version and latest published version. No lockfile means the resolved version is unknown. A newer version allowed by a constraint may still be blocked by SDK or other dependency requirements. Network failures are shown as failures; cached results are identified. The plugin does not edit package versions or run `pub upgrade` automatically.

## Local pub cache

Dependency version checks, package search, release details, documentation and ratings use both in-memory and per-project disk caches. Files are stored under the IDE system cache directory:

```text
<IDE system directory>/flutter-tools/pub-cache/<project hash>/
```

Public responses are saved as JSON files with hashed request keys. They remain available after closing a project or restarting the IDE and are kept outside the project repository. The default lifetime is **12 hours (720 minutes)**; change **Cache minutes** in **Settings > Other Settings > FlutterTools** to use another duration. Existing explicitly saved durations are preserved. When automatic checks are enabled, expired dependency version entries are refreshed on the next one-minute timer tick, using each project package’s current pubspec declarations. With automatic checks disabled, opening a pubspec only fills missing latest-version information. Other query caches refresh when queried after expiration.

Manual dependency refresh bypasses the lifetime and atomically overwrites the corresponding successful version cache. Dependency constraint edits also trigger fresh metadata requests. Failed requests do not overwrite the last successful disk response; dependency checks retain the previous version and show a failure status. Unreadable or incompatible cache files are ignored, and a disk-write failure leaves the in-memory response usable. Query failures in Package Search retain any results already displayed and can be retried.

## Open platform projects in external IDEs

Right-click a Flutter project, module or platform directory in the Project view and open the **FlutterTools** submenu:

| Directory | Action | Opened target |
|---|---|---|
| Flutter project/module root | Assets Generate | Generate resources using the selected package's configuration |
| `android`, `.android` | Open in Android Studio | Android Gradle project directory |
| `ios`, `.ios`, `macos`, `mac` | Open in Xcode | Workspace first, then Xcode project; Runner is preferred |
| `windows` | Open in Visual Studio | A solution if present, otherwise the CMake directory with Open Folder |
| `linux`, `web` | Open in VS Code | Selected directory in a new VS Code window |

Select a single directory. Asset generation appears only on a package root declaring `dependencies.flutter.sdk: flutter` in its own `pubspec.yaml`, and uses that root regardless of the ToolWindow package selection. Platform entries appear directly under a Flutter package, with the expected Gradle, Xcode, CMake or Web project files. Ordinary directories, Dart-only packages, files and multiple selections hide the submenu. Xcode requires macOS; Visual Studio requires Windows. Unsupported host actions remain visible but disabled.

The plugin checks common installation locations and PATH. If the application cannot be found, select its `.app` bundle on macOS or executable on Windows/Linux. The selected path is remembered globally for future opens. Web opens source files in VS Code; it does not start a Flutter development server or open `index.html` in a browser.

## Source layout

Under `src/main/kotlin/com/mocaris/plugin/flutter/tools/`:

| Package | Responsibility |
|---|---|
| `assets` | Pubspec resource resolution, generator settings/PSI edits, generation, synchronization and menu action |
| `assets.ui` | Resource configuration page |
| `dependencies` | Dependency models, Dart version rules, YAML parsing, pub.dev client, checking service and editor extensions |
| `dependencies.ui` | Dependency preferences, results, update popup and editor text presentation |
| `dependencies.search` | Search response models, cached search requests, release history and main-project SDK comparison |
| `dependencies.ui.search` | Package search page, list rendering and version-selection popup |
| `core.ui` | Selectable result/status text areas |
| `core` | Package discovery, shared YAML/VFS helpers, settings, localization, icons and notifications |
| `toolwindow` | Window registration and package/page coordination |
| `platform` | Platform-directory detection, external IDE opening, discovery and launching |

Tests follow the same feature layout under `src/test/kotlin/`.

## Package search

Open **Package Search** in the FlutterTools window. Enter a package name or keyword and press Enter or click **Search**. Use **Load more** for additional results. Click a package name in the list to open its pub.dev page; clicking the rest of the row selects it.

Each result displays its description, likes and pub points as icons with counts, and its verified publisher when available. Two columns compare the **selected version** and the **latest version**, including the Dart and Flutter requirements of each release. The latest column uses the newest non-retracted stable release, falling back to a prerelease when no stable release exists.

Click the version selector at the upper right of a result to choose a historical release. Available versions are sorted newest first, include prereleases and exclude retracted or invalid versions. The popup supports typing to find a version; **Alt+Down** opens it for the selected row. Each result retains its own choice while browsing the current search. Changing the version updates its description, SDK requirements and compatibility warning without changing the latest-release column.

The lower area shows the **main project** name, Dart/Flutter constraints and detected SDK versions. Constraints come from `environment.sdk` and `environment.flutter` in the `pubspec.yaml` at the IDE project root. SDK versions are read from the main project's FVM/Flutter SDK metadata or `.dart_tool/package_config.json`; missing versions are shown as unknown. If the IDE project root has no pubspec, the SDK comparison is reported as unavailable.

Compatibility compares the selected release with the main project, even when another module is selected as the target. A known Dart or Flutter mismatch is highlighted. SDK warnings, missing SDK information and comparison failures **do not disable Add dependency**; dependency resolution is ultimately checked by pub get. Rows use a subdued, theme-aware selection background, and version selectors and the bottom status area have no control border.

Select the destination using the **Package** dropdown. Choose **dependencies** or **dev_dependencies**, then **Add dependency** or **Update dependency**. The operation writes the version selected in that result, including an explicitly chosen prerelease. New dependencies use `^<selected version>`; existing public hosted declarations update in place while retaining comments, quotes and exact-versus-caret constraint style. Edits support IDE Undo.

A dependency already in the other section must be updated there. Local, Git, SDK, custom hosted, alias and override dependencies require manual editing. Adding also requires a valid target and a loaded release. Enable **Run pub get afterwards** to run Dart or Flutter according to the selected package and workspace requirements, with output in the IDE Run window.

All plugin pub API requests—version checks, search, release history and ratings—try [pub.dev](https://pub.dev) and fall back to [pub.flutter-io.cn](https://pub.flutter-io.cn) on failure. The primary host is retried after a one-minute cooldown; if the mirror also fails during that interval, the primary is still attempted. Successful search, package and rating responses are cached in memory and on disk for the configured lifetime (12 hours by default), with at most four simultaneous search API calls. A new query cancels earlier work and ignores stale responses; failed requests can be retried without discarding existing results.

## Package documentation and upgrade notes

Click a dependency version hint or the Packages table's latest/recommended version column, then choose **README** or **CHANGELOG**. In Package Search, select a result and release, then click **Documentation**. The viewer opens the selected release and lets you switch versions and document tabs. Package status identifies discontinued packages and their suggested replacement when provided by pub.dev.

Tabs load lazily in the background and share the configured pub cache lifetime. Document requests are limited to two concurrent loads, responses to 1 MiB, rendered content to approximately 128 Ki characters, and in-memory documents to 16 entries. Previews keep text, code, tables and links; images are omitted. **Open in browser** opens the original version page, including when the embedded preview is unavailable. CHANGELOG is the package author's published upgrade information and may cover several releases; it is not an automatically generated migration guide.

## Development

Kotlin packages follow feature ownership:

- `assets`: shared declarations and registration; `config` owns YAML settings, `generation` owns Dart output, `actions` owns IDE commands, and `inspection` owns diagnostics. Existing `completion`, `preview` and `ui` packages keep their editor and presentation roles.
- `dependencies`: the shared pubspec parser and check action; `pub` owns HTTP, successful response caching and documents, `versions` owns checks and constraints, `sdk` owns compatibility recommendations, `usage` owns Dart usage scans, `installation` owns dependency edits, and `commands` owns pub get. Editor integration lives in `completion`, `inspection` and `navigation`; panels live in `ui`.
- `core`: shared project/workspace context; `settings`, `localization` and `ui` own their respective concerns.
- `workspace`: Melos commands and conversion; `ui` owns the Melos panel. `platform` owns external IDE launching, and `toolwindow` coordinates feature panels.

Tests mirror the feature packages. Plugin XML class names must follow package moves; action IDs and persisted settings names remain stable. Dependency checks, search, completion and Melos share successful package-info responses. Check failures retain their own retry interval, and manual refresh bypasses cached responses while concurrent checks share a response fetched during the same refresh.

Release archives are written to `build/distributions/FlutterTools-2.0.1.zip`. The latest full functional validation passed 358 tests with 13 Dart integration tests skipped. Shared test JVMs can produce intermittent workspace discovery timeouts; the isolated validation approach is recorded in [performance-review.md](performance-review.md).

Use JDK 17 to run `./gradlew test buildPlugin`. The default target is IntelliJ IDEA Community 2023.1 (build 231), and the plugin uses Java 17 bytecode. Build release archives against this baseline to preserve compatibility with older IDEs. Hint refresh resolves the legacy or relocated hint pass factory at runtime, including Android Studio 2026.1.3 Patch 1. Tests use the SDK's bundled `testFramework.jar`, avoiding separate test-framework artifact downloads. The first build still needs the IDE SDK and normal Gradle dependencies. Use `./gradlew runIde` for tool-window, navigation and file-watcher integration checks in a sandbox IDE.

SVG previews use the public API of bundled JSVG 2.2.0. Shadow relocates this dependency into the plugin's own namespace to avoid conflicts with copies included in newer IDEs; release archives do not call the internal IntelliJ `SVGLoader` API. The renderer remains subject to the existing preview size and complexity limits and cannot load external resources.

Run `./gradlew verifyPlugin` to check binary compatibility against representative 2023, 2024, 2025 and 2026 platforms. To verify against installed IDEs, pass a semicolon-separated list of IDE home directories in `-PverificationIdePaths`; this selects verification targets without changing the compilation baseline. For example:

```sh
./gradlew verifyPlugin \
  '-PverificationIdePaths=/path/to/IntelliJ IDEA CE.app/Contents;/path/to/Android Studio.app/Contents'
```

The hint refresh compatibility fix was verified with 171 tests per SDK and Plugin Verifier against IntelliJ IDEA 2023.1/2023.2 and Android Studio 2026.1.3 Patch 1/2026.2. The configured 2024/2025 matrix targets still require a separate verifier run.

To use a locally downloaded or installed SDK, pass `localIdePath` (the IDE home directory; on macOS use the app's `Contents` directory). For an already mounted `ideaIC-2023.2-aarch64.dmg`:

```sh
./gradlew test buildPlugin --offline \
  '-PlocalIdePath=/Volumes/IntelliJ IDEA CE/IntelliJ IDEA CE.app/Contents'
```

Keep the image mounted while using that path, or point the property to a persistent installation. The path is optional and is not stored in the repository. `--offline` works once other Gradle dependencies are cached.

## Resource path completion

In a Dart string, type a resource prefix containing `/`, such as `assets/`, to complete paths declared in the nearest package’s `pubspec.yaml` (assets, fonts and shaders). The theme-aware popup shows thumbnails or file-type icons, full paths and file sizes, with a configurable delay (100 ms by default). Folder entries use a folder icon and continue completion after selection. Enter/Tab replaces the path while retaining quotes and surrounding code. Comments, interpolation and import/export/part directives are excluded.

Resource indexing runs in the background, includes subdirectory entries and descendant files beneath declared directories, supports package paths, and updates after resource or pubspec changes (including unsaved edits). The shared **Resource previews** switch controls popup icons and gutters; path completion remains available when it is off. Only visible thumbnails are decoded, with two readers, at most 16 pending requests and a 512-entry cache.

## Workspace and Melos

Package discovery reads native Pub `workspace` members (including globs and `pubspec_overrides.yaml`) and legacy `melos.yaml` package patterns. Native membership takes precedence; Melos `ignore` patterns filter the package selector. Configuration edits, including unsaved edits, refresh membership. Each member keeps its own resource declarations and output settings while native Pub members share the workspace package_config, lockfile and SDK context.

Open the **Melos** tab after **Project Settings** to view the workspace directory and members, edit configuration, run `bootstrap` / `clean`, or select a configured script. Commands run at the project workspace root. A project dependency uses `dart run melos` with its configured/FVM SDK; legacy global installs use the `melos` executable. Scripts use `--no-select` and retain configured package filters. The root configuration documents are saved before launch. Output and cancellation are available in the IDE Run window. Only one Melos command runs per workspace at a time.

**Convert to Workspace/Melos** configures the current project in place: it preserves package directories, application sources, dependencies, resources and existing scripts; adds a native `workspace` list, latest stable Melos dependency compatible with the project Dart SDK and default analyze script; and sets child packages to `resolution: workspace`. Existing native membership takes precedence, while legacy `packages` / `ignore` rules select packages for migration. Legacy `melos.yaml` is retained; the converted pubspec configuration takes precedence. An existing root package remains usable with `useRootAsPackage: true`; a new aggregate root is marked `publish_to: none`. Compatible SDK constraints are raised to Dart 3.6 when needed; incompatible constraints, duplicate names and concurrent configuration changes stop conversion before writes. Configuration changes form one IDE Undo command. Conversion does not move files or start Bootstrap automatically; run Bootstrap after conversion using a compatible SDK.

Melos version selection uses the workspace command SDK (including FVM / `sdkPath`), with recorded project SDK metadata as a fallback. Release metadata uses the package-search cache and Pub host fallback. If no compatible Workspace/Melos release or SDK version is known, conversion stops before edits. Generated `workspace` and `melos` configuration uses block YAML, including workspace member lists, script mappings and package lists. Existing workspace lists in `pubspec_overrides.yaml` are also expanded to block YAML.

The Melos panel uses compact, top-aligned Configuration, Members, Tasks and Status sections. Member and script lists have theme-aware backgrounds and outlines; scripts keep a four-row viewport with the Run script button beside the list. Bootstrap and Clean share the Tasks section. Narrow windows scroll vertically, and **Edit YAML** opens the existing configuration file.

The Melos panel offers **Include root package** (`useRootAsPackage`) and **Discover nested workspaces** (`discoverNestedWorkspaces`), initialized from the root pubspec's Melos configuration. The first checkbox includes the root package in Melos operations; unchecked follows Melos's default and excludes it. The second enables nested discovery and writes `packages/**` into `workspace` on Dart 3.11+. Older SDKs use explicit paths for all discovered packages instead, retaining `discoverNestedWorkspaces: true`. New packages on older SDKs must also be added to the explicit workspace member list. Unchecked expands configured members to explicit package paths. Existing members outside `packages/` remain explicitly listed, and any workspace override is kept consistent. Click **Save configuration** to apply changes to a converted workspace, or **Convert to Workspace/Melos** to apply them during conversion. Unsaved choices are retained during refresh; commands are enabled after saving. Both nodes remain block YAML with blank lines separating new top-level entries.
