package com.scorchedphoto.app.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.scorchedphoto.app.setup.TERRAIN_COLOR_PALETTE
import com.scorchedphoto.engine.BounceEffect
import com.scorchedphoto.engine.EdgeType
import com.scorchedphoto.engine.FloorType
import com.scorchedphoto.engine.ImpactEffect
import com.scorchedphoto.engine.MatchPhase
import com.scorchedphoto.engine.physics.Projectile
import com.scorchedphoto.engine.tanks.Tank
import com.scorchedphoto.engine.tanks.TankShape
import com.scorchedphoto.engine.terrain.FloorRegion
import com.scorchedphoto.terrain.HeightMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws one frame onto the [GameSurfaceView]'s [Canvas]: the photo as a backdrop, a dark
 * scar over any column craters have carved lower than the original terrain, projectiles,
 * fading impact flashes, and tanks (body tilted to the local slope, with a barrel
 * indicating aim - health is shown only in the HUD's [com.scorchedphoto.app.game.hud.HealthBarRow],
 * not repeated next to each tank here). Every world-space position and size is mapped
 * through a single [WorldTransform.fit] (see its doc for why: independent x/y scale
 * factors distort launch angles and motion).
 *
 * Split into three tiers, cheapest-to-invalidate first: a cached **terrain layer** (background
 * photo, crater scars, horizon line - see [redrawTerrainLayer]), a cached **static layer** on
 * top of it (tank bodies/barrels, ash piles - see [redrawStaticLayer]), and a **dynamic
 * overlay** drawn fresh every frame on top of both (projectiles, impact flashes, burning-tank
 * fire/bubble, the pre-fire speech bubble). The terrain layer only changes when the terrain
 * itself does (a crater) or the canvas resizes; the static layer additionally redraws whenever
 * [MatchPhase.FIRING] isn't active (verified against every mutation site in `GameEngine`,
 * including MIRV's split-child impacts, which always land during `RESOLVING`) since a tank's
 * turn-flash color/aim state can change then. Splitting these two apart matters because the
 * terrain layer's anti-aliased horizon/crater stroke work is real, non-trivial CPU cost on a
 * software `Canvas` (worse for a visually busy/jagged segmented terrain) that has no reason to
 * repeat every single `AIMING` frame when nothing about the terrain changed.
 */
