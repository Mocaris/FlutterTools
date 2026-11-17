package com.mocaris.plugin.flutter.tools.dependencies

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** The hint pass factory moved packages in newer IDEs and is not a stable platform API. */
internal object InlayHintsRefresh {
    private val log = Logger.getInstance(InlayHintsRefresh::class.java)
    private val backend by lazy {
        resolve(InlayHintsRefresh::class.java.classLoader).also {
            if (it == null) log.warn("No compatible inlay hint cache invalidation API found")
        }
    }

    @RequiresEdt
    fun invalidate(editor: Editor) {
        if (!editor.isDisposed) backend?.invalidate(editor)
    }

    internal class Backend(private val companion: Any, private val clearStamp: Method) {
        fun invalidate(editor: Editor) {
            try {
                clearStamp.invoke(companion, editor)
            } catch (e: InvocationTargetException) {
                // Preserve cancellation and platform failures rather than wrapping them in reflection errors.
                throw e.targetException
            }
        }
    }

    internal fun resolve(loader: ClassLoader): Backend? {
        for (name in listOf(
            "com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal",
            "com.intellij.codeInsight.hints.InlayHintsPassFactory",
        )) {
            try {
                val factory = Class.forName(name, true, loader)
                val companion = factory.getField("Companion").get(null)
                val method = companion.javaClass.getMethod("clearModificationStamp", Editor::class.java)
                return Backend(companion, method)
            } catch (_: ClassNotFoundException) {
                // Try the factory used by older IDEs.
            } catch (e: ReflectiveOperationException) {
                log.debug("Incompatible inlay hint factory: $name", e)
            } catch (e: LinkageError) {
                log.debug("Unavailable inlay hint factory: $name", e)
            }
        }
        return null
    }
}
