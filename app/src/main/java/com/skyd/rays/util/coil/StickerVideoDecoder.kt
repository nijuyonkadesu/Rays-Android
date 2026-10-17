package com.skyd.rays.util.coil

import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.video.VideoFrameDecoder

/** Private library files use UUIDs, so Coil cannot infer videos from a file extension. */
class StickerVideoDecoder : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
        val source = result.source.source()
        if (!source.request(16)) return null
        val header = source.peek().readByteArray(16)
        val webm = header.take(4) == listOf(0x1a, 0x45, 0xdf, 0xa3).map { it.toByte() }
        val mp4 = String(header, 4, 4, Charsets.US_ASCII) == "ftyp" &&
                String(header, 8, 4, Charsets.US_ASCII) in setOf("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ", "dash")
        return if (webm || mp4) VideoFrameDecoder(result.source, options) else null
    }
}
