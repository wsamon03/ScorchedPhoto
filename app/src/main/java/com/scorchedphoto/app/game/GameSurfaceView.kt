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
    private val renderer = GameRenderer(
        context = context,
        photo = photo,
        originalGroundY = originalGroundY,
        deathPhrases = deathPhrases,
        wallType = engine.wallType,
        ceilingType = engine.ceilingType,
        onBurnMessageAssigned = onBurnMessageAssigned,
    )
    private var loopThread: GameLoopThread? = null

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // See requestTargetFrameRate's doc - this early call is just a hint, since a rotation
        // can still be in flight when this fires.
        requestTargetFrameRate(holder)
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

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // MainActivity's android:configChanges handles rotation in place (no Activity/Surface
        // recreation), so this - not surfaceCreated - is where a rotation-driven resize's real,
        // settled geometry actually lands. Reasserting here matters most for players who are
        // still physically rotating the phone to landscape as this screen appears (e.g. picking
        // a portrait-oriented gallery photo while holding the phone upright to browse it, then
        // rotating right as gameplay starts) - surfaceCreated's vote can fire before that
        // rotation has settled and silently fail to stick, which is what left the display
        // flapping between 60Hz and ~10Hz for the whole match instead of holding a steady 60.
        requestTargetFrameRate(holder)
    }

    /** Requests this surface's fixed 60fps target - see surfaceChanged's doc for why this needs
     * reasserting on resize, not just once at surfaceCreated. */
    private fun requestTargetFrameRate(holder: SurfaceHolder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.surface.setFrameRate(TARGET_FPS, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
    }

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
        // requestTargetFrameRate.
        private const val TARGET_FPS = 60f
    }
}
