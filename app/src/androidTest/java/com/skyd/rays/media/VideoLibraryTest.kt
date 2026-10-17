package com.skyd.rays.media

import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.skyd.rays.appContext
import com.skyd.rays.di.get
import com.skyd.rays.model.bean.StickerBean
import com.skyd.rays.model.bean.StickerWithTags
import com.skyd.rays.model.bean.TagBean
import com.skyd.rays.model.db.AppDatabase
import com.skyd.rays.model.respository.AddRepository
import com.skyd.rays.util.coil.StickerVideoDecoder
import com.skyd.rays.util.copyStickerToTempFolder
import com.skyd.rays.util.media.StickerFormatChecker
import com.skyd.rays.util.stickerUuidToFile
import com.skyd.rays.util.stickerUuidToUri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class VideoLibraryTest {
    @Test
    fun importPreservesVideoAndManualTagsAndGeneratesThumbnails(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val db = AppDatabase.getInstance(appContext)
        val loader = ImageLoader.Builder(appContext).components { add(StickerVideoDecoder()) }.build()
        try {
            listOf("mp4", "webm").forEach { extension ->
                val uuid = UUID.randomUUID().toString()
                val input = File(appContext.cacheDir, "$uuid.$extension")
                try {
                    instrumentation.context.assets.open("api-fixture.$extension").use { source ->
                        input.outputStream().use { source.copyTo(it) }
                    }
                    assertEquals("video/$extension", StickerFormatChecker.check(input.toUri()))
                    val result = get<AddRepository>().requestAddStickerWithTags(
                        StickerWithTags(StickerBean(title = "video-fixture", uuid = uuid),
                            listOf(TagBean(tag = "video-fixture"), TagBean(tag = "🧪"))),
                        input.toUri(),
                    ).first()
                    assertEquals(uuid, result)
                    val stored = db.stickerDao().getStickerWithTags(uuid)!!
                    assertEquals(setOf("video-fixture", "🧪"), stored.tags.map { it.tag }.toSet())
                    assertArrayEquals(input.readBytes(), stickerUuidToFile(uuid).readBytes())
                    assertEquals("video/$extension", db.mimeTypeDao().getMimeTypeOrNull(uuid))
                    val external = stickerUuidToFile(uuid).copyStickerToTempFolder()
                    assertTrue(external.name.endsWith(".$extension"))
                    assertArrayEquals(input.readBytes(), external.readBytes())
                    val preview = loader.execute(ImageRequest.Builder(appContext)
                        .data(stickerUuidToFile(uuid)).size(128).build())
                    assertTrue(preview.toString(), preview is SuccessResult)
                    val documentUri = android.provider.DocumentsContract.buildDocumentUri(
                        "${appContext.packageName}.documents", stickerUuidToFile(uuid).path)
                    val thumbnail = android.provider.DocumentsContract.getDocumentThumbnail(
                        appContext.contentResolver, documentUri, android.graphics.Point(128, 128), null)
                    assertNotNull(thumbnail)
                    requireNotNull(thumbnail).recycle()
                    assertTrue(get<AddRepository>().requestSuggestTags(stickerUuidToUri(uuid)).first().isEmpty())
                } finally {
                    db.stickerDao().innerDeleteSticker(uuid)
                    stickerUuidToFile(uuid).delete()
                    input.delete()
                }
            }
        } finally { loader.shutdown() }
    }
}