class GameRenderer(
    private val context: Context,
    private val photo: Bitmap?,
    private val originalGroundY: IntArray,
    private val deathPhrases: List<String>,
    private val wallType: EdgeType = EdgeType.NONE,
    private val ceilingType: EdgeType = EdgeType.NONE,
    private val floorType: FloorType = FloorType.GROUND,
    private val photoUsageMode: PhotoUsageMode = PhotoUsageMode.BACKGROUND,
    private val skyLook: SkyLook = SkyLook.CLEAR,
    private val terrainColor: Int = TERRAIN_COLOR_PALETTE.first(),
    private val onBurnMessageAssigned: (tankId: Int, spokenText: String) -> Unit = { _, _ -> },
) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val craterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 40, 30, 20)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tankBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barrelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
    }
    private val projectilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val impactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 255, 160, 40) }
    private val edgeMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val edgeBorderPaint = Paint().apply { style = Paint.Style.FILL }
    private val wrapGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fireFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true; alpha = FIRE_ALPHA }
    private val ashPilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ashSpeckPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val speechBubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val speechBubbleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val speechBubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    private val regionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lavaSpeckPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val waterFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val waterWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val lavaBumpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // PhotoUsageMode.TERRAIN's procedural sky look - see drawSkyLook.
    private val skyGradientPaint = Paint()
    private val glowDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // TEMP DIAGNOSTIC (see plan doc) - remove once the gallery-photo choppiness cause is found.
    private val debugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        textSize = 28f
        textAlign = Paint.Align.LEFT
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    // Reused every call rather than allocated fresh - drawSpeechBubble runs every frame for
    // the full ~2s duration of every pre-fire/death taunt, so this is a hot path. Safe to
    // share: each call fully finishes drawing (paths are consumed synchronously by
    // canvas.drawPath) before the next call - possibly for a second burning tank - reuses them.
    private val speechBubbleFontMetrics = Paint.FontMetrics()
    private val speechBubbleRect = RectF()
    private val speechBubblePath = Path()
    private val speechBubbleTailFillPath = Path()
    private val speechBubbleTailOutlinePath = Path()

    // Reused every frame rather than allocated fresh - both rebuild from the full terrain
    // width unconditionally every frame, so a fresh Path() here was a real per-frame cost.
    private val craterPath = Path()
    private val horizonPath = Path()

    // Reused every terrain-layer redraw for PhotoUsageMode.TERRAIN/SKY's photo clip - see
    // buildTerrainClipPath. Only one of the two clip shapes (ground-down or sky-up) is ever
    // built during this renderer's lifetime, since photoUsageMode is fixed for the whole
    // match, so a single shared field suffices here (unlike craterPath/horizonPath above,
    // which are both genuinely redrawn every time).
    private val terrainClipPath = Path()

    // Reused every frame for the same reason - see the loop above.
    private val backgroundRect = RectF()

    // Reused across every bounce-mark draw call rather than allocated fresh - see
    // drawPaddedMark/drawSpringMark, which run every frame for the ~0.25s life of each
    // wall/ceiling bounce effect.
    private val edgeMarkRect = RectF()
    private val edgeMarkPath = Path()

    // Reused every call rather than allocated fresh - see drawRegionFill (Void/Lava, called
    // once per tracked region whenever the terrain layer redraws) and drawWaterOverlay/
    // drawLavaBumps (called every frame while their floor type is active).
    private val regionFillPath = Path()
    private val waterFillPath = Path()
    private val waveStrokePath = Path()

    // Each Lava FloorRegion's speck layout ("specks of black" - see FloorType's doc), keyed
    // by the region's own centerX (stable for the region's whole lifetime, unlike its
    // ever-growing radius) and generated once, deterministically, so the speckle pattern
    // doesn't flicker by being re-randomized on every redraw - see lavaSpecksFor. Mirrors
    // the ashSpecks/tankBodyPaths caching pattern above.
    private val lavaSpecks = mutableMapOf<Float, List<LavaSpeck>>()

    // Each tank's body Path, created once per tank id and reused every frame (rebuilt via
    // .reset() since a tank's cx/cy/slope-rotation genuinely can change frame to frame) -
    // see tankBodyPathFor. Avoids allocating a fresh Path per tank per frame.
    private val tankBodyPaths = mutableMapOf<Int, Path>()

    private val srcRect = photo?.let { Rect(0, 0, it.width, it.height) }

    // Decoded once and reused every frame - see assets/fire/, extracted from the source
    // GIF (48 frames @ 80ms) down to every other frame @ 160ms, same total loop length.
    private val fireFrames: List<Bitmap> by lazy {
        (0 until FIRE_FRAME_COUNT).map { i ->
            context.assets.open("fire/frame_%02d.png".format(i)).use { BitmapFactory.decodeStream(it) }
        }
    }

    // A tank's taunt is picked once, the first time it's drawn burning, and kept for the
    // whole burn rather than re-rolled every frame - see burnMessageFor. A null value means
    // "already checked, nothing to say" (deathPhrases was empty/all-disabled) - getOrPut
    // caches that too, so it's never re-checked for the rest of this tank's burn.
    private val burnMessages = mutableMapOf<Int, String?>()

    // Each ash pile's speck layout, generated once per tank (deterministically, from its
    // own id) and reused every frame - see ashSpecksFor. Without caching, redrawing fresh
    // random specks every frame would flicker.
    private val ashSpecks = mutableMapOf<Int, List<AshSpeck>>()

    // Each ash pile's mound RectF objects, created once per tank and reused every frame - see
    // ashMoundRectsFor. Unlike ashSpecks these are screen-space (built from cx/cy, which are
    // WorldTransform-derived), and the canvas can resize mid-match (configChanges handles
    // rotation without recreating the Activity), so only the objects are cached - their
    // bounds are still updated via .set(...) every frame in drawAshPile.
    private val ashMoundRects = mutableMapOf<Int, List<RectF>>()

    // Each drowning tank's own little cluster of rising bubbles - generated once
    // (deterministically, from the tank's own id) and cached, mirroring ashSpecks/
    // lavaSpecksFor's own caching pattern, so the bubble layout doesn't flicker by being
    // re-randomized every frame - see bubblesFor/drawDrowningBubbles.
    private val drowningBubbleSets = mutableMapOf<Int, List<Bubble>>()

    // Tracks how long the current tank's turn has been active, purely for the
    // start-of-turn color flash below - reset (via wall-clock nanoTime, not engine dt)
    // whenever the id passed in as currentTankId changes, so it's independent of the
    // engine's own tick cadence and keeps flashing even while the engine is paused on
    // AIMING (during which GameLoopThread doesn't call engine.tick at all).
    private var flashTankId: Int? = null
    private var flashStartNanos: Long = 0L

    // Baseline for EdgeType.WRAP's glitter twinkle animation (see drawWrapEffects) - wall-clock
    // based like flashStartNanos above, so it keeps animating smoothly even while the engine
    // itself is paused on AIMING. The two sparkle layouts (one for the wall edges, mirrored
    // between left/right; one for the ceiling edge) are generated once, deterministically, and
    // reused every frame - only their twinkle phase changes, not their positions.
    private val wrapEffectsStartNanos = System.nanoTime()
    private val wallWrapSparkles: List<WrapSparkle> by lazy { generateWrapSparkles(seed = 4242) }
    private val ceilingWrapSparkles: List<WrapSparkle> by lazy { generateWrapSparkles(seed = 9191) }

    // SkyLook.CLOUDY's cloud-puff scatter and SkyLook.NIGHT_STARS[_MOON]'s star scatter -
    // generated once, deterministically, and cached exactly like wallWrapSparkles/
    // ceilingWrapSparkles above, so the layout doesn't flicker by being re-randomized whenever
    // the terrain layer redraws. Safe to declare unconditionally even for skyLooks that never
    // use one of these - by lazy only actually runs on first access.
    private val cloudPuffs: List<CloudPuff> by lazy { generateClouds(seed = 5151) }
    private val stars: List<Star> by lazy { generateStars(seed = 6161) }

    // Same wall-clock-based animation baseline as wrapEffectsStartNanos above, but shared by
    // FloorType.WATER's rising wavy border and FloorType.LAVA's small surface waves/bumps (see
    // drawWaterOverlay/drawLavaBumps) - kept separate from wrapEffectsStartNanos since it's
    // conceptually a different set of animations, even though the underlying pattern is the same.
    private val floorEffectsStartNanos = System.nanoTime()

    // The cached static-layer bitmap/canvas (tank bodies/barrels, ash piles, drawn on top of
    // the terrain layer below) plus the WorldTransform it was drawn with - recreated only when
    // the real canvas's size changes (WorldTransform depends solely on canvas size and terrain
    // size, and terrain size is fixed for a match). staticLayerDirty forces a redraw whenever
    // any of that content could have changed - see draw()'s doc for the exact conditions.
    // IMPORTANT for future maintainers: any new mutation that changes a tank's drawn appearance
    // outside of MatchPhase.FIRING is already covered by the phase check below, but anything
    // that could change *during* FIRING needs to either avoid doing so or explicitly set
    // staticLayerDirty = true.
    private var staticLayerBitmap: Bitmap? = null
    private var staticLayerCanvas: Canvas? = null
    private var cachedTransform: WorldTransform? = null
    private var cachedCanvasWidth = -1
    private var cachedCanvasHeight = -1
    private var staticLayerDirty = true

    // The cached terrain-layer bitmap/canvas (background photo, crater scars, horizon line) -
    // see the class doc for why this is split from staticLayerBitmap above. Resized alongside
    // it, but its own dirty flag only flips on a resize or when terrain.version has actually
    // advanced (see redrawTerrainLayer) - never merely because the match phase changed.
    private var terrainLayerBitmap: Bitmap? = null
    private var terrainLayerCanvas: Canvas? = null
    private var terrainLayerDirty = true
    private var cachedTerrainVersion = -1

    /** The [WorldTransform] most recently used to draw the world (see [ensureStaticLayer]) -
     * exposed so a caller holding the real canvas dimensions but no drawing responsibilities
     * of its own (see [GameLoopThread]) can convert world coordinates to screen coordinates
     * exactly as this frame actually rendered them, without recomputing [WorldTransform.fit]
     * a second time. */
    val currentTransform: WorldTransform
        get() = cachedTransform ?: WorldTransform(1f, 0f, 0f)

    fun draw(
        canvas: Canvas,
        terrain: HeightMap,
        tanks: List<Tank>,
        projectiles: List<Projectile>,
        impactEffects: List<ImpactEffect>,
        bounceEffects: List<BounceEffect>,
        currentTankId: Int?,
        phase: MatchPhase,
        firingTankId: Int? = null,
        firingMessage: String? = null,
        waterLevelY: Float = terrain.height.toFloat(),
        voidRegions: List<FloorRegion> = emptyList(),
        lavaRegions: List<FloorRegion> = emptyList(),
        tickMs: Float = 0f,
        soundMs: Float = 0f,
        lockMs: Float = 0f,
        drawMs: Float = 0f,
    ) {
        ensureStaticLayer(canvas.width, canvas.height, terrain)
        val transform = cachedTransform!!

        // The terrain layer (photo, crater scars, horizon line, Void/Lava fills) only needs
        // redrawing when it resized (ensureStaticLayer already set terrainLayerDirty for that)
        // or the terrain itself actually changed (a crater carved during RESOLVING, including a
        // Void/Lava region's own per-round growth - see GameEngine.growRegion, which carves
        // through the same CraterCarver.carve that bumps HeightMap.version) - never merely
        // because the match phase changed, unlike the tank layer below. When it does redraw, the
        // tank layer must too, since it's drawn on top of the terrain layer's own bitmap.
        if (terrainLayerDirty || terrain.version != cachedTerrainVersion) {
            redrawTerrainLayer(terrain, transform, voidRegions, lavaRegions)
            terrainLayerDirty = false
            cachedTerrainVersion = terrain.version
            staticLayerDirty = true
        }

        if (currentTankId != flashTankId) {
            flashTankId = currentTankId
            flashStartNanos = System.nanoTime()
        }
        val flashElapsedSeconds = (System.nanoTime() - flashStartNanos) / 1_000_000_000f
        // Whether the current tank's start-of-turn flash could still be playing - driven by
        // wall-clock time, not engine ticks, so it isn't itself gated by phase (a shot fired
        // within ~0.8s of the turn starting can still be mid-flash during FIRING).
        val flashWindowActive = flashTankId != null && flashElapsedSeconds < TURN_FLASH_TOTAL_SECONDS
        val flashColor = if (flashWindowActive) {
            turnStartFlashColor(tanks.firstOrNull { it.id == flashTankId }, flashElapsedSeconds)
        } else {
            null
        }

        // Nothing the static layer draws can change while phase == FIRING - verified against
        // every mutation site in GameEngine, including MIRV's split-child impacts, which
        // always land during RESOLVING, never FIRING - so it only needs redrawing on the
        // first frame, whenever the canvas resizes, whenever phase isn't FIRING (AIMING's
        // aim/turn-flash changes, RESOLVING's impacts/falls/burns/ash), or while the
        // wall-clock turn-flash above is still active.
        if (staticLayerDirty || phase != MatchPhase.FIRING || flashWindowActive) {
            redrawStaticLayer(terrain, tanks, transform, currentTankId, flashColor, waterLevelY)
            staticLayerDirty = false
        }
        canvas.drawBitmap(staticLayerBitmap!!, 0f, 0f, null)

        drawDynamicOverlay(
            canvas,
            terrain,
            tanks,
            projectiles,
            impactEffects,
            bounceEffects,
            transform,
            firingTankId,
            firingMessage,
            waterLevelY,
            lavaRegions,
        )

        // TEMP DIAGNOSTIC (see plan doc) - remove once the gallery-photo choppiness cause is
        // found. Anchored to the bottom-left (canvas.height upward) rather than a fixed
        // top-left offset so it doesn't overlap the HUD's tank health text. "lock" isolates
        // lockCanvas()'s wait on the display compositor from "draw"'s own CPU-side Canvas
        // work, so a spike in one vs. the other points to a very different cause.
        canvas.drawText("draw: %.1fms".format(drawMs), 16f, canvas.height - 16f, debugTextPaint)
        canvas.drawText("lock: %.1fms".format(lockMs), 16f, canvas.height - 48f, debugTextPaint)
        canvas.drawText("sound: %.1fms".format(soundMs), 16f, canvas.height - 80f, debugTextPaint)
        canvas.drawText("tick: %.1fms".format(tickMs), 16f, canvas.height - 112f, debugTextPaint)
    }

    /** (Re)creates the cached static- and terrain-layer bitmaps/canvases/transform whenever the
     * real canvas's size differs from what's cached, and marks both layers dirty so they're
     * redrawn at their new size on the next call - handles both the very first frame
     * (cachedCanvasWidth starts at -1, guaranteed to differ) and any later resize (e.g. a
     * rotation that recreates the underlying Surface - see GameScreen's own doc on that). */
    private fun ensureStaticLayer(canvasWidth: Int, canvasHeight: Int, terrain: HeightMap) {
        if (staticLayerBitmap != null && cachedCanvasWidth == canvasWidth && cachedCanvasHeight == canvasHeight) return
        val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        staticLayerBitmap = bitmap
        staticLayerCanvas = Canvas(bitmap)
        val terrainBitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        terrainLayerBitmap = terrainBitmap
        terrainLayerCanvas = Canvas(terrainBitmap)
        cachedCanvasWidth = canvasWidth
        cachedCanvasHeight = canvasHeight
        cachedTransform = WorldTransform.fit(canvasWidth.toFloat(), canvasHeight.toFloat(), terrain.width.toFloat(), terrain.height.toFloat())
        staticLayerDirty = true
        terrainLayerDirty = true
    }

    /** Draws the parts of the world that only change when the terrain itself does - a resize,
     * or a crater carved during RESOLVING (see [HeightMap.version], checked by [draw]) - into
     * [terrainLayerCanvas]: the background photo, crater scars, and horizon line. Split out from
     * [redrawStaticLayer] (tanks/ash piles/turn-flash) because unlike that content, none of this
     * ever changes during AIMING - most of a match's real elapsed time - so redrawing it every
     * non-FIRING frame, as the combined layer used to, was pure waste; for a visually busy/
     * jagged segmented terrain the anti-aliased horizon/crater stroke work here is expensive
     * enough on this software `Canvas` to cause real, measured multi-hundred-millisecond frame
     * stalls repeated every single frame. */
    private fun redrawTerrainLayer(terrain: HeightMap, transform: WorldTransform, voidRegions: List<FloorRegion>, lavaRegions: List<FloorRegion>) {
        val terrainCanvas = terrainLayerCanvas ?: return
        terrainCanvas.drawColor(Color.BLACK)

        // PhotoUsageMode.TERRAIN/SKY each draw a full-canvas, unclipped complementary layer
        // first (a sky look / a flat terrain color) so the photo - drawn on top, clipped to
        // only its own region - simply covers whatever part of it it occupies. See
        // buildTerrainClipPath's own doc for why this whole block re-running here (whenever
        // this cached layer redraws - see draw()'s own terrain.version dirty check) is what
        // keeps the visible photo region "adjusting as the terrain adjusts" live, with no
        // extra invalidation logic of its own.
        when (photoUsageMode) {
            PhotoUsageMode.BACKGROUND -> {
                if (photo != null && srcRect != null) {
                    backgroundRect.set(
                        transform.screenX(0f),
                        transform.screenY(0f),
                        transform.screenX(terrain.width.toFloat()),
                        transform.screenY(terrain.height.toFloat()),
                    )
                    terrainCanvas.drawBitmap(photo, srcRect, backgroundRect, backgroundPaint)
                }
            }
            PhotoUsageMode.TERRAIN -> {
                drawSkyLook(terrainCanvas, skyLook)
                if (photo != null && srcRect != null) {
                    backgroundRect.set(
                        transform.screenX(0f),
                        transform.screenY(0f),
                        transform.screenX(terrain.width.toFloat()),
                        transform.screenY(terrain.height.toFloat()),
                    )
                    drawClippedPhoto(
                        terrainCanvas, photo, srcRect, backgroundRect,
                        buildTerrainClipPath(terrain, transform, closeAtBottom = true),
                    )
                }
            }
            PhotoUsageMode.SKY -> {
                terrainCanvas.drawColor(terrainColor)
                if (photo != null && srcRect != null) {
                    backgroundRect.set(
                        transform.screenX(0f),
                        transform.screenY(0f),
                        transform.screenX(terrain.width.toFloat()),
                        transform.screenY(terrain.height.toFloat()),
                    )
                    drawClippedPhoto(
                        terrainCanvas, photo, srcRect, backgroundRect,
                        buildTerrainClipPath(terrain, transform, closeAtBottom = false),
                    )
                }
            }
        }

        drawCraterScars(terrainCanvas, terrain, transform)
        drawHorizonLine(terrainCanvas, terrain, transform)
        drawVoidFill(terrainCanvas, terrain, transform, voidRegions)
        drawLavaFill(terrainCanvas, terrain, transform, lavaRegions)
        drawEdgeBorders(terrainCanvas)
    }

    /** [FloorType.VOID]'s own solid-black fill, covering exactly the columns this region has
     * actually carved open (`terrain.groundY[x] > originalGroundY[x]`, scoped to the region's
     * own horizontal span) from the carved surface down to the map's true bottom - never any
     * ordinary battle crater that merely hasn't reached bottom yet, since only tracked
     * [FloorRegion]s reach this call at all (see [GameEngine.voidRegions][com.scorchedphoto.engine.GameEngine.voidRegions]). */
    private fun drawVoidFill(canvas: Canvas, terrain: HeightMap, transform: WorldTransform, regions: List<FloorRegion>) {
        if (regions.isEmpty()) return
        regionFillPaint.shader = null
        regionFillPaint.color = colorFor(FloorType.VOID)!!
        for (region in regions) {
            drawRegionFill(canvas, terrain, transform, region, regionFillPaint)
        }
    }

    /** [FloorType.LAVA]'s own red fill (same carved-columns scoping as [drawVoidFill]), plus a
     * scatter of black flecks per the user's "specks of black" spec - see [lavaSpecksFor]. The
     * small waves/bumps along the lava's own surface are animated, so those are drawn separately
     * every frame as part of the dynamic overlay - see [drawLavaBumps]. */
    private fun drawLavaFill(canvas: Canvas, terrain: HeightMap, transform: WorldTransform, regions: List<FloorRegion>) {
        if (regions.isEmpty()) return
        regionFillPaint.shader = null
        regionFillPaint.color = colorFor(FloorType.LAVA)!!
        for (region in regions) {
            drawRegionFill(canvas, terrain, transform, region, regionFillPaint)
            val (minX, maxX) = regionColumnSpan(terrain, region) ?: continue
            val bottomY = transform.screenY(terrain.height.toFloat())
            for (speck in lavaSpecksFor(region)) {
                val x = minX + speck.alongFraction * (maxX - minX)
                val topY = transform.screenY(terrain.groundY[x.toInt().coerceIn(minX, maxX)].toFloat())
                if (topY >= bottomY) continue
                val sx = transform.screenX(x)
                val sy = topY + speck.depthFraction * (bottomY - topY)
                lavaSpeckPaint.alpha = 255
                canvas.drawCircle(sx, sy, speck.radius * transform.scale, lavaSpeckPaint)
            }
        }
    }

    /** This Lava region's speck layout - generated once (deterministically, from the region's
     * own [FloorRegion.centerX], stable for its whole lifetime) and cached, so specks don't
     * flicker by being re-randomized whenever the terrain layer redraws (every round, at least,
     * as the region keeps growing). */
    private fun lavaSpecksFor(region: FloorRegion): List<LavaSpeck> = lavaSpecks.getOrPut(region.centerX) {
        val rng = kotlin.random.Random(region.centerX.toRawBits())
        (0 until LAVA_SPECK_COUNT).map {
            LavaSpeck(
                alongFraction = rng.nextFloat(),
                depthFraction = rng.nextFloat(),
                radius = 1f + rng.nextFloat() * 1.5f,
            )
        }
    }

    /** [region]'s own column span, clamped to the terrain's actual bounds - `null` if the
     * region sits entirely outside them (shouldn't normally happen, but a region's centerX is
     * never re-clamped after seeding). Shared by [drawRegionFill] and [drawLavaFill]'s speck
     * placement so both agree on exactly which columns a region covers. */
    private fun regionColumnSpan(terrain: HeightMap, region: FloorRegion): Pair<Int, Int>? {
        val minX = (region.centerX - region.radius).toInt().coerceIn(0, terrain.width - 1)
        val maxX = (region.centerX + region.radius).toInt().coerceIn(0, terrain.width - 1)
        return if (minX > maxX) null else minX to maxX
    }

    /** Fills the area this [region] has actually carved open - from its own per-column carved
     * surface (`terrain.groundY[x]`, already lowered by [GameEngine.growRegion][com.scorchedphoto.engine.GameEngine.growRegion])
     * down to the map's true bottom - with [paint]. Shared by [drawVoidFill]/[drawLavaFill];
     * only the color (and, for Lava, the speck scatter drawn on top) differs between the two. */
    private fun drawRegionFill(canvas: Canvas, terrain: HeightMap, transform: WorldTransform, region: FloorRegion, paint: Paint) {
        val (minX, maxX) = regionColumnSpan(terrain, region) ?: return
        regionFillPath.reset()
        var started = false
        for (x in minX..maxX) {
            val px = transform.screenX(x.toFloat())
            val py = transform.screenY(terrain.groundY[x].toFloat())
            if (!started) {
                regionFillPath.moveTo(px, py)
                started = true
            } else {
                regionFillPath.lineTo(px, py)
            }
        }
        if (!started) return
        val bottomY = transform.screenY(terrain.height.toFloat())
        regionFillPath.lineTo(transform.screenX(maxX.toFloat()), bottomY)
        regionFillPath.lineTo(transform.screenX(minX.toFloat()), bottomY)
        regionFillPath.close()
        canvas.drawPath(regionFillPath, paint)
    }

    /** The closed clip region bounded by the terrain's own ground line (walked column by
     * column across the *entire* terrain width, the same per-column walk [drawHorizonLine]/
     * [drawRegionFill] already use) and either the map's bottom edge ([closeAtBottom] = true -
     * [PhotoUsageMode.TERRAIN]'s "photo shows below the line") or its top edge
     * ([closeAtBottom] = false - [PhotoUsageMode.SKY]'s "photo shows above the line").
     * Rebuilt fresh from the *current* [HeightMap.groundY] every call - this is only ever
     * called from [redrawTerrainLayer], which itself only re-runs when [HeightMap.version] has
     * actually advanced or the canvas resized (see [draw]'s own dirty check), so this is how
     * the visible photo region keeps re-clipping live as craters are carved / Void/Lava
     * regions grow, with no extra invalidation logic of its own. */
    private fun buildTerrainClipPath(terrain: HeightMap, transform: WorldTransform, closeAtBottom: Boolean): Path {
        terrainClipPath.reset()
        for (x in 0 until terrain.width) {
            val px = transform.screenX(x.toFloat())
            val py = transform.screenY(terrain.groundY[x].toFloat())
            if (x == 0) terrainClipPath.moveTo(px, py) else terrainClipPath.lineTo(px, py)
        }
        val closeY = transform.screenY(if (closeAtBottom) terrain.height.toFloat() else 0f)
        terrainClipPath.lineTo(transform.screenX((terrain.width - 1).toFloat()), closeY)
        terrainClipPath.lineTo(transform.screenX(0f), closeY)
        terrainClipPath.close()
        return terrainClipPath
    }

    /** save()/clipPath()/drawBitmap()/restore() around the photo draw, for
     * [PhotoUsageMode.TERRAIN]/[PhotoUsageMode.SKY] - the one place in this file
     * [Canvas.clipPath] is used (standard Android API, already used elsewhere in the app -
     * e.g. WindIndicator's own Compose `clipPath` for its circular gauge mask). */
    private fun drawClippedPhoto(canvas: Canvas, photo: Bitmap, srcRect: Rect, dstRect: RectF, clipPath: Path) {
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawBitmap(photo, srcRect, dstRect, backgroundPaint)
        canvas.restore()
    }

    /** A fixed-color border along the screen edges the current [wallType] (left/right),
     * [ceilingType] (top), and [floorType] (bottom) actually bounce off of - drawn in screen
     * space (not scaled by [WorldTransform], unlike everything else in this layer) since it's a
     * HUD-like frame around the play area, not a feature of the world itself. [EdgeType.NONE]/
     * [FloorType.HOLE] draw no border for that edge; every other real bounce type still gets a
     * color/border, including the ones that also detonate/fizzle rather than bounce on touch -
     * "Random" never reaches here at all, already resolved to one concrete type before a match
     * starts (see [com.scorchedphoto.app.setup.GameSetupViewModel.commitAndStart]).
     */
    private fun drawEdgeBorders(canvas: Canvas) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        if (width <= 0f || height <= 0f) return
        val thickness = borderThickness(canvas)

        colorFor(wallType)?.let { color ->
            edgeBorderPaint.color = color
            canvas.drawRect(0f, 0f, thickness, height, edgeBorderPaint)
            canvas.drawRect(width - thickness, 0f, width, height, edgeBorderPaint)
        }
        colorFor(ceilingType)?.let { color ->
            edgeBorderPaint.color = color
            canvas.drawRect(0f, 0f, width, thickness, edgeBorderPaint)
        }
        colorFor(floorType)?.let { color ->
            edgeBorderPaint.color = color
            canvas.drawRect(0f, height - thickness, width, height, edgeBorderPaint)
        }
    }

    /** Screen-edge border thickness, shared by [drawEdgeBorders] and [drawWrapEffects] so the
     * glow/sparkles line up exactly against the solid border they extend from. */
    private fun borderThickness(canvas: Canvas): Float = edgeBorderThicknessPx(canvas.width.toFloat(), canvas.height.toFloat())

    /** Public wrapper around [borderThickness]'s own thickness math, taking explicit
     * dimensions rather than a [Canvas] - lets [GameLoopThread] compute the exact same
     * screen-space border thickness (to know where "just below the top border" actually
     * lands for [com.scorchedphoto.engine.GameEngine.ceilingWrapDepthY]/
     * [com.scorchedphoto.engine.GameEngine.floorWrapDepthY]) without duplicating
     * [EDGE_BORDER_THICKNESS_FRACTION] or holding a real [Canvas] of its own. */
    fun edgeBorderThicknessPx(width: Float, height: Float): Float {
        if (width <= 0f || height <= 0f) return 0f
        return (min(width, height) * EDGE_BORDER_THICKNESS_FRACTION).coerceAtLeast(1f)
    }

    /** [EdgeType.WRAP]'s extra "mystical" flourish - a soft yellow glow bleeding inward from
     * just past the solid border (see [colorFor]), plus a field of twinkling glitter specks
     * scattered across the border and glow band. Unlike [drawEdgeBorders] (drawn once into the
     * cached terrain layer, since a plain solid-color border never changes on its own), this
     * animates continuously - twinkle phase and alpha both depend on wall-clock elapsed time -
     * so it's drawn fresh every frame as part of the dynamic overlay, on top of the static
     * terrain/tank layers, for whichever of [wallType]/[ceilingType] is actually [EdgeType.WRAP].
     */
    private fun drawWrapEffects(canvas: Canvas) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        if (width <= 0f || height <= 0f) return
        val thickness = borderThickness(canvas)
        val glowWidth = thickness * WRAP_GLOW_WIDTH_MULTIPLIER
        // Sparkles are allowed to appear anywhere from the screen edge (0) through the end of
        // the glow's own reach - a wider span than the glow rect itself, which only covers the
        // fade-out past the solid border.
        val bandWidth = thickness + glowWidth
        val elapsedSeconds = (System.nanoTime() - wrapEffectsStartNanos) / 1_000_000_000f
        val wrapColor = colorFor(EdgeType.WRAP)!!

        if (wallType == EdgeType.WRAP) {
            // Left edge: glow fades outward from the border's own inner edge (x=thickness).
            drawWrapGlowBand(
                canvas,
                RectF(thickness, 0f, thickness + glowWidth, height),
                gradientStartX = thickness, gradientStartY = 0f, gradientEndX = thickness + glowWidth, gradientEndY = 0f,
                color = wrapColor,
            )
            // Right edge: mirrored - glow fades outward from x=(width-thickness).
            drawWrapGlowBand(
                canvas,
                RectF(width - thickness - glowWidth, 0f, width - thickness, height),
                gradientStartX = width - thickness, gradientStartY = 0f, gradientEndX = width - thickness - glowWidth, gradientEndY = 0f,
                color = wrapColor,
            )
            drawWrapSparkles(canvas, wallWrapSparkles, elapsedSeconds) { along, depth -> depth * bandWidth to along * height }
            drawWrapSparkles(canvas, wallWrapSparkles, elapsedSeconds) { along, depth -> (width - depth * bandWidth) to along * height }
        }
        if (ceilingType == EdgeType.WRAP) {
            drawWrapGlowBand(
                canvas,
                RectF(0f, thickness, width, thickness + glowWidth),
                gradientStartX = 0f, gradientStartY = thickness, gradientEndX = 0f, gradientEndY = thickness + glowWidth,
                color = wrapColor,
            )
            drawWrapSparkles(canvas, ceilingWrapSparkles, elapsedSeconds) { along, depth -> along * width to depth * bandWidth }
        }
    }

    /** One translucent gradient rect covering [rect], brightest at (gradientStartX,
     * gradientStartY) - the solid border's own inner edge - and fully faded to transparent by
     * (gradientEndX, gradientEndY), further into the play area. */
    private fun drawWrapGlowBand(
        canvas: Canvas,
        rect: RectF,
        gradientStartX: Float,
        gradientStartY: Float,
        gradientEndX: Float,
        gradientEndY: Float,
        color: Int,
    ) {
        val glowColor = Color.argb(WRAP_GLOW_ALPHA, Color.red(color), Color.green(color), Color.blue(color))
        wrapGlowPaint.shader = LinearGradient(gradientStartX, gradientStartY, gradientEndX, gradientEndY, glowColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(rect, wrapGlowPaint)
    }

    /** Twinkling glitter specks across a WRAP edge's border+glow band - [toScreen] maps each
     * sparkle's own normalized (along-the-edge, depth-into-the-band) position to real screen
     * coordinates, so the same sparkle layout/timing can be reused for the mirrored left/right
     * wall edges (see [drawWrapEffects]) without duplicating the twinkle math itself. */
    private fun drawWrapSparkles(
        canvas: Canvas,
        sparkles: List<WrapSparkle>,
        elapsedSeconds: Float,
        toScreen: (along: Float, depth: Float) -> Pair<Float, Float>,
    ) {
        edgeMarkPaint.shader = null
        edgeMarkPaint.style = Paint.Style.FILL
        for (sparkle in sparkles) {
            val twinkle = 0.5f + 0.5f * sin(sparkle.phase + elapsedSeconds * sparkle.twinkleSpeed)
            val alpha = (WRAP_SPARKLE_MIN_ALPHA + (WRAP_SPARKLE_MAX_ALPHA - WRAP_SPARKLE_MIN_ALPHA) * twinkle).toInt()
            edgeMarkPaint.color = Color.argb(alpha, 0xFF, 0xFF, 0xE0) // warm white-yellow glitter
            val (x, y) = toScreen(sparkle.alongFraction, sparkle.depthFraction)
            canvas.drawCircle(x, y, sparkle.radius, edgeMarkPaint)
        }
    }

    private fun generateWrapSparkles(seed: Int): List<WrapSparkle> {
        val rng = kotlin.random.Random(seed)
        return (0 until WRAP_SPARKLE_COUNT).map {
            WrapSparkle(
                alongFraction = rng.nextFloat(),
                depthFraction = rng.nextFloat(),
                phase = rng.nextFloat() * (2f * Math.PI.toFloat()),
                twinkleSpeed = 1.5f + rng.nextFloat() * 2.5f,
                radius = 1.5f + rng.nextFloat() * 2f,
            )
        }
    }

    /** One glitter speck's layout within a WRAP edge's border+glow band, normalized so the same
     * list works regardless of the real band's pixel size - see [drawWrapSparkles]. */
    private data class WrapSparkle(
        val alongFraction: Float,
        val depthFraction: Float,
        val phase: Float,
        val twinkleSpeed: Float,
        val radius: Float,
    )

    // [PhotoUsageMode.TERRAIN]'s procedural sky backdrop - drawn wherever the terrain-clipped
    // photo doesn't cover (see redrawTerrainLayer). One of 7 SkyLook values, resolved once per
    // match and never re-rolled - this dispatch is purely a function of the fixed skyLook
    // field, not of terrain/animation state, so it's safe to live in the cached terrain layer
    // (no per-frame twinkle here - see drawStars' own doc for why, unlike drawWrapSparkles
    // which *is* animated every frame as part of the dynamic overlay). Composed from 3 shared
    // primitives, each reusing an idiom already established elsewhere in this file:
    // drawSkyGradientBand (drawWrapGlowBand's own LinearGradient idiom), drawGlowDisc
    // (drawGradientCircle's own manual concentric-circle idiom, for a sun/moon), and the
    // cached cloudPuffs/stars scatter (generateWrapSparkles' own caching idiom). Positions are
    // plain canvas-fraction coordinates - this is screen-space decoration, like
    // drawEdgeBorders, not tied to WorldTransform/world coordinates.

    private fun drawSkyLook(canvas: Canvas, skyLook: SkyLook) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        if (width <= 0f || height <= 0f) return
        when (skyLook) {
            SkyLook.CLEAR -> drawClearSky(canvas, width, height)
            SkyLook.CLOUDY -> {
                drawClearSky(canvas, width, height)
                drawClouds(canvas, width, height)
            }
            SkyLook.SUNRISE -> drawDuskSky(
                canvas, width, height,
                top = Color.rgb(0x1A, 0x23, 0x7E), bottom = Color.rgb(0xFF, 0xCC, 0x80),
                sunCore = Color.rgb(0xFF, 0xF5, 0x9D), sunGlow = Color.argb(140, 0xFF, 0xB7, 0x4D),
            )
            SkyLook.SUNSET -> drawDuskSky(
                canvas, width, height,
                top = Color.rgb(0x31, 0x1B, 0x92), bottom = Color.rgb(0xE6, 0x51, 0x00),
                sunCore = Color.rgb(0xFF, 0xAB, 0x40), sunGlow = Color.argb(150, 0xD8, 0x43, 0x15),
            )
            SkyLook.NIGHT_CLEAR -> drawNightSky(canvas, width, height)
            SkyLook.NIGHT_STARS -> {
                drawNightSky(canvas, width, height)
                drawStars(canvas, width, height)
            }
            SkyLook.NIGHT_STARS_MOON -> {
                drawNightSky(canvas, width, height)
                drawStars(canvas, width, height)
                drawMoon(canvas, width, height)
            }
        }
    }

    private fun drawClearSky(canvas: Canvas, width: Float, height: Float) =
        drawSkyGradientBand(canvas, width, height, top = Color.rgb(0x42, 0xA5, 0xF5), bottom = Color.rgb(0xE1, 0xF5, 0xFE))

    private fun drawNightSky(canvas: Canvas, width: Float, height: Float) =
        drawSkyGradientBand(canvas, width, height, top = Color.rgb(0x02, 0x02, 0x0B), bottom = Color.rgb(0x1A, 0x1A, 0x3D))

    private fun drawDuskSky(canvas: Canvas, width: Float, height: Float, top: Int, bottom: Int, sunCore: Int, sunGlow: Int) {
        drawSkyGradientBand(canvas, width, height, top, bottom)
        drawGlowDisc(canvas, cx = width * 0.7f, cy = height * 0.72f, coreRadius = min(width, height) * 0.06f, coreColor = sunCore, glowColor = sunGlow)
    }

    private fun drawMoon(canvas: Canvas, width: Float, height: Float) = drawGlowDisc(
        canvas, cx = width * 0.75f, cy = height * 0.15f, coreRadius = min(width, height) * 0.045f,
        coreColor = Color.rgb(0xEC, 0xEF, 0xF1), glowColor = Color.argb(110, 0xCF, 0xD8, 0xDC),
    )

    /** Shared vertical LinearGradient band, [top] to [bottom] - the same shader-reassign idiom
     * [drawWrapGlowBand] already uses, just top-to-bottom instead of edge-to-edge. */
    private fun drawSkyGradientBand(canvas: Canvas, width: Float, height: Float, top: Int, bottom: Int) {
        skyGradientPaint.shader = LinearGradient(0f, 0f, 0f, height, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, skyGradientPaint)
    }

    /** Shared soft circular glow for a sun/moon - the same manual concentric-`drawCircle`-steps
     * idiom [drawGradientCircle] already uses for the impact-explosion flash, fading from
     * [glowColor] at the outer radius down to a fully opaque [coreColor] disc at the center. */
    private fun drawGlowDisc(canvas: Canvas, cx: Float, cy: Float, coreRadius: Float, coreColor: Int, glowColor: Int) {
        val glowRadius = coreRadius * GLOW_DISC_RADIUS_MULTIPLIER
        val steps = 12
        for (i in steps downTo 1) {
            val fraction = i.toFloat() / steps
            val r = coreRadius + (glowRadius - coreRadius) * fraction
            val alpha = (Color.alpha(glowColor) * fraction).toInt()
            glowDiscPaint.color = Color.argb(alpha, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor))
            canvas.drawCircle(cx, cy, r, glowDiscPaint)
        }
        glowDiscPaint.color = coreColor
        canvas.drawCircle(cx, cy, coreRadius, glowDiscPaint)
    }

    private fun drawClouds(canvas: Canvas, width: Float, height: Float) {
        cloudPaint.shader = null
        for (puff in cloudPuffs) {
            cloudPaint.color = Color.argb(puff.alpha, 255, 255, 255)
            val cx = puff.nx * width
            val cy = puff.ny * height
            val r = puff.radiusFraction * min(width, height)
            canvas.drawCircle(cx, cy, r, cloudPaint)
            canvas.drawCircle(cx + r * 0.8f, cy + r * 0.15f, r * 0.7f, cloudPaint)
            canvas.drawCircle(cx - r * 0.8f, cy + r * 0.15f, r * 0.7f, cloudPaint)
        }
    }

    private fun generateClouds(seed: Int): List<CloudPuff> {
        val rng = kotlin.random.Random(seed)
        return (0 until CLOUD_COUNT).map {
            CloudPuff(
                nx = rng.nextFloat(),
                ny = rng.nextFloat() * 0.4f,
                radiusFraction = 0.03f + rng.nextFloat() * 0.04f,
                alpha = 170 + rng.nextInt(60),
            )
        }
    }

    /** One cloud puff's layout within [SkyLook.CLOUDY]'s scatter, normalized to the canvas's
     * own width/height so the same list works regardless of real screen size - see
     * [drawClouds]/[cloudPuffs]. */
    private data class CloudPuff(val nx: Float, val ny: Float, val radiusFraction: Float, val alpha: Int)

    // Not animated (no wall-clock twinkle) - this layer lives in the *cached* terrainLayerCanvas,
    // which only redraws when terrain.version advances or the canvas resizes (see
    // redrawTerrainLayer), not every frame, so a per-frame twinkle here wouldn't actually
    // animate smoothly - unlike drawWrapSparkles, which is drawn fresh every frame as part of
    // the dynamic overlay.
    private fun drawStars(canvas: Canvas, width: Float, height: Float) {
        starPaint.shader = null
        for (star in stars) {
            starPaint.color = Color.argb(star.alpha, 255, 255, 255)
            canvas.drawCircle(star.nx * width, star.ny * height, star.radius, starPaint)
        }
    }

    private fun generateStars(seed: Int): List<Star> {
        val rng = kotlin.random.Random(seed)
        return (0 until STAR_COUNT).map {
            Star(
                nx = rng.nextFloat(),
                ny = rng.nextFloat() * 0.8f,
                radius = 1f + rng.nextFloat() * 1.5f,
                alpha = 140 + rng.nextInt(115),
            )
        }
    }

    /** One star's layout within [SkyLook.NIGHT_STARS]/[SkyLook.NIGHT_STARS_MOON]'s scatter,
     * normalized the same way as [CloudPuff] - see [drawStars]/[stars]. */
    private data class Star(val nx: Float, val ny: Float, val radius: Float, val alpha: Int)

    /** Draws everything that doesn't change while a shot is purely in flight - see [draw]'s
     * doc - into [staticLayerCanvas]: the cached terrain layer as a base, then every tank's
     * body/barrel or ash pile on top. */
    private fun redrawStaticLayer(
        terrain: HeightMap,
        tanks: List<Tank>,
        transform: WorldTransform,
        currentTankId: Int?,
        flashColor: Int?,
        waterLevelY: Float,
    ) {
        val staticCanvas = staticLayerCanvas ?: return
        val terrainBitmap = terrainLayerBitmap
        if (terrainBitmap != null) {
            staticCanvas.drawBitmap(terrainBitmap, 0f, 0f, null)
        } else {
            staticCanvas.drawColor(Color.BLACK)
        }

        for (tank in tanks) {
            // Fell through a Hole/Wrap/Void floor - no body, no ash pile, ever again (see
            // GameEngine.killByFallingThroughFloor's doc): the only remaining trace is its
            // death-taunt speech bubble, drawn separately every frame - see
            // drawFallThroughSpeechBubble. A tank mid-rise is drawn by the dynamic overlay
            // instead (see drawRisingTank), which owns its flip/float tween every frame.
            if (tank.fallingThroughFloor || tank.rising) continue
            if (tank.isDrowned) {
                drawDrownedTank(staticCanvas, tank, transform, waterLevelY)
                continue
            }
            if (tank.isAsh) {
                drawAshPile(staticCanvas, tank, transform)
                continue
            }
            val colorOverride = if (tank.id == currentTankId) flashColor else null
            drawTank(staticCanvas, tank, terrain, transform, colorOverride)
        }
    }

    /** Draws everything that's either inherently per-frame or only ever active outside
     * [MatchPhase.FIRING] (per [draw]'s doc) directly onto the real [canvas], on top of the
     * blitted static layer: impact flashes, in-flight projectiles, burning-tank fire/bubble,
     * and the pre-fire speech bubble. */
    private fun drawDynamicOverlay(
        canvas: Canvas,
        terrain: HeightMap,
        tanks: List<Tank>,
        projectiles: List<Projectile>,
        impactEffects: List<ImpactEffect>,
        bounceEffects: List<BounceEffect>,
        transform: WorldTransform,
        firingTankId: Int?,
        firingMessage: String?,
        waterLevelY: Float,
        lavaRegions: List<FloorRegion>,
    ) {
        drawWrapEffects(canvas)
        drawWaterOverlay(canvas, transform, waterLevelY)
        drawLavaBumps(canvas, terrain, transform, lavaRegions)
        drawImpactEffects(canvas, impactEffects, transform)
        drawBounceEffects(canvas, bounceEffects, transform)

        for (projectile in projectiles) {
            canvas.drawCircle(
                transform.screenX(projectile.x),
                transform.screenY(projectile.y),
                PROJECTILE_RADIUS * transform.scale,
                projectilePaint,
            )
        }

        for (tank in tanks) {
            if (tank.fallingThroughFloor) {
                drawFallThroughSpeechBubble(canvas, tank, transform)
            } else if (tank.rising) {
                drawRisingTank(canvas, tank, transform, waterLevelY)
            } else if (tank.drowningBubbles) {
                drawDrowningBubbles(canvas, tank, transform)
            } else if (tank.drowningSpeech) {
                drawDrowningSpeechBubble(canvas, tank, transform)
            } else if (tank.burning) {
                drawBurningTank(canvas, tank, transform)
            } else if (tank.id == firingTankId && firingMessage != null) {
                drawFiringSpeechBubble(canvas, tank, transform, firingMessage)
            }
        }
    }

    /** [FloorType.WATER]'s rising, wavy-bordered fill - a no-op for every other floor type
     * ([waterLevelY] stays pinned at [HeightMap.height], keeping [screenLevelY] at the very
     * bottom edge, but this still short-circuits explicitly rather than relying on that being
     * invisible). Animated continuously (wall-clock based, like [drawWrapEffects]) since the
     * wave itself moves even between rounds, when the water's actual level isn't rising. */
    private fun drawWaterOverlay(canvas: Canvas, transform: WorldTransform, waterLevelY: Float) {
        if (floorType != FloorType.WATER) return
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        if (width <= 0f || height <= 0f) return
        val screenLevelY = transform.screenY(waterLevelY)
        if (screenLevelY >= height) return

        val elapsedSeconds = (System.nanoTime() - floorEffectsStartNanos) / 1_000_000_000f
        val amplitude = WATER_WAVE_AMPLITUDE * transform.scale
        val wavelength = WATER_WAVE_LENGTH_PX * transform.scale

        waterFillPath.reset()
        waterFillPath.moveTo(0f, height)
        waterFillPath.lineTo(0f, waveY(0f, screenLevelY, amplitude, wavelength, elapsedSeconds))
        waveStrokePath.reset()
        waveStrokePath.moveTo(0f, waveY(0f, screenLevelY, amplitude, wavelength, elapsedSeconds))
        var x = 0f
        while (x <= width) {
            val y = waveY(x, screenLevelY, amplitude, wavelength, elapsedSeconds)
            waterFillPath.lineTo(x, y)
            waveStrokePath.lineTo(x, y)
            x += WAVE_STEP_PX
        }
        waterFillPath.lineTo(width, height)
        waterFillPath.close()

        val waterColor = colorFor(FloorType.WATER)!!
        waterFillPaint.color = Color.argb(WATER_FILL_ALPHA, Color.red(waterColor), Color.green(waterColor), Color.blue(waterColor))
        canvas.drawPath(waterFillPath, waterFillPaint)
        waterWavePaint.color = waterColor
        canvas.drawPath(waveStrokePath, waterWavePaint)
    }

    /** [FloorType.LAVA]'s "very small waves/bumps" - the same wavy-stroke idea as
     * [drawWaterOverlay], but traced along each tracked [FloorRegion]'s own carved surface
     * (not a single flat level) and with a much smaller amplitude, since these are meant to
     * read as surface bubbling rather than a rising tide. */
    private fun drawLavaBumps(canvas: Canvas, terrain: HeightMap, transform: WorldTransform, regions: List<FloorRegion>) {
        if (regions.isEmpty()) return
        val elapsedSeconds = (System.nanoTime() - floorEffectsStartNanos) / 1_000_000_000f
        val amplitude = LAVA_BUMP_AMPLITUDE * transform.scale
        val wavelength = LAVA_BUMP_LENGTH_PX * transform.scale
        lavaBumpPaint.color = colorFor(FloorType.LAVA)!!

        for (region in regions) {
            val (minX, maxX) = regionColumnSpan(terrain, region) ?: continue
            waveStrokePath.reset()
            var started = false
            for (x in minX..maxX) {
                val baseY = transform.screenY(terrain.groundY[x].toFloat())
                val y = waveY(transform.screenX(x.toFloat()), baseY, amplitude, wavelength, elapsedSeconds)
                val px = transform.screenX(x.toFloat())
                if (!started) {
                    waveStrokePath.moveTo(px, y)
                    started = true
                } else {
                    waveStrokePath.lineTo(px, y)
                }
            }
            if (started) canvas.drawPath(waveStrokePath, lavaBumpPaint)
        }
    }

    /** A single point on a sine wave through ([baseX], [baseLevelY]) - shared by
     * [drawWaterOverlay] (a flat, screen-wide level) and [drawLavaBumps] (traced along a
     * region's own already-uneven carved surface, so each column's [baseLevelY] differs). */
    private fun waveY(baseX: Float, baseLevelY: Float, amplitude: Float, wavelength: Float, elapsedSeconds: Float): Float =
        baseLevelY + sin(baseX / wavelength + elapsedSeconds * WAVE_SPEED) * amplitude

    /** [FloorType.HOLE]/[FloorType.WRAP]/[FloorType.VOID]'s instant, animation-free death (see
     * [Tank.fallingThroughFloor]'s doc) - the tank's body is never drawn again once this is
     * true (see [redrawStaticLayer]), so its usual random death-taunt speech bubble is the only
     * remaining trace, anchored at the fixed point it fell through ([Tank.fallThroughAnchorY],
     * not [Tank.y], which the tank no longer needs to keep animating) rather than following it -
     * reuses the exact same taunt-assignment/TTS plumbing ([burnMessageFor]) a normal burning
     * death's bubble already uses, just anchored differently. */
    private fun drawFallThroughSpeechBubble(canvas: Canvas, tank: Tank, transform: WorldTransform) {
        val message = burnMessageFor(tank) ?: return
        val cx = transform.screenX(tank.x)
        val cy = transform.screenY(tank.fallThroughAnchorY)
        drawSpeechBubble(canvas, cx, cy, message)
    }

    // FloorType.WATER's drowning sequence, taken instead of the ordinary burn/explosion/ash
    // chain whenever a tank dies fully submerged - see Tank.pendingDrown's doc. Little bubbles
    // (drawDrowningBubbles) rise from the tank's own position first, then its usual death-taunt
    // speech bubble (drawDrowningSpeechBubble) - the tank's ordinary upright body (still drawn
    // by redrawStaticLayer's normal drawTank branch through both of these phases, exactly like a
    // burning tank's body stays visible under its flames) then flips upside-down and floats up
    // to the water's own surface (drawRisingTank), settling there permanently (drawDrownedTank),
    // tracking the surface as it keeps rising for the rest of the match.

    /** A cluster of small bubbles rising from [tank]'s own position, looping for the whole
     * [Tank.drowningBubbles] phase - drawn with the shared [edgeMarkPaint], mirroring
     * [drawWrapSparkles]'s own reused-paint pattern. */
    private fun drawDrowningBubbles(canvas: Canvas, tank: Tank, transform: WorldTransform) {
        val cx = transform.screenX(tank.x)
        val cy = transform.screenY(tank.y)
        val halfWidth = TANK_HALF_WIDTH * transform.scale
        edgeMarkPaint.shader = null
        edgeMarkPaint.style = Paint.Style.FILL
        for (bubble in bubblesFor(tank.id)) {
            val cycleFraction = (tank.drowningBubblesElapsed * bubble.riseSpeed + bubble.startFraction) % 1f
            val bx = cx + bubble.nx * halfWidth
            val by = cy - cycleFraction * halfWidth * BUBBLE_RISE_HEIGHT_MULTIPLIER
            val alpha = (BUBBLE_MAX_ALPHA * (1f - cycleFraction)).toInt().coerceIn(0, 255)
            edgeMarkPaint.color = Color.argb(alpha, 200, 230, 255)
            canvas.drawCircle(bx, by, bubble.radius * transform.scale, edgeMarkPaint)
        }
    }

    /** This tank's own cluster of [Bubble]s - generated once (deterministically, from the
     * tank's own id) and cached so the layout doesn't flicker by being re-randomized every
     * frame - mirrors [ashSpecksFor]/[lavaSpecksFor]'s own caching pattern. */
    private fun bubblesFor(tankId: Int): List<Bubble> = drowningBubbleSets.getOrPut(tankId) {
        val rng = kotlin.random.Random(tankId * 104_729 + 17)
        (0 until BUBBLE_COUNT).map {
            Bubble(
                nx = rng.nextFloat() * 2f - 1f,
                startFraction = rng.nextFloat(),
                riseSpeed = 0.6f + rng.nextFloat() * 0.6f,
                radius = 1.5f + rng.nextFloat() * 2f,
            )
        }
    }

    /** A drowning tank's own death-taunt speech bubble - reuses [burnMessageFor]/
     * [drawSpeechBubble] exactly like a burning tank's own bubble does, just anchored at the
     * same height above the tank a burning tank's own bubble uses. */
    private fun drawDrowningSpeechBubble(canvas: Canvas, tank: Tank, transform: WorldTransform) {
        val message = burnMessageFor(tank) ?: return
        val cx = transform.screenX(tank.x)
        val cy = transform.screenY(tank.y)
        val halfWidth = TANK_HALF_WIDTH * transform.scale
        val tailTipY = cy - halfWidth * 0.2f - halfWidth * FIRE_HEIGHT_MULTIPLIER
        drawSpeechBubble(canvas, cx, tailTipY, message)
    }

    /** [Tank.rising]'s flip-upside-down-and-float-to-the-surface tween, interpolating from the
     * tank's own frozen resting position (see [com.scorchedphoto.engine.GameEngine.applyTankGravity]'s
     * doc on why gravity stops touching it once this phase starts) up to the live water
     * surface, over [DROWNING_RISE_DURATION_SECONDS] - kept numerically in sync with the
     * engine's own copy of that duration by hand, the same existing pattern [GROWTH_SECONDS]
     * uses to mirror [com.scorchedphoto.engine.GameEngine]'s death-explosion timing. */
    private fun drawRisingTank(canvas: Canvas, tank: Tank, transform: WorldTransform, waterLevelY: Float) {
        val progress = (tank.risingElapsed / DROWNING_RISE_DURATION_SECONDS).coerceIn(0f, 1f)
        val cx = transform.screenX(tank.x)
        val startCy = transform.screenY(tank.y)
        val endCy = transform.screenY(waterLevelY)
        val cy = startCy + (endCy - startCy) * progress
        val halfWidth = TANK_HALF_WIDTH * transform.scale
        val flipDeg = 180f * progress

        tankBodyPaint.color = tank.color
        canvas.save()
        canvas.rotate(flipDeg, cx, cy)
        canvas.drawPath(tankBodyPathFor(tank, cx, cy, halfWidth), tankBodyPaint)
        canvas.restore()
        // No barrel drawn - a dead tank isn't aiming, matching every other death-state body draw.
    }

    /** [Tank.isDrowned]'s permanent resting state - upside-down, exactly at the live water
     * surface, re-tracking it every redraw as it keeps rising for the rest of the match.
     * Drawn into the *static* layer (see [redrawStaticLayer]) rather than every frame, same as
     * [drawAshPile] - it only needs to move when the water level itself changes, which already
     * invalidates that layer (a round boundary always falls outside [MatchPhase.FIRING]). */
    private fun drawDrownedTank(canvas: Canvas, tank: Tank, transform: WorldTransform, waterLevelY: Float) {
        val cx = transform.screenX(tank.x)
        val cy = transform.screenY(waterLevelY)
        val halfWidth = TANK_HALF_WIDTH * transform.scale
        tankBodyPaint.color = tank.color
        canvas.save()
        canvas.rotate(180f, cx, cy)
        canvas.drawPath(tankBodyPathFor(tank, cx, cy, halfWidth), tankBodyPaint)
        canvas.restore()
    }

    /**
     * The current tank's body color while its start-of-turn flash is still playing:
     * alternates white/normal (or magenta/normal if the tank's own color is already
     * white) starting on a flash, [TURN_FLASH_COUNT] flashes total, each flash and each
     * intervening normal-color gap lasting [TURN_FLASH_SEGMENT_SECONDS] - null once the
     * flash sequence has finished (or there's no current tank), meaning "draw normally".
     */
    private fun turnStartFlashColor(tank: Tank?, elapsedSeconds: Float): Int? {
        if (tank == null || elapsedSeconds >= TURN_FLASH_TOTAL_SECONDS) return null
        val segmentIndex = (elapsedSeconds / TURN_FLASH_SEGMENT_SECONDS).toInt().coerceIn(0, TURN_FLASH_SEGMENTS - 1)
        val isFlashSegment = segmentIndex % 2 == 0
        if (!isFlashSegment) return null
        return if (tank.color == Color.WHITE) Color.MAGENTA else Color.WHITE
    }

    private fun drawCraterScars(canvas: Canvas, terrain: HeightMap, transform: WorldTransform) {
        craterPath.reset()
        var started = false
        for (x in 0 until terrain.width) {
            val original = originalGroundY.getOrElse(x) { terrain.groundY[x] }
            val current = terrain.groundY[x]
            if (current <= original) {
                started = false
                continue
            }
            val px = transform.screenX(x.toFloat())
            val py = transform.screenY(current.toFloat())
            if (!started) {
                craterPath.moveTo(px, py)
                started = true
            } else {
                craterPath.lineTo(px, py)
            }
        }
        craterPaint.strokeWidth = CRATER_STROKE_WIDTH * transform.scale
        canvas.drawPath(craterPath, craterPaint)
    }

    private fun drawHorizonLine(canvas: Canvas, terrain: HeightMap, transform: WorldTransform) {
        horizonPath.reset()
        var started = false
        for (x in 0 until terrain.width) {
            val groundY = terrain.groundY[x]
            val px = transform.screenX(x.toFloat())
            val py = transform.screenY(groundY.toFloat())
            if (!started) {
                horizonPath.moveTo(px, py)
                started = true
            } else {
                horizonPath.lineTo(px, py)
            }
        }
        horizonPaint.strokeWidth = HORIZON_STROKE_WIDTH * transform.scale
        canvas.drawPath(horizonPath, horizonPaint)
    }

    private fun drawImpactEffects(canvas: Canvas, impactEffects: List<ImpactEffect>, transform: WorldTransform) {
        for (impact in impactEffects) {
            if (impact.age >= IMPACT_EFFECT_LIFETIME_SECONDS) continue

            val screenX = transform.screenX(impact.x)
            val screenY = transform.screenY(impact.y)
            val maxRadius = impact.blastRadius * transform.scale

            when {
                impact.age < GROWTH_SECONDS -> {
                    // Phase 1: grow from 0 to full size over 0.5 seconds
                    val growthFraction = impact.age / GROWTH_SECONDS
                    drawGradientCircle(canvas, screenX, screenY, maxRadius * growthFraction, growthFraction)
                }
                impact.age < GROWTH_SECONDS + HOLD_SECONDS -> {
                    // Phase 2: hold at full size for 1 second
                    drawGradientCircle(canvas, screenX, screenY, maxRadius, 1f)
                }
                else -> {
                    // Phase 3: fade away over 1 second
                    val fadeFraction = (IMPACT_EFFECT_LIFETIME_SECONDS - impact.age) / FADE_SECONDS
                    drawGradientCircle(canvas, screenX, screenY, maxRadius, fadeFraction)
                }
            }
        }
        impactPaint.alpha = 255
    }

    private fun drawGradientCircle(canvas: Canvas, cx: Float, cy: Float, radius: Float, alphaMult: Float) {
        if (radius <= 0f) return

        // Draw from outside to inside for proper gradient layering: red -> yellow -> white
        val steps = 20
        for (i in steps downTo 1) {
            val fraction = i.toFloat() / steps
            val r = radius * fraction

            val red = 255
            val green: Int
            val blue: Int
            when {
                fraction > 0.66f -> {
                    // Red to yellow: interpolate green from 0 to 255
                    val colorFraction = (fraction - 0.66f) / 0.34f
                    green = (colorFraction * 200).toInt()
                    blue = 0
                }
                fraction > 0.33f -> {
                    // Yellow to white: interpolate green and blue from yellow towards white
                    val colorFraction = (fraction - 0.33f) / 0.33f
                    green = (200 + colorFraction * 55).toInt()
                    blue = (colorFraction * 55).toInt()
                }
                else -> {
                    // White center, fading to yellow: already white
                    green = 255
                    blue = 255
                }
            }

            impactPaint.color = Color.argb((alphaMult * 255).toInt(), red, green, blue)
            canvas.drawCircle(cx, cy, r, impactPaint)
        }
    }

    /** Short-lived marker at a wall/ceiling bounce point - see [BounceEffect] - dispatched
     * to a per-[EdgeType] shape/color/animation below so each bounce type reads as visually
     * distinct on the game canvas. Never called for [EdgeType.NONE]/[EdgeType.BLAST_STEEL]
     * (the latter produces an [ImpactEffect] instead, drawn like a real explosion). */
    private fun drawBounceEffects(canvas: Canvas, bounceEffects: List<BounceEffect>, transform: WorldTransform) {
        for (bounce in bounceEffects) {
            if (bounce.age >= BOUNCE_EFFECT_LIFETIME_SECONDS) continue

            val screenX = transform.screenX(bounce.x)
            val screenY = transform.screenY(bounce.y)
            val lifeFraction = bounce.age / BOUNCE_EFFECT_LIFETIME_SECONDS
            val growFraction = (lifeFraction / BOUNCE_GROW_FRACTION).coerceAtMost(1f)
            val fadeAlpha = if (lifeFraction < BOUNCE_GROW_FRACTION) {
                1f
            } else {
                1f - (lifeFraction - BOUNCE_GROW_FRACTION) / (1f - BOUNCE_GROW_FRACTION)
            }
            val radius = BOUNCE_MARK_RADIUS * transform.scale

            when (bounce.edgeType) {
                EdgeType.PADDED -> drawPaddedMark(canvas, screenX, screenY, radius, growFraction, fadeAlpha)
                EdgeType.RUBBER -> drawRubberMark(canvas, screenX, screenY, radius, growFraction, fadeAlpha)
                EdgeType.SPRING -> drawSpringMark(canvas, screenX, screenY, radius, growFraction, fadeAlpha)
                EdgeType.REFLECTIVE -> drawReflectiveMark(canvas, screenX, screenY, radius, growFraction, fadeAlpha)
                EdgeType.WRAP -> drawWrapMark(canvas, screenX, screenY, radius, growFraction, fadeAlpha)
                EdgeType.NONE, EdgeType.BLAST_STEEL -> Unit
            }
        }
    }

    /** Soft baby-blue squash: starts wide-and-flat, relaxes toward round as it fades. */
    private fun drawPaddedMark(canvas: Canvas, cx: Float, cy: Float, radius: Float, growFraction: Float, alphaMult: Float) {
        val c = colorFor(EdgeType.PADDED)!!
        edgeMarkPaint.color = Color.argb((alphaMult * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
        val squash = 1f - growFraction * 0.6f
        edgeMarkRect.set(cx - radius * 2f, cy - radius * squash, cx + radius * 2f, cy + radius * squash)
        canvas.drawOval(edgeMarkRect, edgeMarkPaint)
    }

    /** Pink starburst: 6 lines snap outward from the point, then fade. */
    private fun drawRubberMark(canvas: Canvas, cx: Float, cy: Float, radius: Float, growFraction: Float, alphaMult: Float) {
        val c = colorFor(EdgeType.RUBBER)!!
        edgeMarkPaint.color = Color.argb((alphaMult * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
        edgeMarkPaint.strokeWidth = 2f
        val length = radius * 1.5f * growFraction
        for (i in 0 until 6) {
            val angleRad = Math.toRadians((i * 60).toDouble())
            val dx = (cos(angleRad) * length).toFloat()
            val dy = (sin(angleRad) * length).toFloat()
            canvas.drawLine(cx, cy, cx + dx, cy + dy, edgeMarkPaint)
        }
    }

    /** Cyan zigzag coil that scales up from the point, then fades. */
    private fun drawSpringMark(canvas: Canvas, cx: Float, cy: Float, radius: Float, growFraction: Float, alphaMult: Float) {
        edgeMarkPaint.color = Color.argb((alphaMult * 255).toInt(), 0x29, 0xB6, 0xF6)
        edgeMarkPaint.style = Paint.Style.STROKE
        edgeMarkPaint.strokeWidth = 2f
        val size = radius * growFraction
        edgeMarkPath.reset()
        edgeMarkPath.moveTo(cx - size, cy - size)
        edgeMarkPath.lineTo(cx - size / 2f, cy + size)
        edgeMarkPath.lineTo(cx, cy - size)
        edgeMarkPath.lineTo(cx + size / 2f, cy + size)
        edgeMarkPath.lineTo(cx + size, cy - size)
        canvas.drawPath(edgeMarkPath, edgeMarkPaint)
        edgeMarkPaint.style = Paint.Style.FILL
    }

    /** White sparkle/asterisk - near-instant flash, fades quickly (a mirror glint). */
    private fun drawReflectiveMark(canvas: Canvas, cx: Float, cy: Float, radius: Float, growFraction: Float, alphaMult: Float) {
        edgeMarkPaint.color = Color.argb((alphaMult * 255).toInt(), 0xFF, 0xFF, 0xFF)
        edgeMarkPaint.strokeWidth = 2f
        canvas.drawLine(cx - radius, cy, cx + radius, cy, edgeMarkPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, edgeMarkPaint)
        val diag = radius * 0.7f
        canvas.drawLine(cx - diag, cy - diag, cx + diag, cy + diag, edgeMarkPaint)
        canvas.drawLine(cx - diag, cy + diag, cx + diag, cy - diag, edgeMarkPaint)
    }

    /** Yellow stroked ring that pops (grows then shrinks) at both the exit and entry point
     * of a teleport - [BounceEffect] doesn't distinguish which end this is, so both use the
     * same simple pop animation. */
    private fun drawWrapMark(canvas: Canvas, cx: Float, cy: Float, radius: Float, growFraction: Float, alphaMult: Float) {
        val c = colorFor(EdgeType.WRAP)!!
        edgeMarkPaint.color = Color.argb((alphaMult * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
        edgeMarkPaint.style = Paint.Style.STROKE
        edgeMarkPaint.strokeWidth = 3f
        val r = radius * (0.3f + growFraction * 0.7f)
        canvas.drawCircle(cx, cy, r, edgeMarkPaint)
        edgeMarkPaint.style = Paint.Style.FILL
    }

    private fun drawTank(canvas: Canvas, tank: Tank, terrain: HeightMap, transform: WorldTransform, colorOverride: Int? = null) {
        val cx = transform.screenX(tank.x)
        val cy = transform.screenY(tank.y)
        val halfWidth = TANK_HALF_WIDTH * transform.scale

        val leftX = (tank.x - SLOPE_SAMPLE_OFFSET).toInt()
        val rightX = (tank.x + SLOPE_SAMPLE_OFFSET).toInt()
        val riseTerrain = (terrain.heightAt(rightX) - terrain.heightAt(leftX)).toFloat()
        // Both the rise and run are world-space distances scaled by the same uniform
        // factor, so it cancels out of the ratio - this angle is just as valid computed
        // in world space directly, but staying in already-scaled screen units here avoids
        // a second unit system for no benefit.
        val slopeDeg = Math.toDegrees(
            atan2(riseTerrain.toDouble(), (2 * SLOPE_SAMPLE_OFFSET).toDouble()),
        ).toFloat()

        tankBodyPaint.color = colorOverride ?: tank.color
        canvas.save()
        canvas.rotate(slopeDeg, cx, cy)
        canvas.drawPath(tankBodyPathFor(tank, cx, cy, halfWidth), tankBodyPaint)
        canvas.restore()

        // Barrel angle is an absolute aim reference, so it's drawn unrotated by slope.
        // Full-circle convention matches PhysicsStep.launchVelocity exactly - no separate
        // facing flag, cos/sin alone cover all four quadrants.
        val angleRad = Math.toRadians(tank.angleDeg.toDouble())
        val barrelLength = BARREL_LENGTH * transform.scale
        val endX = cx + (cos(angleRad) * barrelLength).toFloat()
        val endY = cy - (sin(angleRad) * barrelLength).toFloat()
        barrelPaint.strokeWidth = BARREL_STROKE_WIDTH * transform.scale
        canvas.drawLine(cx, cy, endX, endY, barrelPaint)
    }

    private fun drawBurningTank(canvas: Canvas, tank: Tank, transform: WorldTransform) {
        if (fireFrames.isEmpty()) return
        val frameIndex = ((tank.burningElapsed * 1000).toLong() / FIRE_FRAME_DURATION_MS % fireFrames.size).toInt()
        val frame = fireFrames[frameIndex]

        val cx = transform.screenX(tank.x)
        val cy = transform.screenY(tank.y)
        val halfWidth = TANK_HALF_WIDTH * transform.scale

        // Anchored low - just above the tank's base - so most of the flame overlaps the
        // tank's own body (reading as fire coming out of the tank, not floating above
        // it), sized well past the tank's own height so it's clearly visible licking up
        // past the turret. Drawn after the tank body at FIRE_ALPHA so the tank itself
        // stays visible through/around it rather than being hidden.
        val flameBottom = cy - halfWidth * 0.2f
        val flameHeight = halfWidth * FIRE_HEIGHT_MULTIPLIER
        val flameWidth = flameHeight * frame.width / frame.height
        val dst = RectF(cx - flameWidth / 2f, flameBottom - flameHeight, cx + flameWidth / 2f, flameBottom)
        canvas.drawBitmap(frame, null, dst, fireFramePaint)

        burnMessageFor(tank)?.let { drawSpeechBubble(canvas, cx, dst.top, it) }
    }

    /** Shows a tank's pre-fire taunt - see [GameLoopThread.beginFireSequence], which picks
     * the line and passes it down through [draw] as `firingMessage` - in the same bubble
     * style and at the same height above the tank as [drawBurningTank]'s death taunt, so
     * the two read as the same kind of moment. */
    private fun drawFiringSpeechBubble(canvas: Canvas, tank: Tank, transform: WorldTransform, message: String) {
        val cx = transform.screenX(tank.x)
        val cy = transform.screenY(tank.y)
        val halfWidth = TANK_HALF_WIDTH * transform.scale
        val bubbleTailTipY = cy - halfWidth * 0.2f - halfWidth * FIRE_HEIGHT_MULTIPLIER
        drawSpeechBubble(canvas, cx, bubbleTailTipY, message)
    }

    /** The taunt a burning tank is showing - assigned once (the first time this tank is
     * drawn burning) and held for the rest of its burn, since only actively-burning tanks
     * ever reach this call. A tank dies at most once per match, so the entry is simply
     * left behind afterward - harmless, and gone once this renderer's match ends. Fires
     * [onBurnMessageAssigned] exactly once per death (inside [getOrPut]'s lambda, which
     * only ever runs on first insert) so the caller can speak it via TTS. Null when
     * [deathPhrases] is empty (every death phrase disabled or none exist) - the tank then
     * shows no bubble and says nothing, rather than falling back to some hardcoded line. */
    private fun burnMessageFor(tank: Tank): String? =
        burnMessages.getOrPut(tank.id) { deathPhrases.randomOrNull()?.also { onBurnMessageAssigned(tank.id, it) } }

    /** What's left where a tank died - a squat mound in the tank's own color muddied
     * toward grey, with a scatter of black/grey specks on top. Permanent for the rest of
     * the match (see [Tank.isAsh]). */
    private fun drawAshPile(canvas: Canvas, tank: Tank, transform: WorldTransform) {
        val cx = transform.screenX(tank.x)
        val cy = transform.screenY(tank.y)
        val halfWidth = TANK_HALF_WIDTH * transform.scale
        val pileWidth = halfWidth * ASH_PILE_WIDTH_MULTIPLIER
        val pileHeight = halfWidth * ASH_PILE_HEIGHT_MULTIPLIER

        ashPilePaint.color = mutedAshColor(tank.color)
        // A wide, short base mound with progressively narrower, taller ones stacked
        // (overlapping) on top of it - a single flat oval read as a puddle, not a heap.
        val moundRects = ashMoundRectsFor(tank.id)
        for ((index, layer) in ASH_MOUND_LAYERS.withIndex()) {
            val (widthFraction, heightFraction, riseFraction) = layer
            val moundWidth = pileWidth * widthFraction
            val moundHeight = pileHeight * heightFraction
            val moundBottom = cy - pileHeight * riseFraction
            val moundRect = moundRects[index]
            moundRect.set(cx - moundWidth / 2f, moundBottom - moundHeight, cx + moundWidth / 2f, moundBottom)
            canvas.drawOval(moundRect, ashPilePaint)
        }

        for (speck in ashSpecksFor(tank)) {
            ashSpeckPaint.color = if (speck.isBlack) Color.BLACK else Color.DKGRAY
            val sx = cx + speck.nx * pileWidth / 2f
            val sy = cy + speck.ny * pileHeight
            canvas.drawCircle(sx, sy, ASH_SPECK_RADIUS * transform.scale, ashSpeckPaint)
        }
    }

    /** Blends [originalColor] toward a muddy grey rather than replacing it outright, so
     * the ash pile still visibly traces back to which tank it was. Generated once per
     * tank id and cached - see [ashSpecks] - so the speckle pattern doesn't flicker by
     * being re-randomized every frame. */
    private fun ashSpecksFor(tank: Tank): List<AshSpeck> = ashSpecks.getOrPut(tank.id) {
        val rng = kotlin.random.Random(tank.id * 7919 + 13)
        (0 until ASH_SPECK_COUNT).map {
            AshSpeck(
                nx = rng.nextFloat() * 2f - 1f,
                ny = -rng.nextFloat() * 0.7f,
                isBlack = rng.nextBoolean(),
            )
        }
    }

    /** This tank's 3 mound [RectF]s - created once per tank id and cached, since allocating
     * fresh ones every frame for the rest of the match (ash piles never animate) is pure
     * waste. See [ashMoundRects] doc for why only the objects, not their bounds, are cached. */
    private fun ashMoundRectsFor(tankId: Int): List<RectF> =
        ashMoundRects.getOrPut(tankId) { ASH_MOUND_LAYERS.map { RectF() } }

    private fun mutedAshColor(originalColor: Int): Int {
        fun mute(channel: Int) = (channel + ASH_GREY_LEVEL * 2) / 3
        return Color.rgb(
            mute(Color.red(originalColor)),
            mute(Color.green(originalColor)),
            mute(Color.blue(originalColor)),
        )
    }

    /** One fleck's position within an ash pile, normalized to the pile's own half-width
     * ([nx] in [-1, 1]) and height ([ny] in [-1, 0], 0 = the pile's base). */
    private data class AshSpeck(val nx: Float, val ny: Float, val isBlack: Boolean)

    /** One black fleck's position within a Lava region's own fill area, normalized to the
     * region's horizontal span ([alongFraction] in `[0, 1]`, left to right) and vertical
     * span from the region's carved surface down to the map's true bottom ([depthFraction]
     * in `[0, 1]`) - see [drawLavaFill]. */
    private data class LavaSpeck(val alongFraction: Float, val depthFraction: Float, val radius: Float)

    /** One bubble's layout within a drowning tank's own rising bubble cluster - [nx] its
     * horizontal offset (normalized to the tank's own half-width), [startFraction] its phase
     * offset within the endless 0..1 rise-and-fade cycle (so the whole cluster doesn't rise in
     * lockstep), [riseSpeed] how many full cycles per second, [radius] in world-space units -
     * see [drawDrowningBubbles]/[bubblesFor]. */
    private data class Bubble(val nx: Float, val startFraction: Float, val riseSpeed: Float, val radius: Float)

    private fun drawSpeechBubble(canvas: Canvas, cx: Float, tailTipY: Float, message: String) {
        speechBubbleTextPaint.getFontMetrics(speechBubbleFontMetrics)
        val fm = speechBubbleFontMetrics
        val textHeight = fm.descent - fm.ascent
        val boxWidth = speechBubbleTextPaint.measureText(message) + BUBBLE_PADDING_X * 2f
        val boxHeight = textHeight + BUBBLE_PADDING_Y * 2f
        val boxBottom = tailTipY - BUBBLE_TAIL_HEIGHT
        val boxTop = boxBottom - boxHeight
        speechBubbleRect.set(cx - boxWidth / 2f, boxTop, cx + boxWidth / 2f, boxBottom)

        speechBubblePath.reset()
        speechBubblePath.addRoundRect(speechBubbleRect, BUBBLE_CORNER_RADIUS, BUBBLE_CORNER_RADIUS, Path.Direction.CW)
        speechBubbleTailFillPath.reset()
        speechBubbleTailFillPath.moveTo(cx - BUBBLE_TAIL_WIDTH / 2f, boxBottom)
        speechBubbleTailFillPath.lineTo(cx + BUBBLE_TAIL_WIDTH / 2f, boxBottom)
        speechBubbleTailFillPath.lineTo(cx, tailTipY)
        speechBubbleTailFillPath.close()
        // Open path (no closing top edge) so the tail's outline doesn't draw a stray
        // line across the bubble's own bottom border where the two shapes meet.
        speechBubbleTailOutlinePath.reset()
        speechBubbleTailOutlinePath.moveTo(cx - BUBBLE_TAIL_WIDTH / 2f, boxBottom)
        speechBubbleTailOutlinePath.lineTo(cx, tailTipY)
        speechBubbleTailOutlinePath.lineTo(cx + BUBBLE_TAIL_WIDTH / 2f, boxBottom)

        canvas.drawPath(speechBubbleTailFillPath, speechBubblePaint)
        canvas.drawPath(speechBubblePath, speechBubblePaint)
        canvas.drawPath(speechBubblePath, speechBubbleBorderPaint)
        canvas.drawPath(speechBubbleTailOutlinePath, speechBubbleBorderPaint)

        val baselineY = (boxTop + boxBottom) / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(message, cx, baselineY, speechBubbleTextPaint)
    }

    /** [tank]'s body outline (see [TankShape]) built into a screen-space [Path] - the [Path]
     * object is created once per tank id and cached, but still rebuilt via [Path.reset] every
     * call, since [cx]/[cy] (the tank's own position) and the canvas-rotation this is drawn
     * under can genuinely change frame to frame. */
    private fun tankBodyPathFor(tank: Tank, cx: Float, cy: Float, halfWidth: Float): Path {
        val path = tankBodyPaths.getOrPut(tank.id) { Path() }
        path.reset()
        tank.shape.outline.forEachIndexed { index, (nx, ny) ->
            val x = cx + nx * halfWidth
            val y = cy + ny * halfWidth
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    companion object {
        private const val CRATER_STROKE_WIDTH = 10f
        private const val HORIZON_STROKE_WIDTH = 3f
        // Screen-space, not world-space (unlike HORIZON_STROKE_WIDTH etc.) - this is a frame
        // around the play area, not a feature of the world, so it should look the same
        // thickness regardless of how zoomed-in the terrain itself is.
        private const val EDGE_BORDER_THICKNESS_FRACTION = 0.02f

        // EdgeType.WRAP's extra glow/glitter flourish - see drawWrapEffects. The glow extends
        // this many border-thicknesses beyond the solid border itself, fading from WRAP_GLOW_ALPHA
        // down to fully transparent; sparkle alpha oscillates between the min/max bounds as it
        // twinkles, drawn as small warm-white specks scattered across the border+glow band.
        private const val WRAP_GLOW_WIDTH_MULTIPLIER = 2.5f
        private const val WRAP_GLOW_ALPHA = 90
        private const val WRAP_SPARKLE_COUNT = 26
        private const val WRAP_SPARKLE_MIN_ALPHA = 40
        private const val WRAP_SPARKLE_MAX_ALPHA = 230
        private const val PROJECTILE_RADIUS = 2.5f
        private const val BARREL_STROKE_WIDTH = 1.25f

        // FloorType.WATER's wavy rising border/fill and FloorType.LAVA's small surface bumps -
        // see drawWaterOverlay/drawLavaBumps, both built on the same waveY sine helper. Water's
        // amplitude/wavelength are large enough to read as a real wave; Lava's are deliberately
        // much smaller ("very small waves/bumps" per the user's own spec) so it reads as gentle
        // surface bubbling rather than a tide. WAVE_STEP_PX is screen-space (how finely the wave
        // path is sampled), shared by both since neither needs a finer step than the other.
        private const val WATER_WAVE_AMPLITUDE = 4f
        private const val WATER_WAVE_LENGTH_PX = 40f
        private const val WATER_FILL_ALPHA = 130
        private const val LAVA_BUMP_AMPLITUDE = 1.2f
        private const val LAVA_BUMP_LENGTH_PX = 18f
        private const val WAVE_SPEED = 2f
        private const val WAVE_STEP_PX = 6f
        private const val LAVA_SPECK_COUNT = 14

        // PhotoUsageMode.TERRAIN's procedural sky look - see drawSkyLook/drawGlowDisc/
        // generateClouds/generateStars.
        private const val GLOW_DISC_RADIUS_MULTIPLIER = 2.5f
        private const val CLOUD_COUNT = 5
        private const val STAR_COUNT = 40

        // Tank body half-width shares Tank.RADIUS with GameEngine's hit-detection radius,
        // so the visual size and the actual collision size never drift apart. The rest of
        // these scale proportionally with it (4x the old 14f-radius tuning, then halved
        // back down to 28f, halved to 14f, halved again to 7f: barrel 13->52->26->13->9.75,
        // slope sample offset 6->24->12->6). All are world-space units, scaled by
        // WorldTransform.scale like every other size in this file.
        private const val TANK_HALF_WIDTH = Tank.RADIUS
        private const val BARREL_LENGTH = 9.75f
        private const val SLOPE_SAMPLE_OFFSET = 6

        // Explosion animation phases: grow from 0 to full over 0.125s, hold for 0.25s, fade for 0.25s.
        // GROWTH_SECONDS also mirrors GameEngine's own DEATH_EXPLOSION_GROWTH_SECONDS
        // constant (kept separate since this is a rendering concern, not engine state) -
        // GameEngine uses that same duration to decide when a dying tank's body switches
        // over to Tank.isAsh.
        private const val GROWTH_SECONDS = 0.125f
        private const val HOLD_SECONDS = 0.25f
        private const val FADE_SECONDS = 0.25f
        private const val IMPACT_EFFECT_LIFETIME_SECONDS = GROWTH_SECONDS + HOLD_SECONDS + FADE_SECONDS

        // Wall/ceiling bounce marks: grow over the first 30% of a short 0.25s life, then
        // fade over the rest - see drawBounceEffects. Mirrors GameEngine's own
        // BOUNCE_EFFECT_LIFETIME_SECONDS constant (kept separate since this is a rendering
        // concern, not engine state).
        private const val BOUNCE_EFFECT_LIFETIME_SECONDS = 0.25f
        private const val BOUNCE_GROW_FRACTION = 0.3f
        private const val BOUNCE_MARK_RADIUS = 10f

        // Start-of-turn color flash: 4 white (or magenta, for an already-white tank)
        // flashes, each lasting 0.1s, separated by 0.1s back at the normal color.
        private const val TURN_FLASH_SEGMENT_SECONDS = 0.1f
        private const val TURN_FLASH_COUNT = 4
        private const val TURN_FLASH_SEGMENTS = TURN_FLASH_COUNT * 2
        private const val TURN_FLASH_TOTAL_SECONDS = TURN_FLASH_SEGMENT_SECONDS * TURN_FLASH_SEGMENTS

        // assets/fire/frame_00.png..frame_23.png: every other frame of the source 48-frame
        // @80ms GIF, so 24 frames @160ms reproduces the same ~3.84s loop.
        private const val FIRE_FRAME_COUNT = 24
        private const val FIRE_FRAME_DURATION_MS = 160L
        private const val FIRE_HEIGHT_MULTIPLIER = 2.2f
        private const val FIRE_ALPHA = (0.75f * 255).toInt()

        private const val ASH_PILE_WIDTH_MULTIPLIER = 1.6f
        private const val ASH_PILE_HEIGHT_MULTIPLIER = 0.7f
        // Each mound layer, base first: (width fraction of the pile's full width, height
        // fraction of the pile's full height, how far up from the base its bottom edge
        // sits, as a fraction of the pile's full height) - see drawAshPile.
        private val ASH_MOUND_LAYERS = listOf(
            Triple(1f, 0.5f, 0f),
            Triple(0.65f, 0.65f, 0.28f),
            Triple(0.35f, 0.8f, 0.5f),
        )
        private const val ASH_SPECK_COUNT = 10
        private const val ASH_SPECK_RADIUS = 1.2f
        // Mostly grey, with a hint of the tank's own color still showing through.
        private const val ASH_GREY_LEVEL = 130

        private const val BUBBLE_PADDING_X = 12f
        private const val BUBBLE_PADDING_Y = 8f
        private const val BUBBLE_CORNER_RADIUS = 10f
        private const val BUBBLE_TAIL_WIDTH = 14f
        private const val BUBBLE_TAIL_HEIGHT = 10f

        // FloorType.WATER's drowning sequence - see drawDrowningBubbles/drawRisingTank.
        private const val BUBBLE_COUNT = 5
        private const val BUBBLE_RISE_HEIGHT_MULTIPLIER = 3f
        private const val BUBBLE_MAX_ALPHA = 200
        // Mirrors GameEngine's own DROWNING_RISE_DURATION_SECONDS constant (kept separate
        // since this is a rendering concern, not engine state, the same existing pattern
        // GROWTH_SECONDS uses for DEATH_EXPLOSION_GROWTH_SECONDS) - keep numerically in sync
        // by hand if the engine's copy ever changes.
        private const val DROWNING_RISE_DURATION_SECONDS = 1.5f
    }
}
