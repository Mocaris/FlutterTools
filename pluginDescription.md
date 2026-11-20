# FlutterTools

Flutter asset generation, dependency checks and pub.dev package search for IntelliJ IDEA and Android Studio.

- Use **Tools > FlutterTools** to jump to Package Search, Workspace/Melos or project settings, generate resources, check updates or run Pub Get. Package commands show their target and are disabled when unavailable or running; global settings remain a separate entrance.
- Support WebP resource recognition and bounded previews, Ctrl/Cmd navigation from Dart resource strings, native Pub workspace/Melos membership and a Melos panel after Project Settings with in-place Workspace/Melos conversion, Bootstrap/Clean and script execution with Run-window output and cancellation.
- Complete pubspec resource paths inside Dart strings with thumbnails/file icons and file sizes. Share the default-on Resource previews switch across completion lists, string gutters and generated resources; use lazy, bounded loading.
- Open the right-side tool window from **View > Tool Windows > FlutterTools** or **Tools > FlutterTools > Open Tool Window**. Select a package and switch between Assets, Packages and Package Search.
- Use English or Chinese, follow the system language by default, and switch languages without restarting. Icons and list colors adapt to light and dark themes.
- Diagnose missing/duplicate declarations, font configuration and naming conflicts in pubspec, with safe quick fixes and Undo.
- Register selected resource files or directories from **Project > FlutterTools > Add to Flutter assets**, preserving pubspec comments, avoiding duplicate entries and generating resources for the selected package.
- Preview version-specific README and CHANGELOG from dependency update menus or Package Search. Switch releases, check discontinued/replacement status and open the original pub.dev page.
- Recommend SDK compatible releases for the destination package alongside the latest published version, with explicit selection and optional pub get.
- Preview PNG/JPEG/GIF/BMP/SVG/WebP thumbnails and other file-type icons beside generated resource fields or declared Dart path strings. Use the default-on project switch in Assets; hover for metadata, click for a bounded larger preview and file navigation. Reads are lazy, cancellable and cached with fixed limits.
- Generate resources from `pubspec.yaml` assets, fonts and shaders. Configure exclusions, output file/class, extensions and automatic generation through YAML or the visual configuration page.
- Generate a Dart entry plus three parts in a configurable directory (default `lib/generate/`). Access assets by directory, such as `R.assets.images.tab.icTabHome.path`; use immutable resource metadata and font-family groups with Flutter weight/style types.
- Generate resources independently for discovered modules. The main project defaults to class `R`; other packages use names such as `FeatureLoginR`, with an optional explicit class override.
- Load package configuration at startup, preserve YAML comments and unrelated settings, and protect unsaved configuration and generated output edits.
- Check public hosted dependencies after `pubspec.yaml` editor or external changes. Keep latest-version hints visible during rechecks, underline outdated versions, and update with optional pub get.
- Show **(unused)** beside runtime dependency hints when the current package has no direct Dart import/export reference, with bounded background scans and live updates for unsaved source changes.
- Complete dependency names in pubspec with an automatic pub.dev popup showing package names and versions. Throttle searches, cancel stale requests and preserve existing constraints/comments when a candidate is selected.
- Ctrl/Cmd-click dependency names or click search-result titles to open pub.dev. Respect overrides and skip SDK, Git, path and custom hosted dependencies during version checks.
- Search packages with pagination and release history. Compare selected and latest versions with their Dart/Flutter requirements; display likes and pub points as icons with counts.
- Highlight selected-release SDK differences against the main project without blocking dependency addition. Add or update the selected release in the chosen package, including prereleases, with optional pub get.
- Use the Project view’s FlutterTools submenu to generate assets for the selected Flutter project/module or open platform projects: Android Studio for Android, Xcode for iOS/macOS, Visual Studio for Windows, and VS Code for Linux/Web. Locate installed tools automatically or remember a selected application path.
- Share successful pub package details across dependency checks, search, completion and Melos. Persist responses in the IDE cache directory with a default 12-hour lifetime, deduplicate concurrent manual refreshes and preserve the last successful response on failure. Automatically fall back to [pub.flutter-io.cn](https://pub.flutter-io.cn) when the pub.dev API is unavailable.

Targets IntelliJ Platform 2023.1–2026 and requires YAML support. Build with JDK 17.
