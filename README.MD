# FlutterTools

An IntelliJ IDEA / Android Studio plugin for Flutter asset generation, dependency checks and pub.dev package search. Requires an IDE compatible with IntelliJ Platform 2023.2 or later and the bundled YAML plugin.

## Tool window

Open **View > Tool Windows > FlutterTools** or **Tools > FlutterTools > Open ToolWindow**. The window is docked on the right with a dedicated light/dark SVG icon and lets you select the Flutter/Dart package to work with.

Use the **Language** selector at the top of the window to choose **Follow system**, **中文** or **English**. The default follows the system language: Chinese locales use Simplified Chinese and other locales use English. The selection is saved globally for the plugin and takes effect immediately in open FlutterTools windows, action menus and editor hints without discarding unsaved configuration edits. UI labels, dialogs, statuses, notifications and plugin validation errors are localized; file paths, YAML keys, generated Dart identifiers and external error details retain their original values.

- **Assets**: view resource declarations from pubspec, open pubspec to edit them, configure exclusions/output/class name, enable file extensions or automatic generation, and save/reload settings or generate resources.
- **Packages**: enable automatic checks, choose the cache lifetime (default 30 minutes), include prereleases, refresh versions, and inspect declared constraints, resolved versions, latest versions and check status. Click a public package name to open pub.dev.
- **Package Search**: search pub.dev, compare selected and latest release SDK requirements, choose a historical version, and add or update a dependency in the selected package.

Configuration edits preserve unrelated YAML, comments and unchanged configuration values. Switching packages with unsaved form changes offers Save / Discard / Cancel. External changes require a reload before overwriting an edited form. Saving configuration is an IDE command and supports Undo.

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

The default entry is `lib/r.dart`. The main project uses class `R`; other packages derive their default class name from `pubspec.name`. Three part files are generated under the package's `lib/gen/` directory:

- `lib/gen/assets.g.dart`: resource objects from `flutter.assets`, ordered by logical path.
- `lib/gen/fonts.g.dart`: font families from `flutter.fonts`, ordered by family, then weight, style and path.
- `lib/gen/shaders.g.dart`: resource objects from `flutter.shaders`, ordered by logical path.

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

Empty sections still generate empty groups, so removed declarations clear stale fields. A custom output path changes the entry location; all three part files remain under the package's `lib/gen/` directory. Relative `part` and `part of` references are generated for the actual locations. A custom class name also changes the support types and private group class names. Existing string callers must add `.path` (`R.assets.images.logo.path`); font callers now use family groups (`R.fonts.roboto.w400.path`).

Automatic generation handles creation, deletion, renaming and movement, including changes while the IDE was closed. Saved and unsaved pubspec changes update declarations even when generator settings are stored separately. Single image declarations also watch the parent/variant directories. Missing declared resources report errors before replacing output; removing a declaration removes its constant. Package resources declared with `packages/<name>/...` are resolved through `.dart_tool/package_config.json`; run `flutter pub get` first. Conflicting resource fields automatically gain extension suffixes (for example, `logoPng` and `logoSvg`); unaffected field names stay unchanged. Remaining naming collisions fail before replacing output.

The entry must be a `.dart` file and must not use one of the three part filenames. None of the four output files may replace an explicitly declared resource; all four are excluded from directory resource scans. Existing files are replaced only when their first line is the FlutterTools generation marker; unsaved output edits are protected across the whole batch. Conflicts are checked before replacing any file, and replacement failures attempt to restore earlier outputs.

Package configuration changes refresh external resource locations. The plugin generates Dart constants; it does not modify Flutter resource declarations. Exclusions affect generated constants, not Flutter bundling. Legacy `sync_path` is ignored and removed when generator settings are saved. Without custom settings, manual generation uses `lib/r.dart` and the target package's default class name.

## Resource generation in multiple packages

Select the target package using the existing Package dropdown, then configure and generate its resources on the resource page. Each package reads its own `pubspec.yaml` and `flutter_tools.yaml` settings (standalone settings take precedence), with the usual defaults when no generator settings exist. The entry follows that package's `out_path`; the three `.g.dart` files are generated under its `lib/gen/` directory.

The package at the IDE project directory defaults to class `R`. Other packages default to a class derived from their `pubspec.yaml` name, for example `feature_login` becomes `FeatureLoginR` and `shared_ui` becomes `SharedUiR`. Related public resource types use the same prefix. An explicit `out_class` always takes precedence, including an existing `out_class: R`; remove that setting to use the automatic default or choose a different name in the resource configuration page.

Legacy part files alongside the entry are removed after successful generation only when they carry the FlutterTools marker and a matching `part of` reference. Unsaved edits are protected; failed replacements or migration restore earlier files.

## Dependency checks and navigation

In `pubspec.yaml`, Ctrl/Cmd-click a public dependency name to open its pub.dev page. Version hints use the editor font and baseline, with spacing after the declared version. An outdated version gets a wavy underline; clicking its latest-version hint opens **Update to: <version>** and **Update and run pub get**. If inline hints are disabled in IDE settings, the package table and dependency-name navigation still work.

