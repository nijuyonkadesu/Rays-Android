package com.skyd.rays.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.skyd.rays.R
import com.skyd.rays.appContext
import com.skyd.rays.ext.safeLaunch
import com.skyd.rays.ui.local.LocalPickImageMethod

@Composable
fun rememberImagePicker(
    method: String = LocalPickImageMethod.current,
    multiple: Boolean = false,
    onResult: (List<Uri?>) -> Unit
): ManagedActivityResultLauncher<*, *> {
    return when (method) {
        "PickVisualMedia" -> if (multiple) rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(),
            onResult = onResult,
        ) else rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
            onResult = { onResult(listOf(it)) },
        )

        "PickFromGallery" -> if (multiple) rememberLauncherForActivityResult(
            PickMultipleFromGallery(),
            onResult = onResult,
        ) else rememberLauncherForActivityResult(
            PickFromGallery(),
            onResult = { onResult(listOf(it)) },
        )

        "OpenDocument" -> if (multiple) rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
            onResult = onResult,
        ) else rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
            onResult = { onResult(listOf(it)) },
        )

        else -> if (multiple) rememberLauncherForActivityResult(
            GetStickerContents(),
            onResult = onResult,
        ) else rememberLauncherForActivityResult(
            GetStickerContent(),
            onResult = { onResult(listOf(it)) },
        )
    }
}

@Suppress("UNCHECKED_CAST")
fun ManagedActivityResultLauncher<*, *>.launchImagePicker(includeVideos: Boolean = false) {
    val mediaType = if (includeVideos) ActivityResultContracts.PickVisualMedia.ImageAndVideo
        else ActivityResultContracts.PickVisualMedia.ImageOnly
    val mimeTypes = if (includeVideos) arrayOf("image/*", "video/mp4", "video/webm") else arrayOf("image/*")
    when (contract) {
        is ActivityResultContracts.PickMultipleVisualMedia -> {
            (this as ManagedActivityResultLauncher<PickVisualMediaRequest, List<@JvmSuppressWildcards Uri>>)
                .safeLaunch(PickVisualMediaRequest(mediaType))
        }

        is ActivityResultContracts.PickVisualMedia -> {
            (this as ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?>)
                .safeLaunch(PickVisualMediaRequest(mediaType))
        }

        is PickMultipleFromGallery -> {
            (this as ManagedActivityResultLauncher<Boolean?, List<Uri>>)
                .safeLaunch(includeVideos)
        }

        is PickFromGallery -> {
            (this as ManagedActivityResultLauncher<Boolean?, Uri?>)
                .safeLaunch(includeVideos)
        }

        is ActivityResultContracts.OpenMultipleDocuments -> {
            (this as ManagedActivityResultLauncher<Array<String>, List<@JvmSuppressWildcards Uri>>)
                .safeLaunch(mimeTypes)
        }

        is ActivityResultContracts.OpenDocument -> {
            (this as ManagedActivityResultLauncher<Array<String>, Uri?>)
                .safeLaunch(mimeTypes)
        }

        is ActivityResultContracts.GetMultipleContents -> {
            (this as ManagedActivityResultLauncher<String, List<@JvmSuppressWildcards Uri>>)
                .safeLaunch(if (includeVideos) "*/*" else "image/*")
        }

        is ActivityResultContracts.GetContent -> {
            (this as ManagedActivityResultLauncher<String, Uri?>)
                .safeLaunch(if (includeVideos) "*/*" else "image/*")
        }

        else -> error("Unknown contract type!")
    }
}

class PickFromGallery : ActivityResultContract<Boolean?, Uri?>() {
    override fun createIntent(context: Context, input: Boolean?): Intent {
        return Intent.createChooser(
            Intent(Intent.ACTION_PICK)
                .setDataAndType((if (input == true) MediaStore.Files.getContentUri("external") else MediaStore.Images.Media.EXTERNAL_CONTENT_URI), if (input == true) "*/*" else "image/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, if (input == true) arrayOf("image/*", "video/mp4", "video/webm") else arrayOf("image/*")),
            appContext.getString(R.string.use_the_following_to_open)
        )
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return intent.takeIf { resultCode == Activity.RESULT_OK }?.data
    }
}

class PickMultipleFromGallery : ActivityResultContract<Boolean?, List<Uri>>() {
    override fun createIntent(context: Context, input: Boolean?): Intent {
        return Intent.createChooser(
            Intent(Intent.ACTION_PICK)
                .setDataAndType((if (input == true) MediaStore.Files.getContentUri("external") else MediaStore.Images.Media.EXTERNAL_CONTENT_URI), if (input == true) "*/*" else "image/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, if (input == true) arrayOf("image/*", "video/mp4", "video/webm") else arrayOf("image/*"))
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
            appContext.getString(R.string.use_the_following_to_open)
        )
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        return intent.takeIf { resultCode == Activity.RESULT_OK }?.getClipDataUris().orEmpty()
    }
}

internal fun Intent.getClipDataUris(): List<Uri> {
    // Use a LinkedHashSet to maintain any ordering that may be
    // present in the ClipData
    val resultSet = LinkedHashSet<Uri>()
    data?.let { data ->
        resultSet.add(data)
    }
    val clipData = clipData
    if (clipData == null && resultSet.isEmpty()) {
        return emptyList()
    } else if (clipData != null) {
        for (i in 0 until clipData.itemCount) {
            val uri = clipData.getItemAt(i).uri
            if (uri != null) {
                resultSet.add(uri)
            }
        }
    }
    return ArrayList(resultSet)
}

private class GetStickerContent : ActivityResultContracts.GetContent() {
    override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).apply {
        if (input == "*/*") putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/mp4", "video/webm"))
    }
}

private class GetStickerContents : ActivityResultContracts.GetMultipleContents() {
    override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).apply {
        if (input == "*/*") putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/mp4", "video/webm"))
    }
}
