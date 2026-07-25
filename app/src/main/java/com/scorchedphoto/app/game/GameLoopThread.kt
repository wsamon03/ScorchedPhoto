package com.scorchedphoto.app.game

import android.view.SurfaceHolder
import com.scorchedphoto.app.audio.GameSoundController
import com.scorchedphoto.engine.GameEngine
import com.scorchedphoto.engine.GameEvent
import com.scorchedphoto.engine.MatchPhase
import com.scorchedphoto.engine.ai.CpuAimCalculator
import com.scorchedphoto.engine.physics.normalizeAngleDeg
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

    override fun run() {
        var accumulator = 0f
        var lastNanos = System.nanoTime()
        var frameCount = 0

        while (running) {
            val frameStartNanos = System.nanoTime()
            accumulator += (frameStartNanos - lastNanos) / 1_000_000_000f
            lastNanos = frameStartNanos

            drainAndApplyCommands()

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
                GameEvent.ShotFired -> soundController.onShotFired()
                GameEvent.Impact -> soundController.onExplosion()
                is GameEvent.TankExploded -> soundController.onTankExploded()
            }
        }
        soundController.updateWhistle(engine.projectiles.firstOrNull()?.vy)
        soundController.updateBurningTanks(engine.tanks.filter { it.burning }.mapTo(mutableSetOf()) { it.id })
    }

    /** CPU turns aren't driven by [GameCommand]s - the loop thread already owns engine
     * mutation, so it can aim and fire directly once a short "thinking" delay elapses. */
    private fun maybeTakeCpuTurn(dt: Float) {
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
        engine.fire()
        cpuThinkingForTankId = null
    }

    private fun drainAndApplyCommands() {
        while (true) {
            val command = commandQueue.poll() ?: break
            when (command) {
                is GameCommand.SetAngle -> engine.currentTank?.angleDeg = normalizeAngleDeg(command.angleDeg)
                is GameCommand.SetWeapon -> engine.currentTank?.currentWeapon = command.weaponType
                is GameCommand.FireWithPower -> {
                    engine.currentTank?.power = command.power
                    engine.fire()
                }
            }
        }
    }

    companion object {
        private const val FIXED_DT = 1f / 60f
        private const val STATE_UPDATE_EVERY_N_FRAMES = 6
        private const val TARGET_FRAME_NANOS = 1_000_000_000L / 60L
        private const val CPU_THINKING_SECONDS = 1.2f
    }
}
