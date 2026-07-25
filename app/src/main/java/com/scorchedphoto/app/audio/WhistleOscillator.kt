package com.scorchedphoto.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Real-time sine synthesizer for the in-flight projectile whistle. A pre-baked audio clip
 * can't work here: flight duration and apex timing vary every shot (power, angle, wind,
 * terrain), so the only way for the whistle's pitch to actually track "rising to the apex,
 * falling to the explosion" is to drive an oscillator directly off the projectile's live
 * vertical speed, frame by frame - see [setFrequencyFromVerticalSpeed].
 *
 * Runs its own writer thread against a streaming [AudioTrack] rather than reusing the
 * caller's thread, since [AudioTrack.write] blocks until buffer space frees up.
 */
class WhistleOscillator {

    @Volatile private var running = false
    @Volatile private var targetFrequencyHz = MIN_FREQUENCY_HZ
    @Volatile private var targetAmplitude = 0f
    private var writerThread: Thread? = null

    /** Begins the writer thread; produces silence until [setActive] turns the whistle on. */
    fun start() {
        if (running) return
        running = true
        writerThread = Thread(::runLoop, "WhistleOscillator").apply { start() }
    }

    /** Fades the whistle in/out smoothly rather than cutting it, avoiding audible clicks. */
    fun setActive(active: Boolean) {
        targetAmplitude = if (active) WHISTLE_AMPLITUDE else 0f
    }

    /** Pitch rises as vertical speed shrinks toward zero (the apex) and falls again as it
     * grows on the way down - see class doc. */
    fun setFrequencyFromVerticalSpeed(verticalVelocity: Float) {
        val speedFraction = (abs(verticalVelocity) / MAX_SPEED_FOR_PITCH).coerceIn(0f, 1f)
        targetFrequencyHz = MAX_FREQUENCY_HZ - speedFraction * (MAX_FREQUENCY_HZ - MIN_FREQUENCY_HZ)
    }

    fun stop() {
        running = false
        writerThread?.join(THREAD_JOIN_TIMEOUT_MS)
        writerThread = null
    }

    private fun runLoop() {
        val minBufferBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBufferBytes * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        var phase = 0.0
        var currentFrequency = targetFrequencyHz
        var currentAmplitude = 0f
        val buffer = ShortArray(BUFFER_FRAMES)

        try {
            track.play()
            while (running) {
                for (i in buffer.indices) {
                    currentFrequency += (targetFrequencyHz - currentFrequency) * FREQUENCY_SMOOTHING
                    currentAmplitude += (targetAmplitude - currentAmplitude) * AMPLITUDE_SMOOTHING
                    phase += 2.0 * PI * currentFrequency / SAMPLE_RATE_HZ
                    if (phase > 2.0 * PI) phase -= 2.0 * PI
                    // A touch of second-harmonic content so it reads as an airy "whistle"
                    // rather than a pure lab-tone sine.
                    val wave = sin(phase) + 0.15 * sin(phase * 2.0)
                    buffer[i] = (wave * currentAmplitude * Short.MAX_VALUE).toInt().toShort()
                }
                track.write(buffer, 0, buffer.size)
            }
        } finally {
            track.stop()
            track.release()
        }
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 44_100
        private const val BUFFER_FRAMES = 512
        private const val THREAD_JOIN_TIMEOUT_MS = 200L

        private const val MIN_FREQUENCY_HZ = 300f
        private const val MAX_FREQUENCY_HZ = 1100f
        // Vertical-speed magnitude (engine units, see PhysicsStep.GRAVITY/POWER_SCALE)
        // beyond which pitch bottoms out - roughly a mid-power shot's launch speed.
        private const val MAX_SPEED_FOR_PITCH = 420f
        private const val WHISTLE_AMPLITUDE = 0.22f

        // Per-sample exponential smoothing toward the target frequency/amplitude - avoids
        // zipper noise/clicks from the target jumping every frame.
        private const val FREQUENCY_SMOOTHING = 0.0025f
        private const val AMPLITUDE_SMOOTHING = 0.002f
    }
}
