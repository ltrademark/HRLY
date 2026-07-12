/*
 * HRLY, a simple hourly chime app for Android.
 * Copyright (C) 2025-2026 Ltrademark
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * The HRLY name, logo, and branding are not covered by this license.
 * See TRADEMARK.md.
 */
package com.ltrademark.hourly

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Decodes a local audio file to a small array of peak amplitudes (0..1), one per
 * bucket, for drawing a waveform. Uses only Android's built-in MediaExtractor +
 * MediaCodec so it stays dependency-free (and F-Droid friendly) and handles the
 * common encoded formats (OGG/MP3/AAC/WAV). Runs synchronously, so call it off the
 * main thread.
 */
object WaveformDecoder {

    fun decode(path: String, buckets: Int = 160): FloatArray {
        val peaks = FloatArray(buckets)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) return peaks
            extractor.selectTrack(trackIndex)

            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0) return peaks

            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        val size = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null) {
                            outBuf.order(ByteOrder.LITTLE_ENDIAN)
                            val shorts = outBuf.asShortBuffer()
                            val total = shorts.limit()
                            val frames = max(1, total / channels)
                            // Subsample frames so huge buffers stay fast.
                            val step = max(1, frames / 256)
                            var f = 0
                            while (f < frames) {
                                val frameTimeUs = info.presentationTimeUs +
                                    (f.toLong() * 1_000_000L / sampleRate)
                                val bucket = ((frameTimeUs.toDouble() / durationUs) * (buckets - 1))
                                    .toInt().coerceIn(0, buckets - 1)
                                val v = abs(shorts.get(f * channels).toInt()) / 32768f
                                if (v > peaks[bucket]) peaks[bucket] = v
                                f += step
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFormat = codec.outputFormat
                    if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        channels = max(1, outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                }
            }

            // Carry forward into any buckets a coarse buffer left empty, and normalize
            // so the tallest peak fills the view.
            var lastNonZero = 0f
            var peak = 0f
            for (i in peaks.indices) {
                if (peaks[i] > 0f) lastNonZero = peaks[i] else peaks[i] = lastNonZero
                if (peaks[i] > peak) peak = peaks[i]
            }
            if (peak > 0f) for (i in peaks.indices) peaks[i] = peaks[i] / peak
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
        return peaks
    }
}
