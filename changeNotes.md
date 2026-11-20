## 2.0.1

- Generate assets, fonts and shaders in one entry file with public const metadata wrappers and Record aliases; directory and font-family groups remain private. Add `fullPath` and font `textStyle` getters. **Dart 3 is required; remove obsolete part files manually.** Legacy `generate_dir` is removed on save.
- Simplify Assets, Packages, Package Search and Melos layouts for narrow windows; keep long search details inside the panel.
- Add Tools menu shortcuts for search, workspace, Pub Get and project settings, with package-aware command targets.
- Add recursive resource registration, pubspec diagnostics and fixes, Dart path completion and Ctrl/Cmd navigation.
- Unify generated and ordinary Dart resource previews; support SVG and WebP with bounded background loading and a bundled SVG renderer.
- Preview SVG masks and bounded shadow filters; distinguish restricted SVG content, prioritize popup loading and avoid redundant raster buffers and stale thumbnail decoding.
- Add pub.dev dependency completion, direct-usage hints, SDK-compatible release recommendations and version-specific README/CHANGELOG previews.
- Support native Pub workspaces and Melos discovery, configuration conversion, Bootstrap/Clean and scripts with output and cancellation.
- Share successful pub responses across features, cache them outside the project for 12 hours by default, and retain successful data on request failure.
- Reduce repeated SDK/resource work and hidden-page refreshes; organize source and tests by feature.
- Add Project-view shortcuts for external platform IDEs and fix dependency hint refreshes across IntelliJ IDEA and Android Studio versions. Use IntelliJ Platform 2023.1 as the build baseline.

## 2.0.0

- Add the FlutterTools window with Assets, Packages and Package Search, package selection and English/Chinese localization.
- Add pub.dev search, historical release selection, SDK comparisons and dependency edits with optional Pub Get and Undo.
- Add automatic dependency checks, resolved/latest version hints and update menus, respecting overrides and non-public dependency sources.
- Generate nested asset references, font-family groups and shader metadata from pubspec declarations, with independent package configuration.
- Generate an entry plus asset/font/shader part files, with configurable output directories and legacy-file migration.
- Preserve YAML comments and unrelated settings; protect unsaved edits, validate outputs and improve resource watching.
- Organize source and tests by feature; update to JDK 17 and Gradle 8.14.6.

## 1.0.12

- Update the plugin and add YAML configuration.

## 1.0.11

- Fix bugs and adopt the Notification API.

## 1.0.10

- Update the plugin.

## 1.0.9

- Fix bugs.

## 1.0.8

- Sort synchronized files.

## 1.0.7

- Fix Windows paths.
