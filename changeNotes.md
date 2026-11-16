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
- Generate the entry at out_path and place assets.g.dart, fonts.g.dart and shaders.g.dart under each package's lib/gen directory. Migrate matching legacy generated parts after successful generation.
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
