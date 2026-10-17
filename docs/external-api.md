package com.skyd.rays.api

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import com.skyd.rays.appContext
import com.skyd.rays.di.get
import com.skyd.rays.ext.dataStore
import com.skyd.rays.ext.getOrDefault
import com.skyd.rays.ext.put
import com.skyd.rays.model.bean.ApiGrantPackageBean
import com.skyd.rays.model.bean.StickerBean
import com.skyd.rays.model.bean.TagBean
import com.skyd.rays.model.bean.StickerWithTags
import com.skyd.rays.model.db.AppDatabase
import com.skyd.rays.model.preference.ApiGrantPreference
import com.skyd.rays.model.respository.AddRepository
import kotlinx.coroutines.flow.first
import java.io.File
import com.skyd.rays.util.stickerUuidToFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ApiServiceTest {
    private val db get() = AppDatabase.getInstance(appContext)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ids = (0 until 65).map { UUID.nameUUIDFromBytes("rays-api-fixture-$it".toByteArray()).toString() }
    private var enabled = false
    private var previousGrant: ApiGrantPackageBean? = null
    private lateinit var service: IApiService
    private lateinit var connection: ServiceConnection

    @Before
    fun setUp() = runBlocking {
        enabled = appContext.dataStore.getOrDefault(ApiGrantPreference)
        previousGrant = db.apiGrantPackageDao().getAllPackage().firstOrNull { it.packageName == appContext.packageName }
        appContext.dataStore.put(ApiGrantPreference.key, true)
        db.apiGrantPackageDao().updatePackage(ApiGrantPackageBean(appContext.packageName, true))
        ids.forEachIndexed { index, uuid ->
            val file = stickerUuidToFile(uuid)
            appContext.revokeUriPermission(
                FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            val asset = when (index) {
                64 -> "api-fixture.gif"
                63 -> "api-animated.webp"
                62 -> "api-static.webp"
                61 -> "api-fixture.svg"
                else -> null
            }
            if (asset != null) {
                instrumentation.context.assets.open(asset).use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
            } else {
                val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.rgb(40 + index * 3, 120, 160))
                canvas.drawText("API $index", 24f, 140f, Paint().apply { color = Color.WHITE; textSize = 42f })
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bitmap.recycle()
            }
            db.stickerDao()._innerAddSticker(StickerBean(title = "api-fixture %03d".format(index), uuid = uuid,
                createTime = 1000L + index).apply { stickerMd5 = "api-fixture-$index" })
            db.tagDao().deleteTags(uuid)
            db.tagDao().addTags(listOf(TagBean(uuid, if (index == 64) "🧪" else "fixture", 1000L)))
        }
        val ready = CountDownLatch(1)
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IApiService.Stub.asInterface(binder)
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        check(appContext.bindService(Intent(appContext, ApiService::class.java), connection, android.content.Context.BIND_AUTO_CREATE))
        check(ready.await(5, TimeUnit.SECONDS))
    }

    @After
    fun tearDown(): Unit = runBlocking {
        if (::connection.isInitialized) appContext.unbindService(connection)
        if (InstrumentationRegistry.getArguments().getString("keepApiFixtures") != "true") {
            db.stickerDao().innerDeleteStickers(ids)
            ids.forEach { stickerUuidToFile(it).delete() }
            appContext.dataStore.put(ApiGrantPreference.key, enabled)
        }
        previousGrant?.let { db.apiGrantPackageDao().updatePackage(it) }
            ?: db.apiGrantPackageDao().deletePackage(appContext.packageName)
    }

    @Test
    fun paginationEndsWithoutRepeatingLastItem() {
        val first = page(0, 60)
        val last = page(60, 60)
        assertEquals("60", first["nextIndex"]!!.jsonPrimitive.content)
        assertEquals(60, first["data"]!!.jsonArray.size)
        assertEquals(5, last["data"]!!.jsonArray.size)
        assertNull(last["nextIndex"])
        assertEquals(65, (first["data"]!!.jsonArray + last["data"]!!.jsonArray)
            .map { it.jsonObject["sticker"]!!.jsonObject["uuid"]!!.jsonPrimitive.content }.distinct().size)
        assertEquals(0, page(65, 60)["data"]!!.jsonArray.size)
        assertEquals(0, page(Int.MAX_VALUE, 100)["data"]!!.jsonArray.size)
        assertEquals(0, page(-1, 60)["data"]!!.jsonArray.size)
        assertEquals(0, page(0, 0)["data"]!!.jsonArray.size)
    }

    @Test
    fun legacyResponseAndMetadataRemainSeparate() {
        val legacy = Json.parseToJsonElement(service.searchStickers(appContext.packageName, "api-fixture", 0, 1)).jsonObject
        assertEquals(setOf("startIndex", "size", "data"), legacy.keys)
        assertEquals(setOf("uri", "sticker", "tags"), legacy["data"]!!.jsonArray.first().jsonObject.keys)
        val emoji = Json.parseToJsonElement(service.searchStickersPage(appContext.packageName, "🧪", 0, 60)).jsonObject
        assertEquals(1, emoji["data"]!!.jsonArray.size)
        assertEquals("image/gif", emoji["data"]!!.jsonArray.first().jsonObject["mimeType"]!!.jsonPrimitive.content)
        assertEquals(65, page(0, Int.MAX_VALUE)["data"]!!.jsonArray.size)
    }

    @Test
    fun accessRequiresMatchingCallerAndBothUserGrants() = runBlocking {
        val previous = db.apiGrantPackageDao().getAllPackage().firstOrNull { it.packageName == "android" }
        try {
            db.apiGrantPackageDao().updatePackage(ApiGrantPackageBean("android", true))
            assertNull(service.searchStickersPage("android", "api-fixture", 0, 1))
        } finally {
            previous?.let { db.apiGrantPackageDao().updatePackage(it) }
                ?: db.apiGrantPackageDao().deletePackage("android")
        }
        appContext.dataStore.put(ApiGrantPreference.key, false)
        assertNull(service.searchStickersPage(appContext.packageName, "api-fixture", 0, 1))
        appContext.dataStore.put(ApiGrantPreference.key, true)
        db.apiGrantPackageDao().updatePackage(ApiGrantPackageBean(appContext.packageName, false))
        assertNull(service.searchStickersPage(appContext.packageName, "api-fixture", 0, 1))
        db.apiGrantPackageDao().updatePackage(ApiGrantPackageBean(appContext.packageName, true))
        assertNotNull(service.searchStickersPage(appContext.packageName, "api-fixture", 0, 1))
    }

    @Test
    fun packageApprovalUsesExactMatching(): Unit = runBlocking {
        val packageName = "test.rays.api.fixture"
        val dao = db.apiGrantPackageDao()
        val previous = dao.getAllPackage().firstOrNull { it.packageName == packageName }
        try {
            dao.updatePackage(ApiGrantPackageBean(packageName, true))
            assertTrue(dao.packageEnable(packageName))
            assertFalse(dao.packageEnable("test.rays.api_fixtur%"))
            assertFalse(dao.packageEnable("TEST.RAYS.API.FIXTURE"))
            assertEquals(0, dao.deletePackage("test.rays.api_fixtur%"))
            assertTrue(dao.packageEnable(packageName))
        } finally {
            previous?.let { dao.updatePackage(it) } ?: dao.deletePackage(packageName)
        }
    }

    @Test
    fun responseBudgetKeepsAnAdvancingCursor() = runBlocking {
        ids.takeLast(4).forEach { db.tagDao().addTags(listOf(TagBean(it, "x".repeat(80_000), 1000L))) }
        val page = page(0, 4)
        val count = page["data"]!!.jsonArray.size
        assertTrue(count in 1..3)
        assertEquals(count.toString(), page["nextIndex"]!!.jsonPrimitive.content)
    }

    @Test
    fun missingFileFailsWithoutReturningAnUnusablePage() {
        stickerUuidToFile(ids.last()).delete()
        try {
            page(0, 1)
            fail("Missing file must fail explicitly")
        } catch (_: IllegalStateException) { /* Binder-compatible failure. */ }
    }

    /** Optional emulator fixture for a separately installed client; never enabled in ordinary tests. */
    @Test
    fun externalClientFixture(): Unit = runBlocking {
        val client = InstrumentationRegistry.getArguments().getString("apiClientPackage") ?: return@runBlocking
        require(InstrumentationRegistry.getArguments().getString("keepApiFixtures") == "true")
        db.apiGrantPackageDao().updatePackage(ApiGrantPackageBean(client, true))
        InstrumentationRegistry.getArguments().getString("apiImportDirectory")?.let { directory ->
            val folder = File(directory).canonicalFile
            require(folder.parentFile == appContext.cacheDir.canonicalFile)
            folder.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { file ->
                get<AddRepository>().requestAddStickerWithTags(
                    StickerWithTags(StickerBean(title = "real-fixture ${file.name}"),
                        listOf(TagBean(tag = "real-fixture"), TagBean(tag = "cat"))),
                    Uri.fromFile(file),
                ).first()
            }
        }
    }

    private fun page(start: Int, size: Int) = Json.parseToJsonElement(
        service.searchStickersPage(appContext.packageName, "api-fixture", start, size)
    ).jsonObject
}
