package com.mocaris.plugin.flutter.tools.dependencies.navigation

import org.junit.Assert.*
import org.junit.Test

class DartPackageReferencesTest {
    @Test fun findsDirectDeferredExportAndConditionalReferences() {
        val source = """
            library app;
            import 'dart:io';
            import 'package:_shared/api.dart';
            import 'local.dart' if (dart.library.io) 'package:io_client/client.dart'
              if (dart.library.html) 'package:web_client/client.dart';
            import r'package:deferred_client/client.dart' deferred as client;
            export "package:public_api/api.dart" show Api;
            part 'model.g.dart';
            void main() {}
        """.trimIndent()
        assertEquals(setOf("_shared", "io_client", "web_client", "deferred_client", "public_api"), DartPackageReferences.parse(source))
    }

    @Test fun commentsAndBodyStringsDoNotCountAsImports() {
        val source = """
            /* import 'package:comment/a.dart'; /* nested */ */
            // export 'package:comment/b.dart';
            import 'package:real/a.dart'; // package:unused/unused.dart
            void main() { print("import 'package:string/a.dart';"); }
        """.trimIndent()
        assertEquals(setOf("real"), DartPackageReferences.parse(source))
        assertEquals(emptySet<String>(), DartPackageReferences.parse("// package:unused/a.dart\nconst message = 'package:unused/a.dart';"))
    }

    @Test fun annotatedLibrariesTripleQuotedUrisAndShebangsAreHandled() {
        val source = "#! /usr/bin/env dart\n@TestOn('vm')\nlibrary;\nimport '''package:foo/foo.dart''';"
        assertEquals(setOf("foo"), DartPackageReferences.parse(source))
        assertEquals(setOf("foo"), DartPackageReferences.parse("\uFEFF" + source))
    }

    @Test fun unfinishedOrUnsupportedDirectivesAreInconclusive() {
        for (source in listOf("/* unfinished", "import 'package:foo/foo.dart'", "import 'package:foo", "import;", "impor",
            "import 'package:foo/\$name.dart';", "import 'package:\\u0066oo/foo.dart';")) {
            assertNull(source, DartPackageReferences.parse(source))
        }
    }
}
