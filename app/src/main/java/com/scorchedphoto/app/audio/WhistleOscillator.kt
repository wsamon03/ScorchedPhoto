package com.scorchedphoto.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Real-time synthesizer for the in-flight projectile whistle, styled after a cartoon slide
 * (swanee) whistle rather than a lab-tone sine: the fundamental is mixed with a touch of
 * third-harmonic content for a reedier timbre, a light breath-noise bed, and a gentle
 * vibrato wobble - see [runLoop]. A pre-baked clip can't work for the pitch itself, though:
 * flight duration and apex timing vary every shot (power, angle, wind, terrain), so the
 * only way for it to actually track "rising to the apex, falling to the explosion" is to
 * drive the oscillator directly off the projectile's live vertical speed, frame by frame -
 * see [setFrequencyFromVerticalSpeed].
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
        var vibratoPhase = 0.0
        var currentFrequency = targetFrequencyHz
        var currentAmplitude = 0f
        val rng = Random(System.nanoTime())
        val buffer = ShortArray(BUFFER_FRAMES)

        try {
            track.play()
            while (running) {
                for (i in buffer.indices) {
                    currentFrequency += (targetFrequencyHz - currentFrequency) * FREQUENCY_SMOOTHING
                    currentAmplitude += (targetAmplitude - currentAmplitude) * AMPLITUDE_SMOOTHING

                    vibratoPhase += 2.0 * PI * VIBRATO_RATE_HZ / SAMPLE_RATE_HZ
                    if (vibratoPhase > 2.0 * PI) vibratoPhase -= 2.0 * PI
                    val vibrato = 1.0 + VIBRATO_DEPTH * sin(vibratoPhase)

                    phase += 2.0 * PI * (currentFrequency * vibrato) / SAMPLE_RATE_HZ
                    if (phase > 2.0 * PI) phase -= 2.0 * PI

                    // Fundamental + a touch of the third harmonic for a reedy, non-pure
                    // timbre, plus a whisper of breath noise - together read as a real
                    // slide whistle rather than a synth tone.
                    val tone = sin(phase) + THIRD_HARMONIC_MIX * sin(phase * 3.0)
                    val breath = (rng.nextFloat() * 2f - 1f) * BREATH_NOISE_MIX
                    val wave = (tone + breath) * WAVE_NORMALIZATION

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

        private const val MIN_FREQUENCY_HZ = 1100f
        private const val MAX_FREQUENCY_HZ = 2800f
        // Vertical-speed magnitude (engine units, see PhysicsStep.GRAVITY/POWER_SCALE)
        // beyond which pitch bottoms out - roughly a mid-power shot's launch speed.
        private const val MAX_SPEED_FOR_PITCH = 420f
        private const val WHISTLE_AMPLITUDE = 0.22f

        // Per-sample exponential smoothing toward the target frequency/amplitude - avoids
        // zipper noise/clicks from the target jumping every frame.
        private const val FREQUENCY_SMOOTHING = 0.0025f
        private const val AMPLITUDE_SMOOTHING = 0.002f

        // Cartoon slide-whistle character: a light reedy overtone, a whisper of breath
        // noise, and a gentle wobble - see runLoop.
        private const val THIRD_HARMONIC_MIX = 0.22f
        private const val BREATH_NOISE_MIX = 0.06f
        private const val WAVE_NORMALIZATION = 1f / (1f + THIRD_HARMONIC_MIX + BREATH_NOISE_MIX)
        private const val VIBRATO_RATE_HZ = 7f
        private const val VIBRATO_DEPTH = 0.025
    }
}
