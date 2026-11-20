package com.mocaris.plugin.flutter.tools.assets.generation

import com.mocaris.plugin.flutter.tools.core.localization.requireLocalized
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Prepare all files before publishing; reject conflicts before replacing any output. */
object AssetsOutputWriter {
    fun isOutputEvent(path: String, outputs: Set<Path>): Boolean {
        val changed = Path.of(path).toAbsolutePath().normalize()
        return changed in outputs || changed.fileName?.toString()?.let {
            it.startsWith(".fluttertools-") && it.endsWith(".tmp") && outputs.any { output -> output.parent == changed.parent }
        } == true
    }

    fun prepare(contents: Map<Path, String>): Map<Path, Path> {
        contents.keys.forEach(AssetsGenerator::validateExistingOutput)
        val prepared = linkedMapOf<Path, Path>()
        try {
            contents.forEach { (output, content) ->
                Files.createDirectories(output.parent)
                val temp = Files.createTempFile(output.parent, ".fluttertools-", ".tmp")
                prepared[output] = temp
                Files.writeString(temp, content)
            }
            return prepared
        } catch (e: Exception) {
            cleanup(prepared)
            throw e
        }
    }

    fun publish(contents: Map<Path, String>, prepared: Map<Path, Path>, obsolete: Set<Path> = emptySet()) {
        requireLocalized(contents.keys == prepared.keys, "error.outputBatch")
        requireLocalized(contents.keys.intersect(obsolete).isEmpty(), "error.outputBatch")
        val affected = contents.keys + obsolete
        affected.forEach(AssetsGenerator::validateExistingOutput)
        val originals = affected.associateWith { if (Files.exists(it)) Files.readAllBytes(it) else null }
        val replaced = mutableListOf<Path>()
        try {
            contents.forEach { (output, content) ->
                if (originals[output]?.toString(Charsets.UTF_8) != content) {
                    move(prepared.getValue(output), output)
                    replaced.add(output)
                }
            }
            obsolete.forEach { output ->
                if (Files.deleteIfExists(output)) replaced.add(output)
            }
        } catch (e: Exception) {
            replaced.asReversed().forEach { output ->
                try {
                    val original = originals[output]
                    if (original == null) Files.deleteIfExists(output) else Files.write(output, original)
                } catch (rollback: Exception) { e.addSuppressed(rollback) }
            }
            throw e
        }
    }

    private fun move(source: Path, output: Path) {
        try { Files.move(source, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(source, output, StandardCopyOption.REPLACE_EXISTING) }
    }

    fun cleanup(prepared: Map<Path, Path>) { prepared.values.forEach { Files.deleteIfExists(it) } }
}
