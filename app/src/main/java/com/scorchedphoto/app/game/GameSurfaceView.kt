package com.scorchedphoto.app.game

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.scorchedphoto.app.audio.GameSoundController
import com.scorchedphoto.engine.GameEngine
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The real-time rendering surface, embedded into Compose via
 * `AndroidView(factory = { GameSurfaceView(...) })`. Owns its own [GameLoopThread] tied
 * to the surface's lifecycle so the loop only runs while there's a real [SurfaceHolder]
 * to draw into.
 */
class GameSurfaceView(
    context: Context,
    private val engine: GameEngine,
    photo: Bitmap?,
    private val commandQueue: ConcurrentLinkedQueue<GameCommand>,
    private val onStateChanged: () -> Unit,
    onBurnMessageAssigned: (Int, String) -> Unit,
    private val onFireMessageAssigned: (Int, String) -> Unit,
    private val soundController: GameSoundController,
    deathPhrases: List<String>,
    private val attackPhrases: List<String>,
) : SurfaceView(context), SurfaceHolder.Callback {

    private val originalGroundY = engine.terrain.groundY.copyOf()
    private val renderer = GameRenderer(context, photo, originalGroundY, deathPhrases, onBurnMessageAssigned)
    private var loopThread: GameLoopThread? = null

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Reaching gameplay via the gallery photo picker (a separate system Activity, causing
        // a real onPause/onResume + Surface recreation) rather than the embedded camera capture
        // flow (never leaves this Activity) can leave an adaptive-refresh-rate display defaulted
        // to a low rate for this freshly created Surface - explicitly asserting the fixed 60fps
        // this game loop actually renders at avoids that throttling regardless of which screen
        // flow got the player here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.surface.setFrameRate(TARGET_FPS, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
        val thread = GameLoopThread(
            holder,
            engine,
            renderer,
            commandQueue,
            onStateChanged,
            onFireMessageAssigned,
            soundController,
            attackPhrases,
        )
        thread.running = true
        thread.start()
        loopThread = thread
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        loopThread?.let {
            it.running = false
            it.join(SURFACE_TEARDOWN_JOIN_TIMEOUT_MS)
        }
        loopThread = null
    }

    companion object {
        private const val SURFACE_TEARDOWN_JOIN_TIMEOUT_MS = 500L

        // Matches GameLoopThread's own fixed-timestep target (1/60s ticks) - see
        // surfaceCreated's setFrameRate call.
        private const val TARGET_FPS = 60f
    }
}
