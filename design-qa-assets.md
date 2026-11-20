# Assets panel layout verification

- Source: `build/ui-preview/source-assets.png`, the user-supplied Assets screenshot.
- Renderer: the actual `AssetsConfigurationPanel` in the IntelliJ 2023.1 Swing test framework. These are component renders, not screenshots of a running IDE.
- Viewports: 320 × 760 and 716 × 994 logical pixels, in light and Darcula themes. Dimensions follow `JBUI.scale`; captured previews use scale 1.
- Reference normalization: the 1432 × 2072 source has its 84-pixel tab header cropped, then the remaining 1432 × 1988 panel is scaled to 716 × 994. `assets-comparison.png` places the source and implementation side by side.
- Data: ten resource directories matching the reference, new flutter_tools.yaml configuration, LoginR class and a successful real generation into the temporary package. Automatic generation remains off.

## Corrections and evidence

1. The source resource list consumes surplus height and pushes editable settings downward. A full-width, six-row resource viewport keeps settings near the top while retaining all declarations through scrolling.
2. Resources, Generation settings and Status now have native section headings. Resource declarations, exclusions and status use opaque theme surfaces and `JBColor.border()` outlines, shared with the Melos panel.
3. Edit pubspec.yaml sits beside Resources; Source sits beside the generation settings. Entry, generation directory and class fields have aligned labels and keyboard label associations. Exclusions retain their label and tooltip.
4. Save and Reload share a row; Generate now has a separate row. The 320-pixel renders and assertions confirm button labels and checkbox captions fit without outer horizontal scrolling.
5. The resource viewport uses font-based row height and no bottom padding, avoiding a partly visible seventh row. Shared content surfaces derive their minimum height from their current preferred height, preventing narrow-width collapse or stretching.

## Verification

- `assets-{light,dark}-{narrow,wide}.png` cover the final layout. `source-assets-resource-region.png` and `new-assets-resource-region.png` show the resource section comparison.
- Layout checks verify bounded resource height, complete read-only text, opaque/bordered content, unclipped controls and no outer horizontal scrollbar.
- Full isolated validation: 366 tests, 353 passed, 13 optional Dart integration tests skipped, no failures or errors; `buildPlugin` passed.
- Behavior checks exercise Save, dirty local edits, external YAML changes, canceled and confirmed Reload, and Generate into the reloaded output path. Existing package switching and validation remain unchanged.
- Native typography, colors, focusable controls and localized labels remain in use. No dependencies, artwork or new configuration options were added.
- Manual keyboard/screen-reader verification in a running IDE and cross-version Plugin Verifier are outside this check. The classic test look and feel differs from the newer IDE screenshot.

Result: no actionable layout issues remain in the rendered states.
