package com.mocaris.plugin.flutter.tools.core

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.core.CoreProjectEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFileFactory
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.YAMLParserDefinition
import org.jetbrains.yaml.psi.YAMLFile
import org.junit.*

abstract class YamlPsiTest {
    private lateinit var disposable: Disposable
    protected lateinit var environment: CoreProjectEnvironment
    @Before fun setup() {
        disposable = Disposer.newDisposable()
        val application = CoreApplicationEnvironment(disposable, true)
        // Newer IDE SDKs provide this cache as an application service; core fixtures do not.
        // Reflection keeps the fixture compatible with the 2023.2 baseline as well.
        try {
            val type = Class.forName("com.intellij.psi.util.ReadActionCache")
            val cache = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                when (method.name) {
                    "getProcessingContext" -> com.intellij.util.ProcessingContext()
                    "allowInWriteAction" -> when (val action = args?.firstOrNull()) {
                        is Runnable -> { action.run(); null }
                        is Function0<*> -> action.invoke()
                        else -> null
                    }
                    else -> null
                }
            }
            @Suppress("UNCHECKED_CAST")
            application.registerApplicationService(type as Class<Any>, cache)
        } catch (_: ClassNotFoundException) { /* The baseline SDK does not require this service. */ }
        application.registerFileType(YAMLFileType.YML, "yaml;yml")
        application.registerParserDefinition(YAMLParserDefinition())
        environment = CoreProjectEnvironment(disposable, application)
    }
    @After fun teardown() { Disposer.dispose(disposable) }
    protected fun yaml(text: String): YAMLFile = PsiFileFactory.getInstance(environment.project)
        .createFileFromText("pubspec.yaml", YAMLFileType.YML, text.trimIndent()) as YAMLFile
}
