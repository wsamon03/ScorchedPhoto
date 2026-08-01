package com.scorchedphoto.app.game

import android.view.SurfaceHolder
import com.scorchedphoto.app.audio.GameSoundController
import com.scorchedphoto.engine.GameEngine
import com.scorchedphoto.engine.GameEvent
import com.scorchedphoto.engine.MatchPhase
import com.scorchedphoto.engine.ai.CpuAimCalculator
import com.scorchedphoto.engine.ai.CpuWeaponSelector
import com.scorchedphoto.engine.physics.launchVelocity
import com.scorchedphoto.engine.physics.maxPowerForHealth
import com.scorchedphoto.engine.physics.normalizeAngleDeg
import com.scorchedphoto.engine.tanks.Tank
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/**
 * Fixed-timestep game loop on its own thread: drains queued [GameCommand]s, ticks
 * [engine] independent of draw rate (accumulator pattern), draws every frame via
 * [renderer], and calls [onStateChanged] every ticked frame so the Compose HUD stays as
 * responsive as the physics itself - safe to call this often since [onStateChanged]'s
 * `MutableStateFlow` only actually triggers recomposition when the published state changes,
 * not on every call. This is the only thread that ever mutates engine state once the
 * surface is live, so HUD input reaches it exclusively through [commandQueue].
 */
