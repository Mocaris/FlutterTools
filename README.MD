### FlutterTools can help you get things done quickly in development

---

### Usage
`Menu > FlutterTools > AssetsSync`

Please configure **FlutterTools** in `pubspec.yaml` first:

```yaml
flutter:
  assets:
    - assets/images
    - assets/fonts
    ...

flutter_tools:                  # FlutterTools Config node
  assets_sync:                  # assets sync node
    sync_path:                  # assets sync path node
      - assets/images           # sync images path demo
      - assets/fonts            # sync fonts path demo
    out_path:                   # class output path
      lib/generated/r.dart      # default class output path, you can change it
    out_class:                  # class name
      R                         # default class name, you can change it
```