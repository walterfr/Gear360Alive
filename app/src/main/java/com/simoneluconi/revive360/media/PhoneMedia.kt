package com.simoneluconi.revive360.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import com.simoneluconi.revive360.protocol.FileKind
import java.io.File

/** A picture or video the app saved on the phone (the "Gear360Alive" folders of [MediaSaver]). */
data class PhoneItem(
    val uri: Uri,
    val name: String,
    val kind: FileKind,
    val size: Long,
    /** Unix seconds: when it was shot (the camera's time), else when it was saved. */
    val time: Long,
) {
    /** A stitched 360 copy (equirectangular), as opposed to a camera original. */
    val isStitched: Boolean get() = isStitchedName(name)

    val baseName: String get() = name.substringBeforeLast('.')

    /** An ordinary picture made from the sphere (a framing): opens in the phone's own viewer. */
    val isFlat: Boolean get() = kind == FileKind.PHOTO && !isStitched && !isOriginalName(name)

    companion object {
        const val STITCHED_SUFFIX = "_360"
        /** The system adds " (1)" when a folder already has that name: `360_0295_360 (1).mp4`. */
        private val SYSTEM_COPY = Regex(""" \(\d+\)$""")
        /** A camera's own name: DCF, four digits last (360_0042, SAM_0042) */
        private val ORIGINAL = Regex(""".+_\d{4}""")

        private fun plainName(name: String) = name.substringBeforeLast('.').replace(SYSTEM_COPY, "")

        fun isStitchedName(name: String): Boolean = plainName(name).endsWith(STITCHED_SUFFIX)

        fun isOriginalName(name: String): Boolean = ORIGINAL.matches(plainName(name))

        /**
         * The same file whatever its case or the system's " (1)": the camera lists `SAM_0307.jpg`,
         * the copy saved from Street View is `SAM_0307.JPG`, and a second save becomes `SAM_0307 (1).jpg`.
         */
        fun savedKey(name: String): String =
            (plainName(name) + "." + name.substringAfterLast('.', "")).lowercase()
    }
}

/**
 * Reads back what [MediaSaver] published. Without a media permission the system lists the entries
 * this installation created, which is what the app saved.
 */
object PhoneMedia {
    fun list(context: Context): List<PhoneItem> {
        val items = ArrayList<PhoneItem>()
        for ((collection, kind) in listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to FileKind.PHOTO,
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to FileKind.VIDEO,
        )) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED,
            )
            context.contentResolver.query(
                collection, projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("%/${MediaSaver.FOLDER}/%"),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val taken = c.getLong(3)
                    items += PhoneItem(
                        uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                        name = c.getString(1) ?: continue,
                        kind = kind,
                        size = c.getLong(2),
                        time = if (taken > 0) taken / 1000 else c.getLong(4),
                    )
                }
            }
        }
        return items.sortedWith(compareByDescending<PhoneItem> { it.time }.thenByDescending { it.name })
    }

    fun thumbnail(context: Context, item: PhoneItem): Bitmap? =
        runCatching { context.contentResolver.loadThumbnail(item.uri, Size(THUMB_WIDTH, THUMB_WIDTH / 2), null) }.getOrNull()

    /** A copy in the app's cache, for the readers that want a file. */
    fun local(context: Context, item: PhoneItem): File {
        val dir = File(context.cacheDir, "phone").apply { mkdirs() }
        val file = File(dir, "${item.uri.lastPathSegment}_${item.size}_${item.name}")
        if (file.exists() && file.length() == item.size) return file
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "${file.name}.part")
        context.contentResolver.openInputStream(item.uri)?.use { input -> part.outputStream().use { input.copyTo(it) } }
            ?: error("can't read ${item.name}")
        check(part.renameTo(file)) { "can't keep ${item.name}" }
        return file
    }

    private const val THUMB_WIDTH = 480
}
