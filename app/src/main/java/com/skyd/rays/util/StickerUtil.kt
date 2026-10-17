package com.skyd.rays.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.skyd.rays.appContext
import com.skyd.rays.config.STICKER_DIR
import com.skyd.rays.config.TEMP_STICKER_DIR
import com.skyd.rays.ext.dataStore
import com.skyd.rays.ext.getOrDefault
import com.skyd.rays.model.bean.StickerWithTags
import com.skyd.rays.model.db.AppDatabase
import com.skyd.rays.model.preference.share.CopyStickerToClipboardWhenSharingPreference
import com.skyd.rays.model.preference.share.StickerExtNamePreference
import com.skyd.rays.model.preference.share.UriStringSharePreference
import com.skyd.rays.ui.service.RaysAccessibilityService
import com.skyd.rays.util.media.StickerFormatChecker
import com.skyd.rays.util.share.ShareUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds


private val scope = CoroutineScope(Dispatchers.IO)


fun Context.sendStickerByUuid(
    uuid: String,
    topActivityFullName: String = RaysAccessibilityService.topActivityFullName,
    onSuccess: (() -> Unit)? = null
) {
    val context = this
    scope.launch(Dispatchers.IO) {
        val stickerFile = externalShareStickerUuidToFile(uuid)
        sendStickersByFiles(
            stickerFiles = listOf(stickerFile),
            topActivityFullName = topActivityFullName,
            onSuccess = {
                scope.launch(Dispatchers.IO) {
                    AppDatabase.getInstance(context).stickerDao()
                        .shareStickers(uuids = listOf(uuid))
                    onSuccess?.invoke()
                }
            }
        )
    }
}

fun Context.sendStickersByUuids(
    uuids: Collection<String>,
    topActivityFullName: String = RaysAccessibilityService.topActivityFullName,
    onSuccess: (() -> Unit)? = null
) {
    scope.launch(Dispatchers.IO) {
        val stickerFiles = uuids.map { externalShareStickerUuidToFile(it) }
        sendStickersByFiles(
            stickerFiles = stickerFiles,
            topActivityFullName = topActivityFullName,
            onSuccess = {
                scope.launch(Dispatchers.IO) {
                    AppDatabase.getInstance(this@sendStickersByUuids).stickerDao()
                        .shareStickers(uuids = uuids)
                    onSuccess?.invoke()
                }
            }
        )
    }
}

fun Context.sendSticker(
    bitmap: Bitmap,
    topActivityFullName: String = RaysAccessibilityService.topActivityFullName,
    onSuccess: (() -> Unit)? = null
) {
    scope.launch(Dispatchers.IO) {
        sendStickersByFiles(
            stickerFiles = listOf(bitmap.shareToFile()),
            topActivityFullName = topActivityFullName,
            onSuccess = onSuccess
        )
    }
}

