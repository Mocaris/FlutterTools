# Packages, Package Search and shared header verification

## Sources and rendering

- Source visual truth: `build/ui-preview/source-packages.png` and `build/ui-preview/source-search.png`, the two screenshots supplied by the user.
- Implementation: the actual `FlutterToolsPanel`, `DependenciesPanel` and `PackageSearchPanel` in the IntelliJ 2023.1 Swing test framework. Native lightweight component peers are initialized for rendering; these are not captures of a running IDE.
- Packages reference: 1640 × 2216 pixels. Crop the 82-pixel tool-window title area, then normalize the remaining 1640 × 2134 panel to 820 × 1067 logical pixels.
- Search reference: 1662 × 2140 pixels, normalized to 831 × 1070 logical pixels. No title crop is required.
- Additional viewports: 320 × 760 in light and Darcula themes, plus Chinese at 320 × 760. Captured component renders use scale 1; native component spacing follows `JBUI.scale`.
- State: `modules/login` selected; two cached dependency results matching the reference. Search covers no selection and a selected HTTP package with two sample releases. Sample project/package files provide Dart 3.9.2 metadata; Flutter SDK remains unknown because no Flutter installation is supplied. The English preview explicitly selects English rather than Follow system; these state differences do not affect the layout comparison.
- Full-view evidence: `packages-comparison.png` and `search-comparison.png`, with the normalized source on the left and final implementation on the right.
- Focused evidence: `packages-header-comparison.png`, `search-header-comparison.png` and `search-settings-comparison.png` show selector alignment, table headers and dependency/SDK settings at readable size.

## Findings and corrections

1. [P2, resolved] Fixed-width selectors in one row exceed the space available in narrow windows. Package and Language now use aligned rows; Package fills the available width and Language retains its compact native size. Labels have explicit associations, and package tooltips retain the full path.
2. [P2, resolved] Equal-width dependency columns and the large status column force horizontal scrolling even in the reference's wide window, while short numeric values waste space and Status is clipped. Columns now have meaningful preferred widths and font-aware minimum widths. Wide tables fill their viewport; narrow tables scroll internally. Every cell tooltip includes its complete value and update/SDK status.
3. [P2, resolved] Dense dependency rows sit on an unframed surface above an oversized status block. Native bordered surfaces, padded rows, a heading/Refresh row and a two-row status area clarify the structure. An early renderer leaked its link foreground into other columns; the final renderer applies the link color only to public package names.
4. [P2, resolved] Search stacks SDK summary, recommendation, warnings and all actions below a large empty result area. Results and settings now use a draggable native vertical splitter. Independently scrollable settings group the destination, dependency type, Pub Get, documentation and Add/Update action before SDK details. Search and pagination retain their original actions.
5. [P2, resolved] An empty selection appeared as a red error. The final empty state uses a search prompt, disabled actions and no dependency-error block. Compatibility recommendations and warnings appear only after selecting a result; actual read/validation errors remain visible.
6. [P2, resolved] FlowLayout could wrap documentation into a clipped second line in narrow windows. Short localized View docs copy and a separate aligned action row keep both documentation and Add/Update within their container.
7. [P1, resolved] The attempted native scrolling-tab policy rendered blank labels with the 2023.1 test look and feel. `packages-light-wide-before-native-tabs.png` and `search-selected-dark-wide-before-native-tabs.png` record the failed iteration. Reverting to native wrapping tabs restores every label; final wide, narrow and Chinese captures show the working navigation.

## Fidelity and behavior

- Typography: native IDE fonts, readable row padding and section labels remain in use. The classic test look and feel differs from the newer IDE in the reference; the implementation follows the installed look and feel. Long descriptions/statuses may use native ellipsis, with full text preserved in tooltips.
- Spacing: consistent outer padding and section gaps; selectors align; table headers and buttons fit. Search controls remain separate from scrollable content, and the divider lets users allocate space to results or settings.
- Colors: borders use `JBColor.border()` and content uses native list/text backgrounds. Public package names retain link colors. Empty selection is neutral; actual errors and SDK warnings retain their semantic colors.
- Artwork: no raster, vector, icon or dependency assets were added. Existing native search-row icons and controls remain unchanged.
- Copy: new headings, placeholders and View docs / Use compatible version captions are localized. Pubspec keys, dependency section values and version requirements retain their original values.
- Layout regression checks cover both themes and widths, complete table headers, appropriate horizontal scrolling, unclipped buttons/selectors and access to the compatible-version action by scrolling the settings.
- Full isolated validation: 370 tests, 357 passed, 13 optional Dart integration tests skipped, no failures or errors. `buildPlugin` passed with version 2.0.1. The `search-selected-{light,dark}-{narrow,wide}-sdk.png` captures show the compatible-version action after scrolling.
- Behavior checks cover blank queries, selected releases, adding to dev_dependencies, section-conflict rejection, package navigation and language switching while preserving the selected package and dirty asset configuration. No live pub.dev requests or Pub Get commands are issued by these checks.
- Manual keyboard/screen-reader use in a running IDE, actual network searches and cross-version Plugin Verifier remain outside this check. Existing service/installer tests cover the underlying search, cancellation and write logic.

Implementation checklist: selector layout, dependency table, search/settings grouping, empty feedback, localized copy and native-render comparison complete.

Final result: passed
