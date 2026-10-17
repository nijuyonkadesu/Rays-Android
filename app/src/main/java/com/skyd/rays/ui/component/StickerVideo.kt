package com.skyd.rays.ui.component

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Platform playback keeps video support independent of the image editing pipeline. */
@Composable
fun StickerVideo(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember(context, owner, uri) {
        VideoView(context).apply {
            val videoView = this
            setMediaController(MediaController(context).apply { setAnchorView(videoView) })
            setOnPreparedListener { player ->
                player.isLooping = true
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
            }
            setVideoURI(uri)
        }
    }
    DisposableEffect(view, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                view.pause()
            } else if (event == Lifecycle.Event.ON_RESUME) {
                view.start()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view.stopPlayback()
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
