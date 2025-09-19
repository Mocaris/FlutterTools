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
       lib/generated/r.dart      # output class file path, you can change it, default lib/generated/r.dart
     out_class:                  # class name node
       R                         # output class name, default R
     watch: true                 # watch assets sync and generate class, default false
 ```