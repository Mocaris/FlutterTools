# FlutterTools

Flutter asset generation, dependency checks and pub.dev package search for IntelliJ IDEA and Android Studio.

- Open the right-side tool window from **View > Tool Windows > FlutterTools** or **Tools > FlutterTools > Open ToolWindow**. Select a package and switch between Assets, Packages and Package Search.
- Use English or Chinese, follow the system language by default, and switch languages without restarting. Icons and list colors adapt to light and dark themes.
- Generate resources from `pubspec.yaml` assets, fonts and shaders. Configure exclusions, output file/class, extensions and automatic generation through YAML or the visual configuration page.
- Generate a Dart entry plus three parts under `lib/gen/`. Access assets by directory, such as `R.assets.images.tab.icTabHome.path`; use immutable resource metadata and font-family groups with Flutter weight/style types.
- Generate resources independently for discovered modules. The main project defaults to class `R`; other packages use names such as `FeatureLoginR`, with an optional explicit class override.
- Load package configuration at startup, preserve YAML comments and unrelated settings, and protect unsaved configuration and generated output edits.
- Check public hosted dependencies after `pubspec.yaml` editor or external changes. Keep latest-version hints visible during rechecks, underline outdated versions, and update with optional pub get.
- Ctrl/Cmd-click dependency names or click search-result titles to open pub.dev. Respect overrides and skip SDK, Git, path and custom hosted dependencies during version checks.
- Search packages with pagination and release history. Compare selected and latest versions with their Dart/Flutter requirements; display likes and pub points as icons with counts.
- Highlight selected-release SDK differences against the main project without blocking dependency addition. Add or update the selected release in the chosen package, including prereleases, with optional pub get.
- Cache background requests and automatically fall back to [pub.flutter-io.cn](https://pub.flutter-io.cn) when the pub.dev API is unavailable.

Requires IntelliJ Platform 2023.2 or later and YAML support. Build with JDK 17.