class GameLoopThread(
    private val surfaceHolder: SurfaceHolder,
    private val engine: GameEngine,
    private val renderer: GameRenderer,
    private val commandQueue: ConcurrentLinkedQueue<GameCommand>,
    private val onStateChanged: () -> Unit,
    private val onFireMessageAssigned: (Int, String) -> Unit,
    private val soundController: GameSoundController,
    private val attackPhrases: List<String>,
) : Thread("GameLoopThread") {

    @Volatile
    var running: Boolean = false

    // TEMP DIAGNOSTIC (see plan doc) - last frame's renderer.draw() CPU cost alone (excludes
    // lockCanvas's wait, tracked separately as lockMs below), reported into the next frame's
    // draw call since this frame's own duration isn't known until after draw() returns.
    private var lastDrawMs: Float = 0f

    private val cpuRandom = Random(System.nanoTime())
    private var cpuThinkingElapsed = 0f
    private var cpuThinkingForTankId: Int? = null

    // Seconds since a fire was triggered (sound + whistle already playing) but before the
    // projectile actually spawns/becomes visible - null when no shot is pending. See
    // beginFire/advancePendingFire: the shot sound noticeably lagged the projectile's
    // visual appearance (real device audio-pipeline latency), so instead of trying to
    // shave that lag to zero, the visual is deliberately held back by FIRE_SOUND_LEAD_SECONDS
    // so sound always and consistently leads it.
    private var pendingFireElapsed: Float? = null

    // Seconds since a fire was triggered but before the shot sound/whistle/missile
    // sequence (beginFire) actually starts - null when no pre-fire taunt is pending. See
    // beginFireSequence/advancePendingSpeech: the firing tank says a line first, the same
    // way a dying tank's burn taunt is shown and spoken before its own closing explosion,
    // and only once that's had time to play does the usual beginFire/pendingFireElapsed
    // sequence take over.
    private var pendingSpeechTankId: Int? = null
    private var pendingSpeechText: String? = null
    private var pendingSpeechElapsed: Float? = null

    override fun run() {
        var accumulator = 0f
        var lastNanos = System.nanoTime()

        while (running) {
            val frameStartNanos = System.nanoTime()
            val frameDeltaSeconds = (frameStartNanos - lastNanos) / 1_000_000_000f
            // Capped so a single slow frame (GC pause, brief scheduling hiccup) can't queue
            // up an unbounded catch-up burst of ticks below - each rendered frame only shows
            // the position after however many ticks ran, so a big burst reads as the
            // projectile visibly jumping rather than a smooth slowdown. MAX_ACCUMULATED_SECONDS
            // still allows several ticks' worth of catch-up, just not an unbounded amount.
            accumulator = (accumulator + frameDeltaSeconds).coerceAtMost(MAX_ACCUMULATED_SECONDS)
            lastNanos = frameStartNanos

            drainAndApplyCommands()
            advancePendingSpeech(frameDeltaSeconds)
            advancePendingFire(frameDeltaSeconds)

            val tickStartNanos = System.nanoTime()
            var ticked = false
            while (accumulator >= FIXED_DT) {
                maybeTakeCpuTurn(FIXED_DT)
                engine.tick(FIXED_DT)
                accumulator -= FIXED_DT
                ticked = true
            }

            if (ticked) {
                onStateChanged()
            }
            // TEMP DIAGNOSTIC (see plan doc) - remove once the gallery-photo choppiness cause
            // is found; tickMs also folds in drainAndApplyCommands/advancePendingSpeech/
            // advancePendingFire above, which are normally negligible.
            val tickMs = (System.nanoTime() - tickStartNanos) / 1_000_000f

            val soundStartNanos = System.nanoTime()
            updateSound()
            val soundMs = (System.nanoTime() - soundStartNanos) / 1_000_000f

            // TEMP DIAGNOSTIC - split out so a spike here (waiting on the display compositor,
            // gated by the panel's actual current refresh rate) can be told apart from a spike
            // in renderer.draw()'s own CPU-side Canvas work below.
            val lockStartNanos = System.nanoTime()
            val canvas = surfaceHolder.lockCanvas()
            val lockMs = (System.nanoTime() - lockStartNanos) / 1_000_000f
            if (canvas != null) {
                try {
                    val drawStartNanos = System.nanoTime()
                    renderer.draw(
                        canvas,
                        engine.terrain,
                        engine.tanks,
                        engine.projectiles,
                        engine.impactEffects,
                        engine.bounceEffects,
                        engine.currentTank?.id,
                        engine.phase,
                        pendingSpeechTankId,
                        pendingSpeechText,
                        engine.waterLevelY,
                        engine.voidRegions,
                        engine.lavaRegions,
                        tickMs,
                        soundMs,
                        lockMs,
                        lastDrawMs,
                    )
                    lastDrawMs = (System.nanoTime() - drawStartNanos) / 1_000_000f
                    // Keeps a ceiling WRAP's reappearance point matching what the player can
                    // actually see, not terrain.height itself (which can fall outside the
                    // canvas's cover-fit crop - see GameEngine.ceilingWrapDepthY's doc).
                    // Reuses this frame's own transform rather than recomputing
                    // WorldTransform.fit a second time; this thread is the sole mutator of
                    // engine state, so no synchronization is needed, and it self-corrects
                    // every frame after a resize.
                    val transform = renderer.currentTransform
                    engine.ceilingWrapDepthY = (canvas.height - transform.offsetY) / transform.scale
                    // Mirrors ceilingWrapDepthY above, but "just below the top border" instead
                    // of "near the map's true bottom" - see GameEngine.floorWrapDepthY's doc.
                    val floorBorderThickness = renderer.edgeBorderThicknessPx(canvas.width.toFloat(), canvas.height.toFloat())
                    engine.floorWrapDepthY = (floorBorderThickness - transform.offsetY) / transform.scale
                } finally {
                    surfaceHolder.unlockCanvasAndPost(canvas)
                }
            }

            val elapsedNanos = System.nanoTime() - frameStartNanos
            val sleepNanos = TARGET_FRAME_NANOS - elapsedNanos
            if (sleepNanos > 0) {
                try {
                    sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
                } catch (_: InterruptedException) {
                    // `running` being flipped false is what actually ends the loop.
                }
            }
        }

        // Leaving the screen mid-flight/mid-burn/mid-drown (e.g. a win ending the match) must
        // not leave the whistle, a fire loop, or a bubble loop playing forever - all three are
        // driven purely by continuous per-frame state, so with no more frames coming they'd
        // otherwise never hear the "stop" signal on their own.
        soundController.updateWhistle(null)
        soundController.updateBurningTanks(emptySet())
        soundController.updateDrowningTanks(emptySet())
    }

    /** Forwards this frame's engine events to one-shot sounds, and drives the two
     * continuous sounds (whistle, per-tank fire loop) off live engine state - see
     * [GameSoundController]. */
    private fun updateSound() {
        for (event in engine.drainEvents()) {
            when (event) {
                // Already played eagerly in beginFire(), ahead of the projectile actually
                // existing - see pendingFireElapsed doc.
                GameEvent.ShotFired -> Unit
                GameEvent.Impact -> soundController.onExplosion()
                is GameEvent.TankExploded -> soundController.onTankExploded()
            }
        }
        // While a fire is pending, beginFire() already set the whistle's target pitch for
        // the shot about to happen - this per-frame poll would otherwise immediately zero
        // it back out every frame until the real projectile exists (projectiles is empty
        // until engine.fire() actually runs).
        if (pendingFireElapsed == null) {
            soundController.updateWhistle(engine.projectiles.firstOrNull()?.vy)
        }
        val burningTankIds = if (engine.tanks.any { it.burning }) {
            engine.tanks.filter { it.burning }.mapTo(mutableSetOf()) { it.id }
        } else {
            emptySet()
        }
        soundController.updateBurningTanks(burningTankIds)
        // Only Tank.drowningBubbles drives the loop - it stops the instant the death-taunt
        // speech phase begins, matching the user's own "bubbles + sound... then the message
        // box and speech" sequencing (see GameEngine.killByDrowning's doc).
        val drowningTankIds = if (engine.tanks.any { it.drowningBubbles }) {
            engine.tanks.filter { it.drowningBubbles }.mapTo(mutableSetOf()) { it.id }
        } else {
            emptySet()
        }
        soundController.updateDrowningTanks(drowningTankIds)
    }

    /** Kicks off a shot: picks a random pre-fire taunt for [tankId] from [attackPhrases],
     * hands it to [onFireMessageAssigned] so it's spoken once (mirroring
     * [com.scorchedphoto.app.game.GameRenderer]'s death-taunt speak-once pattern), and
     * holds off the actual [beginFire] (shot sound, whistle, missile) until
     * [advancePendingSpeech] has given the line [FIRE_SPEECH_LEAD_SECONDS] to play out. If
     * [attackPhrases] is empty (every attack phrase disabled or none exist) there's nothing
     * to show/speak, so the shot fires immediately with no taunt/delay at all. */
    private fun beginFireSequence(tankId: Int) {
        val taunt = attackPhrases.randomOrNull(cpuRandom)
        if (taunt == null) {
            beginFire()
            return
        }
        pendingSpeechTankId = tankId
        pendingSpeechText = taunt
        pendingSpeechElapsed = 0f
        onFireMessageAssigned(tankId, taunt)
    }

    private fun advancePendingSpeech(dt: Float) {
        val elapsed = pendingSpeechElapsed ?: return
        val newElapsed = elapsed + dt
        if (newElapsed >= FIRE_SPEECH_LEAD_SECONDS) {
            pendingSpeechTankId = null
            pendingSpeechText = null
            pendingSpeechElapsed = null
            beginFire()
        } else {
            pendingSpeechElapsed = newElapsed
        }
    }

    /** Plays the shot sound and starts the whistle at this shot's actual launch pitch
     * (mirroring the same capped-power -> launchVelocity math [GameEngine.fire] uses, so
     * there's no audible pitch jump once the real projectile takes over next frame),
     * then holds the projectile itself back for [FIRE_SOUND_LEAD_SECONDS] - see
     * [pendingFireElapsed] doc for why. */
    private fun beginFire() {
        val shooter = engine.currentTank ?: return
        val cappedPower = shooter.power.coerceAtMost(maxPowerForHealth(shooter.health, Tank.MAX_HEALTH))
        val (_, vy) = launchVelocity(shooter.angleDeg, cappedPower)
        soundController.onShotFired()
        soundController.updateWhistle(vy)
        pendingFireElapsed = 0f
    }

    private fun advancePendingFire(dt: Float) {
        val elapsed = pendingFireElapsed ?: return
        val newElapsed = elapsed + dt
        if (newElapsed >= FIRE_SOUND_LEAD_SECONDS) {
            pendingFireElapsed = null
            engine.fire()
        } else {
            pendingFireElapsed = newElapsed
        }
    }

    /** CPU turns aren't driven by [GameCommand]s - the loop thread already owns engine
     * mutation, so it can aim and fire directly once a short "thinking" delay elapses. */
    private fun maybeTakeCpuTurn(dt: Float) {
        if (pendingSpeechElapsed != null || pendingFireElapsed != null) return // already mid-windup for this shot
        if (engine.phase != MatchPhase.AIMING) {
            cpuThinkingForTankId = null
            return
        }
        val current = engine.currentTank
        if (current == null || !current.isCpu) {
            cpuThinkingForTankId = null
            return
        }

        if (cpuThinkingForTankId != current.id) {
            cpuThinkingForTankId = current.id
            cpuThinkingElapsed = 0f
        }
        cpuThinkingElapsed += dt
        if (cpuThinkingElapsed < CPU_THINKING_SECONDS) return

        val enemies = engine.tanks.filter { it.alive && it.ownerId != current.ownerId }
        val target = enemies.randomOrNull(cpuRandom) ?: return

        current.currentWeapon = CpuWeaponSelector.selectWeapon(
            target = target,
            otherEnemiesAlive = enemies.size,
            difficulty = current.difficulty,
            ammoFor = { engine.ammoFor(current.id, it) },
            rng = cpuRandom,
        )

        val aim = CpuAimCalculator.computeAim(current, target, engine.terrain, engine.wind, current.difficulty, cpuRandom)
        current.angleDeg = aim.angleDeg
        current.power = aim.power
        beginFireSequence(current.id)
        cpuThinkingForTankId = null
    }

    private fun drainAndApplyCommands() {
        while (true) {
            val command = commandQueue.poll() ?: break
            when (command) {
                is GameCommand.SetAngle -> engine.currentTank?.angleDeg = normalizeAngleDeg(command.angleDeg)
                is GameCommand.SetWeapon -> engine.currentTank?.currentWeapon = command.weaponType
                is GameCommand.FireWithPower -> {
                    if (pendingSpeechElapsed == null && pendingFireElapsed == null) {
                        val shooter = engine.currentTank
                        shooter?.power = command.power
                        shooter?.let { beginFireSequence(it.id) }
                    }
                }
            }
        }
    }

    companion object {
        private const val FIXED_DT = 1f / 60f
        private const val TARGET_FRAME_NANOS = 1_000_000_000L / 60L
        private const val CPU_THINKING_SECONDS = 1.2f
        private const val FIRE_SOUND_LEAD_SECONDS = 0.25f

        // Spiral-of-death guard for the fixed-timestep accumulator above - see its own
        // comment. ~83ms/5 ticks of headroom absorbs a real hiccup smoothly without letting a
        // pathological stall try to simulate dozens of ticks in one rendered frame.
        private const val MAX_TICKS_PER_FRAME = 5
        private const val MAX_ACCUMULATED_SECONDS = FIXED_DT * MAX_TICKS_PER_FRAME

        // How long a pre-fire taunt's speech bubble stays up before the shot itself
        // (sound/whistle/missile) begins - matches how long a dying tank's own burn taunt
        // stays up (GameEngine.TANK_BURNING_DURATION_SECONDS) before its closing explosion,
        // so both taunts get the same amount of time to read/play out.
        private const val FIRE_SPEECH_LEAD_SECONDS = 2f
    }
}
