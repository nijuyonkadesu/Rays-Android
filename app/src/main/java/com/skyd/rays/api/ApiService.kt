package com.skyd.rays.api

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcelable
import androidx.core.net.toUri
import com.skyd.rays.api.strategy.SearchStickersStrategy
import com.skyd.rays.appContext
import com.skyd.rays.di.inject
import com.skyd.rays.ext.dataStore
import com.skyd.rays.ext.getOrDefault
import com.skyd.rays.model.bean.StickerBean
import com.skyd.rays.model.bean.TagBean
import com.skyd.rays.model.db.dao.ApiGrantPackageDao
import com.skyd.rays.model.preference.ApiGrantPreference
import com.skyd.rays.model.respository.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ApiService : Service() {
    private val searchRepository: SearchRepository by inject()
    private val apiGrantPackageDao: ApiGrantPackageDao by inject()
    private val json: Json by inject()

    inner class ApiServiceBinder : IApiService.Stub() {
        override fun searchStickers(
            requestPackage: String?,
            keyword: String?,
            startIndex: Int,
            size: Int,
        ): String? = search(requestPackage, keyword, startIndex, size, includeMetadata = false)

        override fun searchStickersPage(
            requestPackage: String?,
            keyword: String?,
            startIndex: Int,
            size: Int,
        ): String? = search(requestPackage, keyword, startIndex, size, includeMetadata = true)

        private fun search(
            requestPackage: String?,
            keyword: String?,
            startIndex: Int,
            size: Int,
            includeMetadata: Boolean,
        ): String? {
            // Capture caller identity on the Binder thread, before changing coroutine context.
            if (requestPackage.isNullOrBlank() || requestPackage !in
                packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
            ) return null

            return runBlocking(Dispatchers.IO) {
                if (!appContext.dataStore.getOrDefault(ApiGrantPreference) ||
                    !apiGrantPackageDao.packageEnable(requestPackage)
                ) return@runBlocking null

                val limit = size.coerceIn(0, MAX_PAGE_SIZE)
                val matches = if (startIndex < 0 || limit == 0) emptyList() else
                    SearchStickersStrategy.execute(searchRepository, keyword, startIndex, limit + 1)
                var data = matches.take(limit)
                val metadata = if (includeMetadata) data.map {
                    PageSticker(it.uri, it.sticker, it.tags, SearchStickersStrategy.mimeType(it))
                } else emptyList()
                var result: String
                while (true) {
                    result = if (includeMetadata) json.encodeToString(
                        PageResult(
                            startIndex = startIndex,
                            size = data.size,
                            nextIndex = if (matches.size > data.size && data.isNotEmpty())
                                startIndex + data.size else null,
                            data = metadata.take(data.size),
                        )
                    ) else json.encodeToString(ResultWrapper(startIndex, data.size, data))
                    // Strings use UTF-16 in a Parcel. Leave room for concurrent Binder calls.
                    if (result.length * 2 <= MAX_RESPONSE_BYTES) break
                    check(data.size > 1) { "Sticker metadata exceeds the API response limit" }
                    data = data.take(data.size / 2)
                }
                // Grant only the final returned page, never the entire set of search matches.
                data.forEach {
                    grantUriPermission(
                        requestPackage, it.uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                result
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = ApiServiceBinder()

    @Parcelize
    @Serializable
    data class ResultWrapper(
        val startIndex: Int,
        val size: Int,
        val data: List<ApiStickerWithTags>,
    ) : Parcelable

    @Serializable
    data class PageResult(
        val startIndex: Int,
        val size: Int,
        val nextIndex: Int?,
        val data: List<PageSticker>,
    )

    @Serializable
    data class PageSticker(
        val uri: String,
        val sticker: StickerBean,
        val tags: List<TagBean>,
        val mimeType: String,
    )

    companion object {
        const val MAX_PAGE_SIZE = 100
        private const val MAX_RESPONSE_BYTES = 256 * 1024
    }
}
