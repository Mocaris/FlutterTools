# FlutterTools

An IntelliJ IDEA / Android Studio plugin for Flutter resources, dependencies and workspaces.

**Version 2.0.1** · IntelliJ Platform 2023.1+ · YAML plugin required · Dart plugin optional for Dart editor features. See [release notes](changeNotes.md).

## Features

- Generate assets, fonts and shaders from `pubspec.yaml` into one Dart file.
- Register resources from the Project view, with recursive folder support.
- Complete resource paths, preview images and navigate to source files in Dart.
- Check dependency updates, recommend SDK-compatible releases and show direct-usage hints.
- Search pub.dev, select historical versions, preview README/CHANGELOG and add dependencies.
- Discover Pub workspaces and Melos packages; run tasks or convert an existing project.
- Open platform projects in Android Studio, Xcode, Visual Studio or VS Code.
- Use English or Chinese, with global defaults and project overrides.

## Quick start

Open **Tools > FlutterTools > Open Tool Window** or **View > Tool Windows > FlutterTools**, then select a package.

| Page | Use |
|---|---|
| Assets | View declarations, configure generation and generate resources |
| Packages | Check declared, resolved, latest and recommended versions |
| Package Search | Search releases, read documentation and add/update dependencies |
| Project settings | Override global editor and dependency preferences |
| Melos | Manage workspace configuration, members and tasks |

The **Tools > FlutterTools** menu also provides generation, update checks, Pub Get and settings shortcuts. Commands use the current file's package, then the selected package, then the only discovered package. Pub Get saves the relevant package/workspace pubspec documents and shows output in the IDE Run window.

## Resource generation

Declare resources in the selected package's `pubspec.yaml`:

```yaml
name: your_app
environment:
  sdk: ^3.0.0
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

Directories include direct files only; declare ordinary subdirectories separately. Flutter resolution variants share the base image's logical key. Package resource paths use `.dart_tool/package_config.json`; run `flutter pub get` first.

Optional generator settings go in `flutter_tools.yaml` or under the same key in `pubspec.yaml`. The standalone file takes precedence; the Assets page creates it on first save when no configuration exists.

```yaml
flutter_tools:
  assets_sync:
    excluded:
      - .DS_Store
      - '*.tmp'
    out_path: lib/r.dart
    out_class: R
    out_extension: false
    watch: false
```

Click **Generate now** or **Tools > FlutterTools > Generate Resources**. `watch` enables automatic generation after resource or declaration changes. Exclusions affect generated references, not Flutter bundling.

Each package generates one entry file, defaulting to `lib/r.dart`. The main package uses `R`; other packages derive names such as `FeatureLoginR` from `pubspec.name`. Explicit `out_class` values take precedence. Resources use public const wrappers such as `RAssetsMeta` and `RFontFaceMeta`, with public `AssetsMeta` and `FontFaceStyle` Record aliases. Directory and font-family groups remain private. Callers can use type inference or name the public metadata types. When importing several generated entries, use import prefixes to distinguish their same-named Record aliases.

The entry class exposes the declaring module name as `static const String package`, for example `R.package` or `LoginR.package`. Resource metadata references this constant directly; only other package names need group-local constants. Directory and font groups do not expose a package getter. Font-family groups expose `String get family => _family`, and each face's style references the same private family constant.

```dart
import 'package:your_app/r.dart';

Image.asset(R.assets.images.logo.path);
FragmentProgram.fromAsset(R.shaders.shadersEffect.path);

final logo = R.assets.images.logo;
logo.package;       // 'your_app'
logo.kind;          // 'assets'
logo.sourcePackage; // null, or the package in a packages/<name>/ path
logo.fullPath;      // 'packages/your_app/assets/images/logo.png'

