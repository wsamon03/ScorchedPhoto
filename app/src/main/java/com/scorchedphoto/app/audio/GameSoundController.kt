package com.scorchedphoto.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.scorchedphoto.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns every gameplay sound effect: short one-shots via [SoundPool] (shot, impact,
 * tank-death boom) plus two things [SoundPool] alone can't do - a continuous pitch-bending
 * whistle synced to a live trajectory ([WhistleOscillator]) and a per-tank looping
 * fire-crackle bed that starts/stops as tanks catch fire and burn out. Driven once per
 * frame by [com.scorchedphoto.app.game.GameLoopThread], which is the only place engine
 * state (and [com.scorchedphoto.engine.GameEvent]s) is read.
 *
 * [Singleton] for the same reason as [com.scorchedphoto.app.tts.DeathLineSpeaker]: cheap to
 * keep alive for the whole app process, and there's never a need for more than one match's
 * worth of sound at a time.
 */
@Singleton
class GameSoundController @Inject constructor(@ApplicationContext context: Context) {

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val loadedSoundIds = mutableSetOf<Int>()
    private val shootSoundId = soundPool.load(context, R.raw.sfx_shoot, 1)
    private val explosionSoundId = soundPool.load(context, R.raw.sfx_explosion, 1)
    private val tankExplosionSoundId = soundPool.load(context, R.raw.sfx_tank_explosion, 1)
    private val fireLoopSoundId = soundPool.load(context, R.raw.sfx_fire_loop, 1)

    private val whistle = WhistleOscillator()
    private var whistleStarted = false

    // tankId -> its active looping fire-crackle stream, so overlapping deaths each get
    // their own independent loop that stops only when that specific tank stops burning.
    private val burningStreams = mutableMapOf<Int, Int>()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedSoundIds += sampleId
        }
    }

    fun onShotFired() {
        play(shootSoundId, volume = 0.8f)
    }

    fun onExplosion() {
        play(explosionSoundId, volume = 1f)
    }

    fun onTankExploded() {
        play(tankExplosionSoundId, volume = 1f)
    }

    /** Called every frame with the vertical velocity of the primary in-flight projectile,
     * or null when nothing is airborne - drives the whistle's rising/falling pitch to
     * track the real trajectory rather than a fixed, pre-baked sweep. */
    fun updateWhistle(verticalVelocity: Float?) {
        if (verticalVelocity == null) {
            whistle.setActive(false)
            return
        }
        if (!whistleStarted) {
            whistle.start()
            whistleStarted = true
        }
        whistle.setActive(true)
        whistle.setFrequencyFromVerticalSpeed(verticalVelocity)
    }

    /** Called every frame with the ids of tanks currently mid-burn - starts each newly
     * burning tank's looping hiss-and-crackle stream and stops it the instant that tank's
     * burn ends. */
    fun updateBurningTanks(burningTankIds: Set<Int>) {
        if (burningTankIds.isEmpty() && burningStreams.isEmpty()) return
        val toStop = burningStreams.keys - burningTankIds
        for (tankId in toStop) {
            burningStreams.remove(tankId)?.let { soundPool.stop(it) }
        }
        val toStart = burningTankIds - burningStreams.keys
        for (tankId in toStart) {
            if (fireLoopSoundId !in loadedSoundIds) continue
            val streamId = soundPool.play(fireLoopSoundId, FIRE_LOOP_VOLUME, FIRE_LOOP_VOLUME, 1, -1, 1f)
            if (streamId != 0) burningStreams[tankId] = streamId
        }
    }

    private fun play(soundId: Int, volume: Float) {
        if (soundId !in loadedSoundIds) return
        soundPool.play(soundId, volume, volume, 0, 0, 1f)
    }

    companion object {
        private const val MAX_STREAMS = 8
        private const val FIRE_LOOP_VOLUME = 0.55f
    }
}
