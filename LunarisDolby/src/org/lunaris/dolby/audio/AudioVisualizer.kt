/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.audio

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Arrays
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Captures the output mix and exposes smoothed, roughly log-spaced band levels
 * (0f..1f) so the UI can draw a live visualizer.
 *
 * Requires [android.Manifest.permission.RECORD_AUDIO]; when it is missing (or
 * the platform effect is unavailable) [start] returns false and the UI keeps
 * its idle animation. A watchdog decays the levels back to silence when the
 * capture stops, so the UI returns to idle shortly after audio stops.
 *
 * Use [acquire] / [releaseShared] to share one instance across the UI.
 */
class AudioVisualizer(
    private val bandCount: Int = BANDS,
    private val audioSession: Int = 0,
) {

    private var visualizer: Visualizer? = null
    private val smoothed = FloatArray(bandCount)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var lastUpdateMs = 0L
    @Volatile private var updateCount = 0

    private val _levels = MutableStateFlow(FloatArray(bandCount))
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()

    /** True while meaningful audio energy is present. */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val watchdog = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() - lastUpdateMs > 700L && _active.value) {
                var max = 0f
                for (i in smoothed.indices) {
                    smoothed[i] *= 0.65f
                    if (smoothed[i] > max) max = smoothed[i]
                }
                _levels.value = smoothed.copyOf()
                if (max < 0.02f) {
                    Arrays.fill(smoothed, 0f)
                    _levels.value = FloatArray(bandCount)
                    _active.value = false
                }
            }
            handler.postDelayed(this, 250L)
        }
    }

    /** @return true if capture started successfully. */
    fun start(): Boolean {
        if (visualizer != null) return true
        return try {
            val v = Visualizer(audioSession)
            v.captureSize = Visualizer.getCaptureSizeRange()[1]
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int,
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int,
                    ) {
                        if (fft != null) updateBands(fft)
                    }
                },
                Visualizer.getMaxCaptureRate(),
                false,
                true,
            )
            v.enabled = true
            visualizer = v
            lastUpdateMs = SystemClock.uptimeMillis()
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, 250L)
            Log.i(TAG, "Visualizer started (bands=$bandCount, session=$audioSession, size=${v.captureSize})")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Visualizer unavailable: ${e.message}")
            visualizer = null
            false
        }
    }

    private fun updateBands(fft: ByteArray) {
        lastUpdateMs = SystemClock.uptimeMillis()
        val bins = fft.size / 2
        if (bins <= 2) return

        val minBin = 1
        val maxBin = bins - 1
        val ratio = maxBin.toDouble() / minBin

        var peak = 0f
        for (band in 0 until bandCount) {
            val startBin = (minBin * ratio.pow(band.toDouble() / bandCount))
                .toInt().coerceIn(minBin, maxBin - 1)
            val endBin = (minBin * ratio.pow((band + 1).toDouble() / bandCount))
                .toInt().coerceIn(startBin + 1, maxBin)

            var sum = 0f
            for (b in startBin until endBin) {
                val re = fft[2 * b].toFloat()
                val im = fft[2 * b + 1].toFloat()
                sum += sqrt(re * re + im * im)
            }
            val avg = sum / (endBin - startBin)
            val level = (avg / 64f).coerceIn(0f, 1f)
            smoothed[band] = if (level > smoothed[band]) level else smoothed[band] * 0.80f
            if (smoothed[band] > peak) peak = smoothed[band]
        }

        val active = peak > 0.05f
        if (active != _active.value) _active.value = active
        _levels.value = smoothed.copyOf()

        if (++updateCount % 20 == 0) {
            Log.d(TAG, "peak=$peak active=$active bins=$bins")
        }
    }

    fun release() {
        handler.removeCallbacks(watchdog)
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {
            // ignore
        }
        visualizer = null
        Arrays.fill(smoothed, 0f)
        _levels.value = FloatArray(bandCount)
        _active.value = false
    }

    companion object {
        private const val TAG = "AudioVisualizer"
        const val BANDS = 28

        @Volatile private var shared: AudioVisualizer? = null
        private var refCount = 0

        @Synchronized
        fun acquire(bandCount: Int = BANDS, session: Int = 0): AudioVisualizer {
            val v = shared ?: AudioVisualizer(bandCount, session).also { shared = it }
            refCount++
            v.start()
            return v
        }

        @Synchronized
        fun releaseShared() {
            if (refCount > 0) refCount--
            if (refCount == 0) shared?.release()
        }
    }
}