final face = R.fonts.roboto.w400;
Text('Hello', style: face.textStyle);
R.fonts.roboto.faces; // immutable list of declared faces
```

Asset properties follow directories: `assets/images/icons/home.png` becomes `R.assets.images.icons.home`. Font families expose faces such as `w400`, `w700` and `w400Italic`, with path, family, weight and style metadata. Same-directory name conflicts gain extension suffixes; `out_extension` includes suffixes for every field.

Use `path` for main-app resources and `fullPath` for dependency-package resources. Existing `packages/...` keys remain unchanged. Font `textStyle` contains family, weight and style; set Flutter's `package` argument when needed for dependency fonts.

Generation requires a Dart SDK lower bound of **3.0.0+**. If package configuration records an older language version, run Pub Get. Invalid declarations, naming conflicts and unsafe output paths stop generation before replacement. Output must be a `.dart` file inside the package; existing files require the FlutterTools marker, and unsaved output edits are protected.

**Migration:** use nested properties and `.path` for old string references. All output now lives in the entry file. Legacy `sync_path` and `generate_dir` settings are removed on save; obsolete part files must be removed manually.

## Resource registration and editor support

Right-click files or folders in the Project view and choose **FlutterTools > Register Flutter assets**. The recursive action also registers ordinary subfolders, skipping resolution variants, symbolic links and nested packages. Registration preserves comments, skips duplicates, supports Undo and starts generation. Fonts and shaders belong in their dedicated pubspec sections.

In a Dart string, type a prefix such as `assets/` or use manual completion. Folder selection continues browsing. Indexed paths show image thumbnails or file icons, with Ctrl/Cmd-click navigation. PNG, JPEG, GIF, BMP, SVG and WebP previews load in the background with bounded caches. Imports, comments and interpolated strings are ignored.

SVG previews support alpha/luminance masks and bounded shadow/color filters. External resources, cyclic references and effects exceeding preview limits fall back to opening the original file. Filters using transforms, CSS, nested SVGs or object-bounding-box primitive units remain outside the preview support range. Popup loading takes priority over queued thumbnails; invalidated jobs skip decoding, and raster images reuse correctly sized decoded buffers.

Pubspec diagnostics flag missing paths, duplicates, invalid fonts and generated-name conflicts, with fixes where available. The **Resource previews** setting controls gutters and completion icons; navigation remains available when previews are off.

## Dependencies and package search

The Packages page and pubspec editor show declared constraints, lockfile versions and latest releases. Update menus offer SDK-compatible recommendations, README/CHANGELOG previews and optional Pub Get. Updates preserve comments and quotes and support Undo; versions are never changed automatically.

Version checks cover public pub.dev dependencies after overrides are applied. SDK, Git, local path and custom hosted dependencies are excluded. **(unused)** means no direct Dart import/export was found, including tests and tools; it is not proof that a native or configuration-only dependency can be removed.

Type at least two characters in a pubspec dependency key for package completion, or use manual completion. In Package Search, select a release and destination package, then add/update it in `dependencies` or `dev_dependencies`. Existing non-hosted or override declarations require manual editing. SDK warnings do not block addition; Pub Get validates resolution.

Requests fall back from [pub.dev](https://pub.dev) to [pub.flutter-io.cn](https://pub.flutter-io.cn). Successful responses are cached outside the repository for **12 hours** by default. Manual refresh bypasses the cache; failures retain the last successful response.

## Settings and workspaces

Open **Settings > Other Settings > FlutterTools** for language, previews, completion delay, dependency hints/checks, cache lifetime and prerelease preferences. The language selector follows the IDE interface language or uses English/Chinese explicitly. Project overrides live in the tool window; language remains global. Generator options stay in each package's YAML.

Package discovery supports native Pub `workspace` members and legacy `melos.yaml` patterns. Each package keeps its own resource settings. The Melos page runs Bootstrap, Clean and configured scripts with Run-window output and cancellation.

**Convert to Workspace/Melos** edits configuration in place, preserves package files and scripts, and supports Undo. It requires a compatible SDK/Melos release and may raise compatible Dart constraints to **3.6**. Run Bootstrap afterwards. Nested workspace globs require Dart **3.11+**; older SDKs use explicit member paths that must be updated when adding packages.

For platform projects, right-click `android`/`.android`, `ios`/`.ios`/`macos`, `windows`, `linux` or `web` and use the FlutterTools submenu. Xcode requires macOS; Visual Studio requires Windows. Missing IDE installations can be selected and remembered.

## Development

Use JDK 17:

```sh
./gradlew test buildPlugin
./gradlew runIde
./gradlew verifyPlugin
```

The compilation baseline is IntelliJ IDEA Community 2023.1; Plugin Verifier targets representative 2023–2026 IDEs. Archives are written to `build/distributions/`. SVG previews use bundled, relocated JSVG.

Use `-PlocalIdePath=/path/to/IDE/home` for an installed SDK, or `-PverificationIdePaths='/path/to/IDE1;/path/to/IDE2'` for local verification targets. On macOS, use the app's `Contents` directory. Dart integration tests require the optional Dart test environment.
