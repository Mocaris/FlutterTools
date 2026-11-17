## Unreleased

- Include subdirectories and descendant files in resource path completion, continue browsing after selecting a folder, and use a 100 ms throttle. Anchor generated resource preview icons on the path string line.
- Complete pubspec resource paths inside Dart strings with thumbnails/file icons and file sizes. Share the default-on Resource previews switch across completion lists, string gutters and generated resources; use lazy, bounded loading.
- Add parenthesized unused hints beside latest-version hints for runtime dependencies without direct Dart import/export references. Include unsaved, conditional and test/tool references; exclude SDK/dev dependencies and generated/cache/nested-package sources. Scan in the background with bounded, incremental reference caching and suppress unused conclusions for incomplete scans.

- Add automatic pub.dev package completion for dependency keys in pubspec, with name/version popup candidates and Enter/Tab selection. Throttle searches to one start per 200 ms, cancel stale work, bound candidate/cache sizes and preserve existing constraints/comments. Theme-aware candidate rows show the existing FlutterTools icon, localized release age and right-aligned heart/like counts.

- Add quick resource registration from the Project view for files and directories in the same Flutter package. Preserve pubspec comments and other settings, skip duplicate/shallow-covered entries, support Undo and trigger resource generation.
- Add version-specific README/CHANGELOG previews to dependency update menus and Package Search, with release selection, discontinued/replacement status and a browser fallback. Load tabs on demand with bounded requests, HTML size and document caching.

- Add optional Dart image gutters for generated assets.g.dart, with PNG/JPEG/GIF/BMP/SVG thumbnails, metadata tooltips and a larger click preview/file navigation. Add an Assets panel switch that defaults on, persists per project and applies immediately.
- Load only visible thumbnails, cap readers/queues/cache/popup sizes, subsample raster files, cancel work when disabled/closed and invalidate affected resources and package locations on VFS changes. Keep the Dart extension optional and use platform PSI APIs.

- Add pubspec resource diagnostics for missing paths, duplicates, invalid sections/fonts and generated-name conflicts. Offer existing-resource selection, directory slash/font-value repairs and duplicate removal that retains comments, with IDE Undo.
- Show SDK compatible release recommendations for the destination package in dependency results, update popups and package search. Retain explicit latest-version selection, distinguish unknown/no-compatible states and reuse cached release requirements.

- Fetch missing latest-version information when opening module pubspec files even with automatic checks disabled; restore cached results first. Refresh expired metadata periodically only for dependencies declared in the current IDE project.

- Fix missing inlay hint factory errors in Android Studio 2026.1.3 Patch 1; resolve hint refresh APIs across IDE versions and refresh language changes on the EDT. Use IntelliJ Platform 2023.1 as the compilation baseline and add a 2023–2026 Plugin Verifier matrix.

- Persist successful pub version, search, detail and rating responses outside the project, default the cache lifetime to 12 hours, and overwrite version cache entries after successful manual refresh. Preserve existing custom lifetimes and last successful disk responses on request failure.

- Add a FlutterTools submenu to the Project view for generating assets in the selected Flutter project/module and opening platform projects in Android Studio, Xcode, Visual Studio or VS Code, with automatic application discovery and remembered paths. Move plugin actions out of Open In and support module wrappers `.android` / `.ios`.
- Prefer Xcode workspaces over projects and Windows solutions over CMake folders; disable tools unsupported by the host operating system.
- Fix latest-version hints after manual dependency checks by invalidating unchanged-file hint caches, including split editors and language changes.

## 2.0.0

### Tool window and package search

- Add a right-side FlutterTools window with Assets, Packages and Package Search pages, shared package selection, and an Open Tool Window action in the Tools menu.
- Add English/Chinese localization, a persistent language selector and light/dark SVG icons.
- Add paged pub.dev search, clickable package titles, likes and pub points icons with counts, and publisher information.
- Add per-result historical version selection, including prereleases, with selected/latest release and Dart/Flutter requirement comparisons.
- Compare selected releases against the main project's Dart/Flutter constraints and SDK metadata. Highlight mismatches and unknown SDKs without disabling dependency addition.
- Add or update the selected release in dependencies or dev_dependencies, preserving YAML comments and quoting, with optional pub get and IDE Undo.
- Use borderless version selectors and status text, roomier list rows and a subdued theme-aware selection background.

### Dependency checks

- Automatically check public hosted dependencies after pubspec editor or external changes, and refresh resolved versions after lockfile changes.
- Preserve latest-version hints during constraint edits and recheck the changed dependency without reusing its cached version response.
- Add outdated-version underlines and an inline update popup with Update and Update with pub get actions. Align hint fonts and baselines with dependency values and add spacing.
- Respect pubspec/external overrides and skip SDK, Git, local path and custom hosted dependencies during checks.
- Share pub.dev to pub.flutter-io.cn API fallback across checks, search, release history and ratings, with caching, request deduplication and bounded concurrency.

### Resource generation

- Read asset, font and shader paths from pubspec declarations; remove sync_path from generator settings and migrate legacy settings on save.
- Support independent generation for discovered packages. Default to R in the main project and package-derived class names such as FeatureLoginR in modules, unless out_class is explicit.
- Generate the entry at out_path and place assets.g.dart, fonts.g.dart and shaders.g.dart under each package's configurable generate_dir (default lib/generate). Migrate matching legacy generated parts from the previous generation directory, lib/gen or beside the entry after successful generation and update relative part references.
- Generate nested asset properties from directory paths, including R.assets.images.tab.icTabHome, R.assets.fonts and R.assets.anim. Resolve same-directory basename collisions with extension suffixes.
- Follow Flutter's shallow directory declarations and logical image/resolution-variant rules, and resolve package resource paths through package_config.json.
- Generate immutable resource metadata and font-family groups, preserving each face's path, weight and style. Use UpperCamelCase support types and super parameters in generated Dart.
- Load YAML configuration at startup, save valid block-style YAML and preserve unrelated settings and comments. Protect unsaved edits and conflicting external changes.
- Fix resource watcher lifecycle and creation/deletion/rename/move handling. Preflight all outputs, refuse to replace unmarked files and restore earlier outputs when replacement fails.

### Migration and development

- Existing flattened asset references must be updated to nested directory properties after regeneration. Resource paths are accessed through .path; Flutter font declarations use family groups under R.fonts.
- Use the Package dropdown for module selection; resource declarations remain in each package's pubspec.yaml.
- Organize source and tests by feature. Target IntelliJ Platform 2023.2+ and JDK 17, update Gradle to 8.14.6, and support localIdePath with the SDK's bundled test framework.

## 1.0.12
- update plugin and use yaml config

## 1.0.11
- bug fixed,Using the Notification Api

## 1.0.10
- update plugin

## 1.0.9
- bug fixed

## 1.0.8
- sort sync files

## 1.0.7
- fixed window path bug
