package com.mocaris.plugin.flutter.tools.dependencies

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.mocaris.plugin.flutter.tools.core.YamlPsiTest
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class PubspecHintRefreshTest : YamlPsiTest() {
    private fun invalidate(editor: Editor) = javax.swing.SwingUtilities.invokeAndWait {
        PubspecInlayHints.invalidate(editor)
    }

    private val factory: Class<*> get() = listOf(
        "com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal",
        "com.intellij.codeInsight.hints.InlayHintsPassFactory",
    ).firstNotNullOf { name ->
        try { Class.forName(name) } catch (_: ClassNotFoundException) { null }
    }

    private fun saveStamp(editor: Editor, file: com.intellij.psi.PsiFile) {
        val companion = factory.getField("Companion").get(null)
        companion.javaClass.getMethod("putCurrentModificationStamp", Editor::class.java, com.intellij.psi.PsiFile::class.java)
            .invoke(companion, editor, file)
    }

    private fun cachedPass(file: com.intellij.psi.PsiFile, editor: Editor): Any? =
        factory.getMethod("createHighlightingPass", com.intellij.psi.PsiFile::class.java, Editor::class.java)
            .invoke(factory.getConstructor().newInstance(), file, editor)

    private class CachedEditor {
        val data = mutableMapOf<Key<*>, Any?>()
        val editor = Proxy.newProxyInstance(Editor::class.java.classLoader, arrayOf(Editor::class.java)) { _, method, args ->
            when (method.name) {
                "getUserData" -> data[args!![0]]
                "putUserData" -> { data[args!![0] as Key<*>] = args[1]; null }
                "isOneLineMode", "isDisposed" -> false
                else -> null
            }
        } as Editor
    }

    @Test fun unchangedYamlHintPassIsInvalidatedAfterDependencyRefresh() {
        val spec = yaml("name: app\ndependencies:\n  http: ^1.0.0")
        val editor = CachedEditor()
        saveStamp(editor.editor, spec)
        assertNull(cachedPass(spec, editor.editor))
        val stamp = editor.data.entries.single { it.value is Long }.key
        val originalText = spec.text
        val originalStamp = spec.modificationStamp

        invalidate(editor.editor)

        assertNull(editor.data[stamp])
        assertEquals(originalText, spec.text)
        assertEquals(originalStamp, spec.modificationStamp)
    }

    @Test fun splitEditorsCanBeInvalidatedIndependentlyWithoutDiscardingOtherEditorData() {
        val spec = yaml("name: app\ndependencies:\n  http: ^1.0.0")
        val first = CachedEditor()
        val second = CachedEditor()
        val unrelated = Key.create<String>("fluttertools.test.unrelated")
        for (cached in listOf(first, second)) {
            cached.editor.putUserData(unrelated, "keep")
            saveStamp(cached.editor, spec)
        }
        val stamp = first.data.entries.single { it.value is Long }.key
        invalidate(first.editor)
        assertNull(first.data[stamp])
        assertNotNull(second.data[stamp])
        invalidate(second.editor)
        assertNull(second.data[stamp])
        assertEquals("keep", first.editor.getUserData(unrelated))
        assertEquals("keep", second.editor.getUserData(unrelated))
    }

    @Test fun missingFactoryClassesDoNotCauseLinkageFailures() {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.endsWith("InlayHintsPassFactory") || name.endsWith("InlayHintsPassFactoryInternal")) {
                    throw ClassNotFoundException(name)
                }
                return super.loadClass(name, resolve)
            }
        }
        assertNull(InlayHintsRefresh.resolve(loader))
    }

    @Test fun disposedEditorsAreNotInvalidated() {
        val editor = Proxy.newProxyInstance(Editor::class.java.classLoader, arrayOf(Editor::class.java)) { _, method, _ ->
            if (method.name == "isDisposed") true else fail("Disposed editor was accessed: ${method.name}")
        } as Editor
        invalidate(editor)
    }
}
