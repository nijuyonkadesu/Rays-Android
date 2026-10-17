package com.skyd.rays.api.strategy

import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.skyd.rays.api.ApiStickerWithTags
import com.skyd.rays.appContext
import com.skyd.rays.model.respository.SearchRepository
import com.skyd.rays.util.media.StickerFormatChecker
import com.skyd.rays.util.stickerUuidToFile
import java.io.IOException

object SearchStickersStrategy {
    suspend fun execute(
        repo: SearchRepository,
        keyword: String?,
        startIndex: Int,
        size: Int,
    ): List<ApiStickerWithTags> {
        return repo.requestStickerWithTagsPage(keyword.orEmpty(), startIndex, size).map {
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                stickerUuidToFile(it.sticker.uuid),
            )
            ApiStickerWithTags.fromStickerWithTags(stickerWithTags = it, uri = uri.toString())
        }
    }

    fun mimeType(sticker: ApiStickerWithTags): String {
        return try {
            val file = stickerUuidToFile(sticker.sticker.uuid)
            check(file.isFile) { "Sticker file is unavailable" }
            StickerFormatChecker.check(file.toUri(), sticker.sticker.uuid)
                ?: error("Unsupported sticker format")
        } catch (error: IOException) {
            // Binder can marshal IllegalStateException, but not IOException.
            throw IllegalStateException("Sticker file is unavailable", error)
        }
    }
}
