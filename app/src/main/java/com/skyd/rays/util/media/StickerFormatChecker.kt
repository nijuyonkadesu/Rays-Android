package com.skyd.rays.util.media

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.WorkerThread
import com.skyd.rays.appContext
import com.skyd.rays.di.get
import com.skyd.rays.model.db.dao.sticker.MimeTypeDao
import com.skyd.rays.util.image.ImageFormatChecker
import com.skyd.rays.util.image.format.ImageFormat
import kotlinx.coroutines.runBlocking

/** Extends the image detector without changing image-only editing tools. */
object StickerFormatChecker {
    val videoMimeTypes = setOf("video/mp4", "video/webm")

    @WorkerThread
    fun check(uri: Uri, stickerUuid: String? = null): String? = runBlocking {
        stickerUuid?.let { get<MimeTypeDao>().getMimeTypeOrNull(it) }?.let {
            if (it in videoMimeTypes || ImageFormat.fromMimeType(it) != ImageFormat.UNDEFINED) {
                return@runBlocking it
            }
        }
        val image = appContext.contentResolver.openInputStream(uri)?.use { ImageFormatChecker.check(it) }
        if (image != null && image != ImageFormat.UNDEFINED) return@runBlocking image.toMimeType()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.takeIf {
                it in videoMimeTypes &&
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    fun extension(mimeType: String): String = when (mimeType) {
        "video/mp4" -> ".mp4"
        "video/webm" -> ".webm"
        else -> ImageFormat.fromMimeType(mimeType).toString()
    }

    @WorkerThread
    fun isVideo(uri: Uri, stickerUuid: String? = null): Boolean = check(uri, stickerUuid) in videoMimeTypes
}
