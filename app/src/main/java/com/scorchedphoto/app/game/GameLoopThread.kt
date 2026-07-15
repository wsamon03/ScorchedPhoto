package com.scorchedphoto.app.game

import android.view.SurfaceHolder
import com.scorchedphoto.engine.GameEngine

/**
 * Fixed-timestep game loop on its own thread: ticks [engine] independent of draw rate
 * (accumulator pattern), draws every frame via [renderer], and calls [onStateChanged]
 * only every few frames so the Compose HUD isn't rebuilt 60 times a second.
 */
class GameLoopThread(
    private val surfaceHolder: SurfaceHolder,
    private val engine: GameEngine,
    private val renderer: GameRenderer,
    private val onStateChanged: () -> Unit,
) : Thread("GameLoopThread") {

    @Volatile
    var running: Boolean = false

    override fun run() {
        var accumulator = 0f
        var lastNanos = System.nanoTime()
        var frameCount = 0

        while (running) {
            val frameStartNanos = System.nanoTime()
            accumulator += (frameStartNanos - lastNanos) / 1_000_000_000f
            lastNanos = frameStartNanos

            var ticked = false
            while (accumulator >= FIXED_DT) {
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

            val canvas = surfaceHolder.lockCanvas()
            if (canvas != null) {
                try {
                    renderer.draw(canvas, engine.terrain, engine.tanks, engine.projectiles)
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
    }

    companion object {
        private const val FIXED_DT = 1f / 60f
        private const val STATE_UPDATE_EVERY_N_FRAMES = 6
        private const val TARGET_FRAME_NANOS = 1_000_000_000L / 60L
    }
}
