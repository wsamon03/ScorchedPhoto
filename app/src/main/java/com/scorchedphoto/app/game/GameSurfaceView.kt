package com.scorchedphoto.app.game

import android.content.Context
import android.graphics.Bitmap
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
) : SurfaceView(context), SurfaceHolder.Callback {

    private val originalGroundY = engine.terrain.groundY.copyOf()
    private val renderer = GameRenderer(context, photo, originalGroundY, onBurnMessageAssigned)
    private var loopThread: GameLoopThread? = null

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val thread = GameLoopThread(holder, engine, renderer, commandQueue, onStateChanged, onFireMessageAssigned, soundController)
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
    }
}
