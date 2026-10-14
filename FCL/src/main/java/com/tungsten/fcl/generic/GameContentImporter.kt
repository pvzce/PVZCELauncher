package com.tungsten.fcl.generic

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.util.jar.JarFile

/** Copy SAF content into app-owned game directories. No downloads or archive extraction. */
object GameContentImporter {
    fun file(resolver: ContentResolver, uri: Uri, destination: File, name: String, mod: Boolean): File {
        check(destination.isDirectory || destination.mkdirs()) { "Cannot create import directory" }
        val target = GameContentPaths.child(destination, name)
        check(!target.exists()) { "File already exists: $name (rename it first)" }
        val temporary = File.createTempFile("import-", ".tmp", destination)
        try {
            resolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { input.copyTo(it) } }
                ?: throw IOException("Cannot open selected file")
            if (mod) {
                require(name.endsWith(".jar", true)) { "PVZCE mods must be JAR files" }
                JarFile(temporary).use { require(it.getJarEntry("fabric.mod.json") != null) { "Missing fabric.mod.json" } }
            }
            check(!target.exists() && temporary.renameTo(target)) { "Cannot save imported file" }
            return target
        } finally {
            temporary.delete()
        }
    }

    fun tree(resolver: ContentResolver, tree: Uri, destination: File): File {
        check(destination.isDirectory || destination.mkdirs()) { "Cannot create pack directory" }
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val name = resolver.query(rootUri, columns, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: throw IOException("Cannot read selected directory")
        val target = GameContentPaths.child(destination, name)
        check(!target.exists()) { "Pack directory already exists: $name" }
        // Keep incomplete packs outside resourcepacks/datapacks: the game enumerates every directory.
        val staging = File.createTempFile(".import-", ".tmp", destination.parentFile)
        check(staging.delete() && staging.mkdir()) { "Cannot create staging directory" }
        try {
            val budget = intArrayOf(0)
            copyTree(resolver, tree, rootId, staging, 0, budget)
            require(File(staging, "assets").isDirectory || File(staging, "data").isDirectory) {
                "Select the pack root containing assets/ or data/, not a ZIP or its parent directory"
            }
            check(!target.exists() && staging.renameTo(target)) { "Cannot save pack directory" }
            return target
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun copyTree(resolver: ContentResolver, tree: Uri, id: String, dest: File, depth: Int, budget: IntArray) {
        require(depth <= 32) { "Pack directory nesting exceeds 32" }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val cursor = resolver.query(children, columns, null, null, null) ?: throw IOException("Cannot read pack directory")
        cursor.use {
            while (it.moveToNext()) {
                require(++budget[0] <= 20000) { "Pack has more than 20000 entries" }
                val childId = it.getString(0)
                val name = it.getString(1)
                val target = GameContentPaths.child(dest, name)
                check(!target.exists()) { "Duplicate pack entry: $name" }
                if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    check(target.mkdir()) { "Cannot create pack directory" }
                    copyTree(resolver, tree, childId, target, depth + 1, budget)
                } else {
                    file(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, childId), dest, name, false)
                }
            }
        }
    }
}