fun Context.sendStickersByFiles(
    stickerFiles: List<File>,
    topActivityFullName: String = RaysAccessibilityService.topActivityFullName,
    onSuccess: (() -> Unit)? = null
) {
    scope.launch(Dispatchers.IO) {
        val contentUris = stickerFiles.map {
            FileProvider.getUriForFile(
                this@sendStickersByFiles,
                "${packageName}.fileprovider", it
            )
        }

        if (dataStore.getOrDefault(CopyStickerToClipboardWhenSharingPreference)) {
            copyStickerToClipboard(*contentUris.toTypedArray())
        }

        with(AppDatabase.getInstance(this@sendStickersByFiles)) {
            if (dataStore.getOrDefault(UriStringSharePreference)) {
                contentUris.shareStickerUriString(
                    context = this@sendStickersByFiles,
                    packages = uriStringSharePackageDao().getAllPackage()
                        .filter { it.enabled }
                        .map { it.packageName }
                )
            }
        }

        val mimeTypes = contentUris.mapNotNull { StickerFormatChecker.check(it, it.lastPathSegment) }
        val mimetype = when {
            mimeTypes.distinct().size == 1 -> mimeTypes.first()
            mimeTypes.all { it.startsWith("image/") } -> "image/*"
            mimeTypes.all { it.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }
        withContext(Dispatchers.Main) {
            ShareUtil.share(
                context = this@sendStickersByFiles,
                uris = contentUris,
                mimetype = mimetype,
                topActivityFullName = topActivityFullName,
            )
        }
    }.invokeOnCompletion {
        onSuccess?.invoke()
    }
}

fun StickerWithTags.md5(): String {
    val summary = StringBuilder("$sticker\n")
    tags.forEach {
        summary.append(it).append("\n")
    }
    val md = MessageDigest.getInstance("MD5")
    return BigInteger(1, md.digest(summary.toString().toByteArray()))
        .toString(16).padStart(32, '0')
}

private fun File.deleteDirs(
    maxSize: Int = 5_242_880,
    operation: (res: Boolean, file: File) -> Boolean
) {
    scope.launch {
        // > 5MB
        if (walkTopDown().filter { it.isFile }.map { it.length() }.sum() > maxSize) {
            walkBottomUp().fold(true) { res, it -> operation(res, it) }
        }
    }
}

fun Bitmap.shareToFile(outputDir: File = appContext.TEMP_STICKER_DIR): File {
    if (!outputDir.exists()) {
        outputDir.mkdirs()
    }
    val resultFileName = "${System.currentTimeMillis()}_${Random.nextInt(0, Int.MAX_VALUE)}.png"
    val tempFile = File(outputDir, resultFileName)

    FileOutputStream(tempFile).use {
        compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    val nowTime = System.currentTimeMillis().milliseconds
    outputDir.deleteDirs { res, file ->
        // file == tempFile || file == outputDir 可以排除当前文件和TempSticker文件夹
        (file.name == resultFileName || file == outputDir ||
                nowTime - file.lastModified().milliseconds < 1.hours ||
                file.delete() || !file.exists()) && res
    }
    return tempFile
}

/**
 * 把表情包复制到临时目录
 */
fun File.copyStickerToTempFolder(fileExtension: Boolean = true): File {
    val outputDir = appContext.TEMP_STICKER_DIR
    check(outputDir.exists() || outputDir.mkdirs())
    val resultFileName = name + "_" + Random.nextInt(0, Int.MAX_VALUE) + if (fileExtension) {
        StickerFormatChecker.check(toUri(), name)?.let(StickerFormatChecker::extension).orEmpty()
    } else ""
    val resultFile = copyTo(
        target = File(outputDir, resultFileName),
        overwrite = true
    )
    val nowTime = System.currentTimeMillis().milliseconds
    outputDir.deleteDirs { res, file ->
        // file == tempFile || file == outputDir 可以排除当前文件和TempSticker文件夹
        (file.name == resultFileName || file == outputDir ||
                nowTime - file.lastModified().milliseconds < 1.hours ||
                file.delete() || !file.exists()) && res
    }
    return resultFile
}

/**
 * 针对外部应用
 */
fun externalShareStickerUuidToFile(uuid: String): File = stickerUuidToFile(uuid).let {
    if (appContext.dataStore.getOrDefault(StickerExtNamePreference)) it.copyStickerToTempFolder()
    else it
}

/**
 * 针对内部操作，针对原图片本身
 */
fun stickerUuidToFile(uuid: String): File = File(appContext.STICKER_DIR, uuid)

fun externalShareStickerUuidToUri(uuid: String): Uri =
    Uri.fromFile(externalShareStickerUuidToFile(uuid))

fun stickerUuidToUri(uuid: String): Uri = Uri.fromFile(stickerUuidToFile(uuid))

fun exportSticker(uuid: String, outputDir: Uri) {
    val originFile = stickerUuidToFile(uuid)
    val mimeType = StickerFormatChecker.check(originFile.toUri(), uuid)
        ?: throw IOException("Unsupported sticker format")
    val extensionName = StickerFormatChecker.extension(mimeType)
    val resultFileName = uuid + "_" + Random.nextInt(0, Int.MAX_VALUE) + extensionName

    val documentFile = DocumentFile.fromTreeUri(appContext, outputDir)!!
    val stickerUri: Uri = documentFile.createFile(
        mimeType,
        uuid
    )?.apply { renameTo(resultFileName) }?.uri!!
    val stickerOutputStream = appContext.contentResolver.openOutputStream(stickerUri)!!
    stickerOutputStream.use { outputStream ->
        originFile.inputStream().use { it.copyTo(outputStream) }
    }
}

/**
 * 将表情包导出到 Pictures 文件夹
 */
fun exportStickerToPictures(uri: Uri) {
    val contentResolver = appContext.contentResolver

    val mimeType = StickerFormatChecker.check(uri, uri.lastPathSegment)
        ?: throw IOException("Unsupported sticker format")
    val extensionName = StickerFormatChecker.extension(mimeType)
    val video = mimeType in StickerFormatChecker.videoMimeTypes
    val filename = uri.lastPathSegment + "_" + Random.nextInt(0, Int.MAX_VALUE) + extensionName

    var outputStream: OutputStream
    var imageUri: Uri?
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Rays")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    contentResolver.also { resolver ->
        imageUri = resolver.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        outputStream = imageUri?.let { resolver.openOutputStream(it) }
            ?: throw IOException("Can not write file")
        resolver.openInputStream(uri)?.use { inputStream -> inputStream.copyTo(outputStream) }
    }

    outputStream.close()

    contentValues.clear()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
    }
    contentResolver.update(imageUri!!, contentValues, null, null)
}

/**
 * 微信聊天框输入图片 uri 自动识别图片的功能
 */
private fun List<Uri>.shareStickerUriString(context: Context, packages: List<String>) {
    packages.forEach {
        forEach { uri ->
            context.grantUriPermission(it, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    val clipboard = ContextCompat.getSystemService(context, ClipboardManager::class.java)
    clipboard?.setPrimaryClip(
        ClipData.newPlainText(
            "Share sticker",
            if (size == 1) first().toString() else this.toString()
        )
    )
}

/**
 * 注意：此 uri 需要使用 FileProvider 提供
 */
suspend fun Context.copyStickerToClipboard(vararg uris: Uri) {
    val firstUri = uris.firstOrNull() ?: return
//    uris.forEachIndexed { index, uri ->
//        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
//        clipboard.setPrimaryClip(
//            ClipData("Sticker", arrayOf("image/*"), ClipData.Item(uri))
//        )
//    }
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val mimetypes = mutableListOf<String>()
    val contentResolver = appContext.contentResolver
    withContext(Dispatchers.IO) {
        uris.forEach { uri ->
            StickerFormatChecker.check(uri, uri.lastPathSegment)?.let { mimetypes += it }
        }
    }
    clipboard.setPrimaryClip(
        ClipData("Sticker", mimetypes.toTypedArray(), ClipData.Item(firstUri)).apply {
            for (i in 1..<uris.size) {
                addItem(ClipData.Item(uris[i]))
            }
        }
    )
}

suspend fun Context.copyStickerToClipboard(uuid: String) {
    copyStickerToClipboard(
        FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            stickerUuidToFile(uuid)
        )
    )
}