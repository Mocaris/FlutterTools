package com.mocaris.plugin.flutter.tools.core

import org.junit.Test
import org.junit.Assert.assertEquals
import javax.xml.parsers.DocumentBuilderFactory

class PluginRegistrationTest {
    @Test fun toolsMenuGroupsNavigationCommandsAndSettingsInOrder() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse("src/main/resources/META-INF/plugin.xml")
        val groups = document.getElementsByTagName("group")
        val group = (0 until groups.length).map { groups.item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("id") == "FlutterTools.Mocaris" }
        val children = (0 until group.childNodes.length).map { group.childNodes.item(it) }
            .filterIsInstance<org.w3c.dom.Element>().filter { it.tagName in setOf("action", "separator") }
            .map { if (it.tagName == "separator") "separator" else it.getAttribute("id") }
        assertEquals(listOf("FlutterTools.OpenToolWindow", "FlutterTools.SearchPackages", "FlutterTools.OpenWorkspace", "separator",
            "AssetsSync", "FlutterTools.CheckPackages", "FlutterTools.RunPubGet", "separator",
            "FlutterTools.OpenProjectSettings", "FlutterTools.OpenSettings"), children)
    }

    @Test fun registeredClassesStillExistAfterPackageMoves() {
        for (name in listOf("plugin.xml", "fluttertools-dart.xml")) {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse("src/main/resources/META-INF/$name").getElementsByTagName("*")
            for (index in 0 until nodes.length) {
                val attributes = nodes.item(index).attributes
                for (attribute in 0 until attributes.length) {
                    val value = attributes.item(attribute).nodeValue
                    if (value.startsWith("com.mocaris.plugin.flutter.tools.")) {
                        Class.forName(value, false, javaClass.classLoader)
                    }
                }
            }
        }
    }
}
