### FlutterTools can help you get things done quickly in development

 ---

### Usage
`Menu > FlutterTools > AssetsSync`

#### Please configure **FlutterTools** in `flutter_tools.yaml` or `pubspec.yaml` first.  
> if `flutter_tools.yaml` and `pubspec.yaml` exist, `flutter_tools.yaml` will be used.

 ```yaml
 flutter_tools:                  # FlutterTools Config node
   assets_sync:                  # assets sync node
     excluded:
       - '*.DS_Store'            # excluded path node
       - '*.png'                 # excluded path node
       - assets/hide/            # excluded path node
       - assets/images/logo.png  # excluded path node
     sync_path:                  # assets sync path node
       - assets/images           # sync images path demo
       - assets/fonts            # sync fonts path demo
     out_path: lib/r.dart        # output class file path, you can change it, default lib/r.dart
     out_class: R                # output class name, default R
     out_extension: false        # output file extension, default false
     watch: false                # watch assets sync and generate class, default false
 ```
---
When there is a multi-power folder in the configuration directory, the multi-power resource paths will be merged, such as:

> assets/images/app_logo.png  
> assets/images/2.0x/app_logo.png

```dart
class R {
  static const String assetsImageAppLogo = "assets/images/app_logo.png";
}
```
