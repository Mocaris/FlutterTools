package com.mocaris.plugin.flutter.tools.core

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.*
import org.junit.Test

class PackageDiscoveryEventsTest {
    private class Directory(private val location: String, private val owner: VirtualFile? = null) : LightVirtualFile(location.substringAfterLast('/')) {
        override fun isDirectory() = true
        override fun getPath() = location
        override fun getParent() = owner
        override fun findChild(name: String): VirtualFile? = null
    }
    private val root = Directory("/project")
    private val directory = Directory("/project/package", root)
    private fun create(name: String, directory: Boolean) = VFileCreateEvent(this, root, name, directory, null, null, true, null)

    @Test fun directoryCreationFromRefreshRediscoversPackagesEvenWithoutASeparatePubspecEvent() {
        val event = create("new_package", true)
        assertEquals(setOf("/project/new_package"), VfsEventPaths.paths(event))
        assertTrue(VfsEventPaths.affectsPackageDiscovery(event))
        assertTrue(VfsEventPaths.affectsPackageDiscovery(create("pubspec.yaml", false)))
        assertFalse(VfsEventPaths.affectsPackageDiscovery(create("logo.png", false)))
    }

    @Test fun movedCopiedRenamedAndDeletedTreesAlsoRefreshPackageRoots() {
        for (event in listOf(
            VFileMoveEvent(this, directory, Directory("/other")),
            VFileCopyEvent(this, directory, root, "copy"),
            VFilePropertyChangeEvent(this, directory, VirtualFile.PROP_NAME, "package", "renamed", false),
            VFileDeleteEvent(this, directory, true),
        )) assertTrue(event.toString(), VfsEventPaths.affectsPackageDiscovery(event))
        assertFalse(VfsEventPaths.affectsPackageDiscovery(
            VFilePropertyChangeEvent(this, directory, VirtualFile.PROP_WRITABLE, true, false, false)))
    }

    @Test fun savingPubspecOnlyUpdatesItsExistingPackage() {
        val pubspec = object : LightVirtualFile("pubspec.yaml") {
            override fun getPath() = "/project/pubspec.yaml"
        }
        assertFalse(VfsEventPaths.affectsPackageDiscovery(VFileContentChangeEvent(this, pubspec, 1, 2, true)))
        assertTrue(VfsEventPaths.affectsPackageDiscovery(VFileDeleteEvent(this, pubspec, true)))
    }
}
