package com.scorchedphoto.app.game

import android.view.SurfaceHolder
import com.scorchedphoto.app.audio.GameSoundController
import com.scorchedphoto.engine.GameEngine
import com.scorchedphoto.engine.GameEvent
import com.scorchedphoto.engine.MatchPhase
import com.scorchedphoto.engine.ai.CpuAimCalculator
import com.scorchedphoto.engine.physics.launchVelocity
import com.scorchedphoto.engine.physics.maxPowerForHealth
import com.scorchedphoto.engine.physics.normalizeAngleDeg
import com.scorchedphoto.engine.tanks.Tank
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/**
 * Fixed-timestep game loop on its own thread: drains queued [GameCommand]s, ticks
 * [engine] independent of draw rate (accumulator pattern), draws every frame via
 * [renderer], and calls [onStateChanged] only every few frames so the Compose HUD isn't
 * rebuilt 60 times a second. This is the only thread that ever mutates engine state once
 * the surface is live, so HUD input reaches it exclusively through [commandQueue].
 */
class GameLoopThread(
    private val surfaceHolder: SurfaceHolder,
    private val engine: GameEngine,
    private val renderer: GameRenderer,
    private val commandQueue: ConcurrentLinkedQueue<GameCommand>,
    private val onStateChanged: () -> Unit,
    private val soundController: GameSoundController,
) : Thread("GameLoopThread") {

    @Volatile
    var running: Boolean = false

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

    override fun run() {
        var accumulator = 0f
        var lastNanos = System.nanoTime()
        var frameCount = 0

        while (running) {
            val frameStartNanos = System.nanoTime()
            val frameDeltaSeconds = (frameStartNanos - lastNanos) / 1_000_000_000f
            accumulator += frameDeltaSeconds
            lastNanos = frameStartNanos

            drainAndApplyCommands()
            advancePendingFire(frameDeltaSeconds)

            var ticked = false
            while (accumulator >= FIXED_DT) {
                maybeTakeCpuTurn(FIXED_DT)
                engine.tick(FIXED_DT)
                accumulator -= FIXED_DT
                ticked = true
            }

            if (ticked) {
                frameCount++
                if (frameCount % STATE_UPDATE_EVERY_N_FRAMES == 0) {
                    onStateChanged()
                }
            }

            updateSound()

            val canvas = surfaceHolder.lockCanvas()
            if (canvas != null) {
                try {
                    renderer.draw(canvas, engine.terrain, engine.tanks, engine.projectiles, engine.impactEffects, engine.currentTank?.id)
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

        // Leaving the screen mid-flight/mid-burn (e.g. a win ending the match) must not
        // leave the whistle or a fire loop playing forever - both are driven purely by
        // continuous per-frame state, so with no more frames coming they'd otherwise never
        // hear the "stop" signal on their own.
        soundController.updateWhistle(null)
        soundController.updateBurningTanks(emptySet())
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
        soundController.updateBurningTanks(engine.tanks.filter { it.burning }.mapTo(mutableSetOf()) { it.id })
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
        if (pendingFireElapsed != null) return // already mid-windup for this shot
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

        val target = engine.tanks
            .filter { it.alive && it.ownerId != current.ownerId }
            .randomOrNull(cpuRandom)
            ?: return

        val aim = CpuAimCalculator.computeAim(current, target, engine.terrain, engine.wind, current.difficulty, cpuRandom)
        current.angleDeg = aim.angleDeg
        current.power = aim.power
        beginFire()
        cpuThinkingForTankId = null
    }

    private fun drainAndApplyCommands() {
        while (true) {
            val command = commandQueue.poll() ?: break
            when (command) {
                is GameCommand.SetAngle -> engine.currentTank?.angleDeg = normalizeAngleDeg(command.angleDeg)
                is GameCommand.SetWeapon -> engine.currentTank?.currentWeapon = command.weaponType
                is GameCommand.FireWithPower -> {
                    if (pendingFireElapsed == null) {
                        engine.currentTank?.power = command.power
                        beginFire()
                    }
                }
            }
        }
    }

    companion object {
        private const val FIXED_DT = 1f / 60f
        private const val STATE_UPDATE_EVERY_N_FRAMES = 6
        private const val TARGET_FRAME_NANOS = 1_000_000_000L / 60L
        private const val CPU_THINKING_SECONDS = 1.2f
        private const val FIRE_SOUND_LEAD_SECONDS = 0.25f
    }
}
