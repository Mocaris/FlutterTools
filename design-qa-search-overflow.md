# Package Search selected-row overflow verification

## Visual evidence

- Source: `build/ui-preview/source-search-overflow.png`, the supplied 1222 × 1928 screenshot showing Riverpod selected and the expanded row extending past the window edge.
- Renderer: the actual FlutterTools Swing window in the IntelliJ 2023.1 test framework, with lightweight native component peers. This is not a running-IDE capture.
- Source normalization: downsample the 2× reference to 611 × 964 logical pixels. The implementation is 611 × 1056; crop its 92-pixel Package/Language header to compare the same tab/page region at 611 × 964. The measured crop is saved in `search-long-reference-crop.txt`.
- Full-view comparison: `search-overflow-comparison.png`; source left, implementation right. `search-overflow-fixed.png` contains the final implementation crop.
- Focused comparison: `search-overflow-list-comparison.png` covers the result list and dependency actions at readable size.
- Extra states: `search-long-{light,dark}-{narrow,reference}.png`, with a 320 × 760 narrow viewport. The corresponding `-details.png` captures show the settings scrolled to the selected package details.
- Fixture: ten mocked results with Riverpod selected at 3.5.0, Dart ^3.12.0, full description and publisher. Dart 3.9.2 project metadata recommends 3.3.2 and retains the SDK warning. First result data matches the reference; later rows reuse sample versions/scores and are not exact Pub metadata replicas.

## Findings and corrections

1. [P1, resolved] JBList's expandable-items handler turns a truncated selected row into a floating overlay. The reference shows its description and selection surface crossing the right window boundary. Disable native automatic expansion for this list; retain normal list clipping and move full text into the details area. Regression assertions check that expansion is disabled and the list remains within its viewport.
2. [P2, resolved] Every result repeated selected/latest versions and four SDK requirement labels, producing tall rows and squeezing later results. Rows now contain package/version, one-line description and scores/publisher. Row height follows native font/icon sizes; tests bound the compact height at both widths. Version selection remains in the row, including its original mouse hit region and Alt+Down action.
3. [P2, resolved] Long descriptions and publisher names lose useful information when abbreviated. Full text, publisher and selected/latest Dart/Flutter requirements now live in a labeled, read-only details surface below the dependency actions. Text wraps and scrolls; changes of row or selected version update the details. No data is discarded.

## Fidelity and behavior checks

- Typography: native IDE fonts and bold package identity remain. Long row descriptions/publishers use native ellipsis; the separate details contain complete text. Classic test fonts differ from the newer reference IDE.
- Spacing: compact rows show more results at the same viewport; the border and divider keep list and settings distinct. Add/Update, documentation and Pub Get remain ahead of long details, and the settings retain independent vertical scrolling.
- Colors: native list backgrounds and the existing blended selection color remain; link, warning and error colors retain their existing roles.
- Artwork: existing native like, pub-points and version-arrow icons are retained without new image assets or dependencies. Live IDE icon/focus behavior is outside the headless render check.
- Copy: Selected package details and Publisher are localized. Package names, descriptions, release versions and SDK constraints retain their source values.
- Behavior: test coverage includes disabled automatic expansion, viewport bounds, version hit bounds, compact row height, full long text/publisher retention, row changes, selected/latest release details and existing dependency-section writes. New details share the existing Copy / Select All menu and label association.
- Full isolated verification: 371 tests, 358 passed, 13 optional Dart integration tests skipped, no failures or errors. The 2.0.1 plugin builds successfully.
- Iteration: the initial compact-row preview already contained the selected surface within the list. The first new regression needed to retrieve the real renderer from the panel because JBList wraps it internally; no production workaround or extra abstraction was added for this test issue.
- Limitations: automatic popup behavior in a running IDE, real pub.dev searches and cross-version Plugin Verifier were not manually exercised. Native handler state and Swing layout/render evidence confirm the implemented boundary; existing service tests cover network/cancellation behavior.

Implementation checklist: automatic expansion disabled, compact result rows, full selected-package details, release actions retained, localized copy and comparison completed.

Final result: passed