Checks run in the background when a package is discovered or its pubspec is opened. Unsaved editor changes are debounced for one second; only dependency changes trigger new checks. Saved/external changes are also watched. Changes to `pubspec.lock` refresh the resolved version, including workspace lockfiles. `dependency_overrides` and `pubspec_overrides.yaml` are respected.

Public hosted dependencies in `dependencies` and `dev_dependencies` are checked. Editing a dependency constraint keeps its latest-version hint visible while that dependency is checked again, bypassing the previous cache entry. Updates can optionally run `flutter pub get` in the package directory. Quotes and comments are retained, and effective overrides are updated at their source. Exact constraints stay exact; caret and other constraints become `^<latest>`. FVM or the SDK recorded in package_config is preferred, with PATH as a fallback. Process output appears in the Run window. SDK, Git (including fixed refs), relative/absolute path and custom hosted dependencies are excluded from version checks and results. Sources are classified after applying pubspec and external overrides; exact pub.dev version constraints are still checked. Requests are cached, deduplicated and limited to four concurrent connections. Manual refresh is available through the window or **Tools > FlutterTools > Check Package Updates**.

The displayed information distinguishes the declared constraint, resolved lockfile version and latest published version. No lockfile means the resolved version is unknown. A newer version allowed by a constraint may still be blocked by SDK or other dependency requirements. Network failures are shown as failures; cached results are identified. The plugin does not edit package versions or run `pub upgrade` automatically.

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

Tests follow the same feature layout under `src/test/kotlin/`.

## Package search

Open **Package Search** in the FlutterTools window. Enter a package name or keyword and press Enter or click **Search**. Use **Load more** for additional results. Click a package name in the list to open its pub.dev page; clicking the rest of the row selects it.

Each result displays its description, likes and pub points as icons with counts, and its verified publisher when available. Two columns compare the **selected version** and the **latest version**, including the Dart and Flutter requirements of each release. The latest column uses the newest non-retracted stable release, falling back to a prerelease when no stable release exists.

Click the version selector at the upper right of a result to choose a historical release. Available versions are sorted newest first, include prereleases and exclude retracted or invalid versions. The popup supports typing to find a version; **Alt+Down** opens it for the selected row. Each result retains its own choice while browsing the current search. Changing the version updates its description, SDK requirements and compatibility warning without changing the latest-release column.

The lower area shows the **main project** name, Dart/Flutter constraints and detected SDK versions. Constraints come from `environment.sdk` and `environment.flutter` in the `pubspec.yaml` at the IDE project root. SDK versions are read from the main project's FVM/Flutter SDK metadata or `.dart_tool/package_config.json`; missing versions are shown as unknown. If the IDE project root has no pubspec, the SDK comparison is reported as unavailable.

Compatibility compares the selected release with the main project, even when another module is selected as the target. A known Dart or Flutter mismatch is highlighted. SDK warnings, missing SDK information and comparison failures **do not disable Add dependency**; dependency resolution is ultimately checked by pub get. Rows use a subdued, theme-aware selection background, and version selectors and the bottom status area have no control border.

Select the destination using the **Package** dropdown. Choose **dependencies** or **dev_dependencies**, then **Add dependency** or **Update dependency**. The operation writes the version selected in that result, including an explicitly chosen prerelease. New dependencies use `^<selected version>`; existing public hosted declarations update in place while retaining comments, quotes and exact-versus-caret constraint style. Edits support IDE Undo.

A dependency already in the other section must be updated there. Local, Git, SDK, custom hosted, alias and override dependencies require manual editing. Adding also requires a valid target and a loaded release. Enable **Run pub get afterwards** to run Flutter in the selected package directory, with output in the IDE Run window.

All plugin pub API requests—version checks, search, release history and ratings—try [pub.dev](https://pub.dev) and fall back to [pub.flutter-io.cn](https://pub.flutter-io.cn) on failure. The primary host is retried after a one-minute cooldown; if the mirror also fails during that interval, the primary is still attempted. Successful search, package and rating responses are cached for five minutes, with at most four simultaneous search API calls. A new query cancels earlier work and ignores stale responses; failed requests can be retried without discarding existing results.

## Development

Use JDK 17 to run `./gradlew test buildPlugin`. The default target is IntelliJ IDEA Community 2023.2. Tests use the SDK's bundled `testFramework.jar`, avoiding separate test-framework artifact downloads. The first build still needs the IDE SDK and normal Gradle dependencies. Use `./gradlew runIde` for tool-window, navigation and file-watcher integration checks in a sandbox IDE.

To use a locally downloaded or installed SDK, pass `localIdePath` (the IDE home directory; on macOS use the app's `Contents` directory). For an already mounted `ideaIC-2023.2-aarch64.dmg`:

```sh
./gradlew test buildPlugin --offline \
  '-PlocalIdePath=/Volumes/IntelliJ IDEA CE/IntelliJ IDEA CE.app/Contents'
```

Keep the image mounted while using that path, or point the property to a persistent installation. The path is optional and is not stored in the repository. `--offline` works once other Gradle dependencies are cached.
