package com.simoneluconi.revive360.media

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.protocol.FileKind
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Copies files the user picked (a card reader, a folder) into the app's folders. The source is only
 * read. A copy that fails, or comes out at another size, is removed and counted as failed.
 */
object MediaImporter {
    data class Result(val imported: Int, val alreadyHere: Int, val skipped: Int, val failed: Int)

    fun kindOf(name: String): FileKind? = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> FileKind.PHOTO
        "mp4" -> FileKind.VIDEO
        else -> null
    }

    private fun mimeOf(kind: FileKind) = if (kind == FileKind.VIDEO) "video/mp4" else "image/jpeg"

    /** Same name and size: the file the camera numbered is already here. */
    fun isDuplicate(name: String, size: Long, existing: List<Pair<String, Long>>): Boolean = name to size in existing

    /** Every photo and video under a folder the user picked, its subfolders included, sorted by name. */
    fun filesIn(context: Context, tree: Uri): List<Uri> {
        val found = ArrayList<Pair<String, Uri>>()

        fun walk(documentId: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    when {
                        c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR -> walk(id)
                        kindOf(name) != null -> found += name to DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree))
        return found.sortedBy { it.first }.map { it.second }
    }

    suspend fun import(context: Context, uris: List<Uri>, progress: (Int) -> Unit): Result {
        val existing = PhoneMedia.list(context).map { it.name to it.size }.toMutableList()
        var imported = 0
        var alreadyHere = 0
        var skipped = 0
        var failed = 0
        uris.forEachIndexed { index, uri ->
            currentCoroutineContext().ensureActive()
            progress(index + 1)
            val source = describe(context, uri)
            val kind = source?.let { kindOf(it.name) }
            when {
                source == null || kind == null -> skipped++
                isDuplicate(source.name, source.size, existing) -> alreadyHere++
                copy(context, uri, source, kind) -> {
                    imported++
                    existing += source.name to source.size
                }
                else -> failed++
            }
        }
        EventLog.log("Import: imported $imported, already here $alreadyHere, skipped $skipped, failed $failed")
        return Result(imported, alreadyHere, skipped, failed)
    }

    private class Source(val name: String, val size: Long, val modifiedSeconds: Long)

    /** The size comes from the open file: a provider's size column can be out of date, even 0. */
    private fun describe(context: Context, uri: Uri): Source? = runCatching {
        val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val modified = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            Source(
                name = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)),
                size = size,
                modifiedSeconds = if (modified >= 0) c.getLong(modified) / 1000 else 0L,
            )
        }
    }.onFailure { EventLog.log("Import: can't read $uri: ${it.message}") }.getOrNull()

    private fun copy(context: Context, uri: Uri, source: Source, kind: FileKind): Boolean = runCatching {
        MediaSaver.publish(context, source.name, kind, mimeOf(kind), source.modifiedSeconds) { out ->
            val input = context.contentResolver.openInputStream(uri) ?: error("can't read ${source.name}")
            val written = input.use { it.copyTo(out) }
            // statSize is -1 for a file with no known size: then only a read error can tell
            check(source.size < 0 || written == source.size) { "copied $written of ${source.size} bytes" }
        }
    }.onFailure { EventLog.log("Import: ${source.name} failed: ${it.message}") }.isSuccess
}
