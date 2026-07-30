package com.scorchedphoto.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.scorchedphoto.app.R
import com.scorchedphoto.app.settings.AudioSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
class GameSoundController @Inject constructor(
    @ApplicationContext context: Context,
    audioSettingsRepository: AudioSettingsRepository,
) {

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
    private val bubbleLoopSoundId = soundPool.load(context, R.raw.sfx_bubble_loop, 1)

    private val whistle = WhistleOscillator()
    private var whistleStarted = false

    // tankId -> its active looping fire-crackle stream, so overlapping deaths each get
    // their own independent loop that stops only when that specific tank stops burning.
    // ConcurrentHashMap rather than a plain map: written from the game-loop thread
    // (updateBurningTanks) but also iterated from the settings-collector coroutine below.
    private val burningStreams = ConcurrentHashMap<Int, Int>()

    // Mirrors burningStreams above, but for FloorType.WATER's drowning bubble loop (see
    // updateDrowningTanks) - a separate map since a tank is never simultaneously burning and
    // drowning (killOrDrown routes to exactly one sequence), but kept independent regardless
    // so the two loops' own start/stop bookkeeping never has to know about each other.
    private val drowningStreams = ConcurrentHashMap<Int, Int>()

    // Read on the game-loop thread (every play()/updateWhistle() call), written from the
    // settings collector below - plain @Volatile rather than a suspend read, since this
    // class's whole public API must stay synchronous/non-blocking for its caller (see class
    // doc: driven once per frame from GameLoopThread).
    @Volatile private var sfxVolume = 1f

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedSoundIds += sampleId
        }
        // Lives for the app process, same as this singleton itself - never cancelled, same
        // rationale as DeathLineSpeaker's speechExecutor.
        CoroutineScope(Dispatchers.Default).launch {
            audioSettingsRepository.settings.collect { settings ->
                sfxVolume = settings.sfxVolume
                whistle.volumeMultiplier = settings.sfxVolume
                // Already-looping fire-crackle/bubble streams don't otherwise notice a volume
                // change until they'd next restart, so nudge any currently playing ones.
                for (streamId in burningStreams.values) {
                    soundPool.setVolume(streamId, FIRE_LOOP_VOLUME * sfxVolume, FIRE_LOOP_VOLUME * sfxVolume)
                }
                for (streamId in drowningStreams.values) {
                    soundPool.setVolume(streamId, BUBBLE_LOOP_VOLUME * sfxVolume, BUBBLE_LOOP_VOLUME * sfxVolume)
                }
            }
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
            val volume = FIRE_LOOP_VOLUME * sfxVolume
            val streamId = soundPool.play(fireLoopSoundId, volume, volume, 1, -1, 1f)
            if (streamId != 0) burningStreams[tankId] = streamId
        }
    }

    /** Called every frame with the ids of tanks currently mid-[com.scorchedphoto.engine.tanks.Tank.drowningBubbles]
     * - starts each newly-bubbling tank's looping bubble stream and stops it the instant that
     * tank's bubble phase ends (the speech-bubble/flip-and-rise phases that follow are silent -
     * see [com.scorchedphoto.app.game.GameLoopThread]'s own doc on why only this phase drives
     * sound). Mirrors [updateBurningTanks] exactly, just for the drowning sequence instead. */
    fun updateDrowningTanks(drowningTankIds: Set<Int>) {
        if (drowningTankIds.isEmpty() && drowningStreams.isEmpty()) return
        val toStop = drowningStreams.keys - drowningTankIds
        for (tankId in toStop) {
            drowningStreams.remove(tankId)?.let { soundPool.stop(it) }
        }
        val toStart = drowningTankIds - drowningStreams.keys
        for (tankId in toStart) {
            if (bubbleLoopSoundId !in loadedSoundIds) continue
            val volume = BUBBLE_LOOP_VOLUME * sfxVolume
            val streamId = soundPool.play(bubbleLoopSoundId, volume, volume, 1, -1, 1f)
            if (streamId != 0) drowningStreams[tankId] = streamId
        }
    }

    private fun play(soundId: Int, volume: Float) {
        if (soundId !in loadedSoundIds) return
        val scaled = volume * sfxVolume
        soundPool.play(soundId, scaled, scaled, 0, 0, 1f)
    }

    companion object {
        private const val MAX_STREAMS = 8
        private const val FIRE_LOOP_VOLUME = 0.55f
        private const val BUBBLE_LOOP_VOLUME = 0.5f
    }
}
