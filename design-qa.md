# Melos panel layout verification

- Source visual truth: `build/ui-preview/source-melos.png`, the screenshot supplied by the user.
- Implementation: `build/ui-preview/melos-dark-wide.png`, `melos-dark-narrow.png`, `melos-light-wide.png`, `melos-light-narrow.png` and `melos-dark-empty.png`.
- Renderer: the actual `MelosPanel` Swing component in the IntelliJ 2023.1 test framework, with sample workspace/member/script data. These are component renders, not captures of a running IDE.
- Viewports: 712 × 996 and 320 × 760 logical pixels, scaled by `JBUI.scale`. The captured previews use scale 1.
- Source pixels: 1424 × 2134 at approximately 2× density. The 144-pixel package/language/tab header is excluded because it is outside this change. The remaining 1424 × 1990 panel is normalized to 712 × 996 for comparison; the one-pixel height adjustment has no bearing on layout findings.
- State: configured workspace, root package included, nested discovery off, analyze selected. The additional test script is sample data used to check row spacing. The empty render covers the initial state and disabled Run action.
- Validation: 364 isolated tests, 351 passed, 13 optional Dart integration tests skipped, no failures. Melos save/conversion/member/command regressions and layout checks passed.

## Evidence and corrections

1. The source script region has no visible container boundary and leaves a large gap before Run script. `source-script-region.png` and `new-script-region.png` show the change to a bordered, opaque surface with the action immediately above it.
2. Initial narrow rendering retained the empty list's minimum height after loading, and the long configuration buttons wrapped into a clipped row. `melos-light-narrow-before-fix.png` records this intermediate issue. Dynamic minimum sizes, a font-aware four-row script viewport and the shorter Edit YAML label resolve it.
3. A subsequent minimum-height correction compressed content to zero when GridBagLayout selected minimum sizes for a narrow viewport. The final surface retains its current preferred height while allowing width to shrink. Layout assertions reject collapsed surfaces and clipped buttons.
4. `melos-comparison.png` places the density-normalized reference and final wide render side by side. Final sections stay top-aligned; only unused space beneath the Status section grows. The footer remains accessible at the bottom.

## Fidelity checks

- Typography: native IDE fonts and renderer spacing remain in use. The test framework's classic light/Darcula fonts differ from the newer IDE in the reference; the implementation follows the installed IDE look and feel.
- Spacing: consistent row/section gaps; scripts are bounded to four font-sized rows; configuration and execution controls are separated. Both tested widths keep button labels inside their containers.
- Colors: list opacity and viewport background are verified after switching look and feel. Borders use `JBColor.border()` and the script surface uses the IDE text-field colors, without a fixed light/dark palette.
- Assets: no new raster/vector assets or decorative icons are introduced. Native controls and existing links remain in place.
- Copy: option labels are localized as Include root package / Discover nested workspaces; persisted `useRootAsPackage` / `discoverNestedWorkspaces` keys stay unchanged. Edit YAML remains the same file-opening action.
- Behavior: existing configuration save, nested membership, conversion and command tests remain applicable. Empty scripts disable Run. Native label associations and keyboard-capable controls are preserved; screen-reader behavior and live-IDE focus are not certified by these renders.

## Result

No actionable P0/P1/P2 layout findings remain in the rendered states. Manual verification in a running 2026 IDE and cross-version Plugin Verifier remain outside this check.

Final result: passed

## Packages, Package Search and shared header

The latest verification covers the two user screenshots in `build/ui-preview/source-packages.png` and `source-search.png`, normalized to 820 × 1067 and 831 × 1070 logical pixels. Native full-window renders include light/Darcula, 320 × 760 narrow windows, empty/selected search states and Chinese labels. Full and focused comparison evidence, iteration history, behavior checks and limitations are recorded in [design-qa-packages.md](design-qa-packages.md).

The shared selectors now use aligned rows, the dependency table has readable headers/padded rows and the search settings scroll independently below a draggable divider. Native wrapping tabs resolve the blank-label issue found with the attempted scrolling-tab policy.

Final result: passed

## Selected search-row overflow

The later selected-row issue is checked against `build/ui-preview/source-search-overflow.png`. Automatic row expansion is disabled; compact rows keep package/version, summary and scores, while complete text and release requirements move into a wrapping, scrollable details surface below the dependency actions. The measured 2×-to-1× full-view comparison, focused evidence and regression checks are recorded in [design-qa-search-overflow.md](design-qa-search-overflow.md).

Final result: passed
