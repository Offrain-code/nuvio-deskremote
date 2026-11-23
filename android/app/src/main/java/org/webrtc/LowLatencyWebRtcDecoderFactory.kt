package org.webrtc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Build
import android.view.Surface

/**
 * Low-latency WebRTC decoder factory that preserves the official `AndroidVideoDecoder` and
 * native `VideoDecoderFallback` from `DefaultVideoDecoderFactory`, while injecting real-time
 * decoding hints (`KEY_LOW_LATENCY`, `KEY_PRIORITY`, `KEY_OPERATING_RATE`, and MediaTek/Qualcomm
 * vendor extensions) into `MediaCodec.configure`.
 */
object LowLatencyWebRtcDecoderFactory {
    internal const val KEY_VENDOR_MTK_LOW_LATENCY = "vendor.mtk-ext-dec-low-latency.enable"
    internal const val KEY_VENDOR_QTI_LOW_LATENCY = "vendor.qti-ext-dec-low-latency.enable"
    internal const val KEY_VENDOR_RTC_LOW_LATENCY = "vendor.rtc-ext-dec-low-latency.enable"
    internal const val KEY_VENDOR_HISI_LOW_LATENCY =
        "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req"

    fun create(sharedContext: EglBase.Context?): VideoDecoderFactory =
        DefaultVideoDecoderFactory(LowLatencyHardwareVideoDecoderFactory(sharedContext))

    internal fun shouldSetStandardLowLatencyKey(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.R

    internal fun buildTunedFormat(source: MediaFormat, sdkInt: Int = Build.VERSION.SDK_INT): MediaFormat {
        val mime = runCatching { source.getString(MediaFormat.KEY_MIME) }.getOrNull() ?: "video/avc"
        val width = runCatching { source.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(1920)
        val height = runCatching { source.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(1080)
        return MediaFormat.createVideoFormat(mime, width, height).apply {
            if (source.containsKey("color-format")) {
                runCatching { setInteger("color-format", source.getInteger("color-format")) }
            }
            if (shouldSetStandardLowLatencyKey(sdkInt)) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0)
            setInteger(KEY_VENDOR_MTK_LOW_LATENCY, 1)
            setInteger(KEY_VENDOR_QTI_LOW_LATENCY, 1)
            setInteger(KEY_VENDOR_RTC_LOW_LATENCY, 1)
            setInteger(KEY_VENDOR_HISI_LOW_LATENCY, 1)
        }
    }
}

private class LowLatencyHardwareVideoDecoderFactory(
    private val sharedContext: EglBase.Context?,
) : VideoDecoderFactory {
    private val delegate = HardwareVideoDecoderFactory(sharedContext)

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = delegate.supportedCodecs

    override fun createDecoder(codecType: VideoCodecInfo): VideoDecoder? {
        if (!codecType.name.equals("H264", ignoreCase = true)) {
            return delegate.createDecoder(codecType)
        }
        val selected = selectBestH264HardwareCodec() ?: return delegate.createDecoder(codecType)
        return AndroidVideoDecoder(
            LowLatencyMediaCodecWrapperFactory(),
            selected.first,
            VideoCodecMimeType.H264,
            selected.second,
            sharedContext,
        )
    }

    private data class H264CodecCandidate(
        val name: String,
        val colorFormat: Int,
        val score: Int,
    )

    private fun selectBestH264HardwareCodec(): Pair<String, Int>? = runCatching {
        val mime = VideoCodecMimeType.H264.mimeType()
        val best = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .mapNotNull { info -> evaluateH264Candidate(info, mime) }
            .maxByOrNull { it.score }
        best?.let { Pair(it.name, it.colorFormat) }
    }.getOrNull()

    private fun evaluateH264Candidate(info: MediaCodecInfo, mime: String): H264CodecCandidate? {
        val eligible = !info.isEncoder &&
            !info.name.endsWith(".secure", ignoreCase = true) &&
            MediaCodecUtils.codecSupportsType(info, VideoCodecMimeType.H264) &&
            MediaCodecUtils.isHardwareAccelerated(info)
        if (!eligible) return null

        val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return null
        val color = MediaCodecUtils.selectColorFormat(MediaCodecUtils.DECODER_COLOR_FORMATS, caps)
            ?: return null
        val supportsLowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        return H264CodecCandidate(
            name = info.name,
            colorFormat = color,
            score = if (supportsLowLatency) 2 else 1,
        )
    }
}

private class LowLatencyMediaCodecWrapperFactory : MediaCodecWrapperFactory {
    private val delegate = MediaCodecWrapperFactoryImpl()

    override fun createByCodecName(name: String): MediaCodecWrapper =
        LowLatencyMediaCodecWrapper(delegate.createByCodecName(name))
}

private class LowLatencyMediaCodecWrapper(
    private val delegate: MediaCodecWrapper,
) : MediaCodecWrapper by delegate {
    override fun configure(format: MediaFormat, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
        val tuned = runCatching { LowLatencyWebRtcDecoderFactory.buildTunedFormat(format) }.getOrNull()
        if (tuned != null) {
            try {
                delegate.configure(tuned, surface, crypto, flags)
                return
            } catch (_: MediaCodec.CodecException) {
                // Immediate fallback to the default AndroidVideoDecoder MediaFormat
            } catch (_: IllegalArgumentException) {
                // Immediate fallback to the default AndroidVideoDecoder MediaFormat
            } catch (_: IllegalStateException) {
                // Immediate fallback to the default AndroidVideoDecoder MediaFormat
            }
        }
        delegate.configure(format, surface, crypto, flags)
    }
}
