package com.vysion.xr.perception

import android.app.Activity
import android.app.Application
import android.content.res.AssetManager
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import androidx.xr.arcore.Anchor
import androidx.xr.arcore.AnchorCreateSuccess
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.Depth
import androidx.xr.arcore.RenderViewpoint
import androidx.xr.arcore.TrackingState
import androidx.xr.runtime.AnchorPersistenceMode
import androidx.xr.runtime.Config
import androidx.xr.runtime.DepthEstimationMode
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.PlaneTrackingMode
import androidx.xr.runtime.RenderingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.XrDevice
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.AnchorSpace
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.PanelEntity
import androidx.xr.scenecore.PointSourceParams
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.SpatialSoundPool
import androidx.xr.scenecore.scene
import com.vysion.xr.core.AnchorId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *  R3 · Spatial Perception — the ARCore for Jetpack XR session and the raw perception streams.
 *
 *  Everything below the "Value types" banner is pure Kotlin (no Android, no androidx.xr) so the
 *  cone math, plane semantics and relative geometry run in JVM unit tests. The session class at
 *  the bottom is the only place the XR runtime is touched; session startup, depth streaming and head pose are
 *  implemented, while plane, hand, anchor, and spatial-audio hooks remain in progress.
 *
 *  Owner: R3 · SRS §8 "ARCore for Jetpack XR session, depth, planes, anchors, hands" · due Sat 12:00
 *  (session, A-5); Sat 13:00 is only the A-6 probe (one ping: audible? positioned?); the spatial-audio
 *  P0 (ping every 3 s while guiding, SpatialAudioPinger) is due Sat 21:00 and merges at gate 6.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 */

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Value types shared by every R3 file (pure; mirror androidx.xr.runtime.math without depending on it)
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Plain 3-vector in the XR world frame, meters. OpenXR / Jetpack XR convention: +X right, +Y up,
 * **-Z forward** (`Pose.forward` is `rotation * (0, 0, -1)`). Mirrors `androidx.xr.runtime.math.Vector3`
 * so pure code and JVM tests never import the XR runtime (ADR-0005 keeps `core` the only shared package;
 * these types stay inside `perception`).
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float): Vec3 = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3): Vec3 = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Float get() = sqrt(dot(this))
    fun normalized(): Vec3 = if (length == 0f) this else this * (1f / length)

    companion object {
        val ZERO: Vec3 = Vec3(0f, 0f, 0f)
        val UP: Vec3 = Vec3(0f, 1f, 0f)

        /** World forward at yaw 0 (-Z). */
        val FORWARD: Vec3 = Vec3(0f, 0f, -1f)
    }
}

/** 2-vector in a plane's local frame (`Plane.State.vertices` are `Vector2`), meters. */
data class Vec2(val x: Float, val y: Float)

/**
 * Field of view of the depth image in degrees. On the device it comes from
 * `RenderViewpoint.left/mono(session).state.value.fieldOfView` (`androidx.xr.runtime.math.FieldOfView`:
 * `angleLeft`, `angleRight`, `angleUp`, `angleDown`, radians per the OpenXR `XrFovf` convention,
 * left/down negative; the unit is `// stack.md: UNVERIFIED`): horizontal = angleRight - angleLeft,
 * vertical = angleUp - angleDown, both converted to degrees. Never a hardcoded constant (NFR Honesty,
 * INV-7): a depth frame without a measured FOV is not emitted.
 */
data class FovDeg(val horizontal: Float, val vertical: Float)

/** Mirrors `androidx.xr.arcore.TrackingState` (TRACKING, PAUSED, STOPPED). */
enum class Tracking { TRACKING, PAUSED, STOPPED }

/** Mirrors `androidx.xr.arcore.PlaneLabel` (UNKNOWN, WALL, FLOOR, CEILING, TABLE). */
enum class PlaneKind { WALL, FLOOR, CEILING, TABLE, UNKNOWN }

enum class HandSide { LEFT, RIGHT }

/**
 * One depth map, copied out of `Depth.State.smoothDepthMap` (`FloatBuffer`, meters, row-major,
 * index = y * width + x, `0f` = no data) and `smoothConfidenceMap` (`ByteBuffer`, 0..255, 255 = most
 * confident; `null` when the runtime does not provide it). [timestampNanos] is
 * `SystemClock.elapsedRealtimeNanos()` at copy time (monotonic, H-21) so the FR-N1 "≤ 300 ms after
 * threshold crossed" latency can be measured against the cue's emit time.
 *
 * Not a `data class`: arrays compare by identity and a depth map is never compared.
 */
class DepthFrame(
    val width: Int,
    val height: Int,
    val depthM: FloatArray,
    val confidence: ByteArray?,
    val fov: FovDeg,
    val timestampNanos: Long,
) {
    init {
        require(width > 0 && height > 0) { "DepthFrame needs a positive size: ${width}x$height" }
        require(depthM.size == width * height) { "depthM has ${depthM.size} values, expected ${width * height}" }
        require(confidence == null || confidence.size == width * height) { "confidence size mismatch" }
    }

    /** Depth at a pixel, meters; `0f` means no data. */
    fun depthAt(x: Int, y: Int): Float = depthM[y * width + x]

    /** Confidence 0..255 at a pixel; 255 when the runtime provides no confidence map. */
    fun confidenceAt(x: Int, y: Int): Int = confidence?.let { it[y * width + x].toInt() and 0xFF } ?: 255
}

/**
 * One tracked plane from `Plane.subscribe(session)` / `plane.state.value` (FR-N5).
 *
 * Frame conventions (`// stack.md: UNVERIFIED` which axis of `centerPose` is the normal; the
 * ARCore convention is local +Y = normal, +X / +Z span the plane and `vertices` are (x, z) in that
 * local frame): [center] = `centerPose.translation`, [normal] = `centerPose.up`, [axisX] =
 * `centerPose.right`, [axisZ] = `-centerPose.forward` (local +Z), all in world meters; [polygon] =
 * `vertices` as (x, z) in plane-local meters. The first device run (A-5) confirms the mapping by
 * logging a FLOOR plane's normal, which must be ≈ (0, 1, 0).
 */
data class PlaneObservation(
    val id: String,
    val kind: PlaneKind,
    val tracking: Tracking,
    val center: Vec3,
    val normal: Vec3,
    val axisX: Vec3,
    val axisZ: Vec3,
    val polygon: List<Vec2>,
    val timestampNanos: Long,
)

/**
 * One hand from `Hand.left/right(session).state.value` (FR-G2 pinch is R1's P1; FR-D4 pointing is
 * R3's P2). Joints are `handJoints[HandJointType.THUMB_TIP]`, `[INDEX_TIP]`, `[INDEX_PROXIMAL]`
 * transformed with `session.scene.perceptionSpace.transformPoseTo(pose, session.scene.activitySpace)`
 * (stack.md R3 "Hands"). `null` joint = not tracked this frame.
 */
data class HandObservation(
    val side: HandSide,
    val tracking: Tracking,
    val thumbTip: Vec3?,
    val indexTip: Vec3?,
    val indexProximal: Vec3?,
    val timestampNanos: Long,
)

/**
 * Head pose from `ArDevice.getInstance(session).state.value.devicePose` (needs
 * `DeviceTrackingMode.SPATIAL` + `HEAD_TRACKING`; stack.md R3 "Head pose").
 *
 * Angle conventions used by every R3 file (and by `HeadPose` in `core`, which this maps to):
 * - [yawDeg]: heading, 0 = facing world -Z, **positive = turned to the right** (clockwise seen from
 *   above), normalized to (-180, 180]. Matches `RelativeTarget.bearingDeg` ("positive to the right").
 * - [pitchDeg]: positive = looking up. [rollDeg]: positive = right ear down.
 * Built from the pose's forward and up vectors by [HeadAngles.fromForwardUp] (pure, tested).
 */
data class RawHeadPose(
    val position: Vec3,
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val tracking: Tracking,
    val timestampNanos: Long,
) {
    /** Horizontal forward unit vector for [yawDeg]: yaw 0 → (0, 0, -1); yaw +90 → (1, 0, 0) = right. */
    val forwardFlat: Vec3 get() = HeadAngles.forwardForYaw(yawDeg)
}

/** Pose of a native anchor from `anchor.state.value.pose / .trackingState` (FR-N3, FR-N4). */
data class AnchorPoseSample(
    val id: AnchorId,
    val position: Vec3,
    val tracking: Tracking,
    val timestampNanos: Long,
)

/** Lifecycle of the XR session. `UNAVAILABLE` carries no number and renders as "not available" (INV-7). */
enum class SessionState { NOT_STARTED, STARTING, RUNNING, UNAVAILABLE, STOPPED }

/**
 * When [XrPerceptionSession] opens the runtime session. Pure, tested.
 *
 * - `explicit` = [XrPerceptionSession.start] from `MainActivity` (after permissions, on every `onStart`):
 *   opens unless a session is already RUNNING or STARTING, so `start()` after `stop()` reopens.
 * - implicit = [XrPerceptionSession.ensureStarted] from [XrNavigationEngine.start] and
 *   [XrAnchorStore.placeFromBearing]: only retries a session that came up UNAVAILABLE. `MainActivity` starts
 *   the graph as soon as camera + microphone are granted and asks for the XR permissions afterwards, so the
 *   first attempt can fail on a missing `SCENE_UNDERSTANDING_*` grant and nothing else would retry it. It
 *   never opens a session that was never started or that `onStop` stopped (the next `onStart` reopens it).
 */
internal object SessionStartPolicy {
    fun shouldOpen(state: SessionState, explicit: Boolean): Boolean = when (state) {
        SessionState.RUNNING, SessionState.STARTING -> false
        SessionState.UNAVAILABLE -> true
        SessionState.NOT_STARTED, SessionState.STOPPED -> explicit
    }
}

/**
 * Opaque handle to the SceneCore `Entity` parented under an anchor's `AnchorSpace`, the point at
 * which [SpatialAudioPinger] plays (stack.md R3 rows "Spatial audio" and "Entities"). `native` holds
 * the `androidx.xr.scenecore.Entity` once FR-N3 lands; it is `Any?` here so the pinger stays
 * compilable while the SceneCore entity API is alpha (RK-10).
 */
class AnchorEntity internal constructor(val anchorId: AnchorId, internal val native: Any?)

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Pure helpers: head angles
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** Angle helpers shared by [RawHeadPose], [RelativeGeometry] and [PlaneSemantics]. Pure, tested. */
object HeadAngles {
    /** Normalizes any angle to (-180, 180]. */
    fun wrapDeg(deg: Float): Float {
        var d = deg % 360f
        if (d <= -180f) d += 360f
        if (d > 180f) d -= 360f
        return d
    }

    /** Horizontal forward unit vector for a yaw (0 → -Z, +90 → +X). */
    fun forwardForYaw(yawDeg: Float): Vec3 {
        val r = Math.toRadians(yawDeg.toDouble())
        return Vec3(Math.sin(r).toFloat(), 0f, -Math.cos(r).toFloat())
    }

    /**
     * Yaw / pitch / roll (degrees, conventions in [RawHeadPose]) from a pose's forward and up unit
     * vectors (`Pose.forward`, `Pose.up`). Returns (yaw, pitch, roll).
     */
    fun fromForwardUp(forward: Vec3, up: Vec3): Triple<Float, Float, Float> {
        val f = forward.normalized()
        val yaw = Math.toDegrees(Math.atan2(f.x.toDouble(), (-f.z).toDouble())).toFloat()
        val pitch = Math.toDegrees(Math.asin(f.y.toDouble().coerceIn(-1.0, 1.0))).toFloat()
        // right = forward × up; with no roll it is horizontal (right.y == 0) and up.y > 0.
        val right = f.cross(up.normalized())
        val roll = Math.toDegrees(Math.atan2((-right.y).toDouble(), up.y.toDouble())).toFloat()
        return Triple(wrapDeg(yaw), pitch, wrapDeg(roll))
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The port the other R3 classes consume (so unit tests can fake the device)
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * What [XrNavigationEngine], [XrAnchorStore], [XrHeadPoseSource] and [SpatialAudioPinger] need from
 * the session. [XrPerceptionSession] is the only production implementation; the tests use
 * `FakePerceptionSource`. Internal on purpose: it never crosses the package (ADR-0005).
 */
internal interface PerceptionSource {
    /** Depth maps, latest-wins (a depth map is ~100 KB; nothing may queue them). */
    val depthFrames: Flow<DepthFrame>

    /** All currently known planes; replaced wholesale on every runtime update. */
    val planes: StateFlow<List<PlaneObservation>>

    val hands: Flow<HandObservation>

    /** `null` until `DeviceTrackingMode.SPATIAL` delivers the first pose. */
    val headPose: StateFlow<RawHeadPose?>

    /**
     * Retries a session that came up unavailable ([SessionStartPolicy]); a no-op otherwise. Idempotent;
     * never throws (the skeleton app must launch, AGENTS.md §5).
     */
    suspend fun ensureStarted()

    /**
     * FR-N3 "samples depth along that bearing": world point hit along [worldYawDeg] / [pitchDeg]
     * from the current head position, or `null` when no trusted depth exists there (RK-5: only
     * distances we trust).
     */
    suspend fun sampleDepthAlongBearing(worldYawDeg: Float, pitchDeg: Float): Vec3?

    /** Native anchor at [position]; `null` on `AnchorCreateTrackingUnavailable` / `ResourcesExhausted`. */
    suspend fun createAnchor(position: Vec3): AnchorId?

    /**
     * Pose stream of one anchor. Emits a sample with [Tracking.STOPPED] as its last value when the
     * anchor is detached or the runtime stops tracking it; an unknown id emits one STOPPED sample.
     */
    fun anchorPose(id: AnchorId): Flow<AnchorPoseSample>

    suspend fun detachAnchor(id: AnchorId)
    suspend fun detachAllAnchors()
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The session
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Owns the ARCore for Jetpack XR [Session] and turns its `StateFlow`s into the raw streams the rest
 * of `perception` consumes ([PerceptionSource]). Provides nothing to other packages directly: R1
 * reaches head pose through [XrHeadPoseSource], navigation through [XrNavigationEngine], anchors
 * through [XrAnchorStore] (all `core` contracts).
 *
 * - FR ids: FR-N1 (depth), FR-N3/FR-N4 (anchors, head pose), FR-N5 (planes), FR-G2 P1 / FR-D4 P2 (hands).
 * - SRS due: Sat 12:00 "ARCore for Jetpack XR session … depth, plane, hand, and device-pose configs enabled".
 * - Owner: R3. API: docs/stack.md §3 → R3 (every call named below was verified by javap against the
 *   rc02 `arcore` / `scenecore` jars and the runtime jar on 2026-10-17, except lines marked UNVERIFIED).
 * - Assumption: A-5 (depth + planes return data with `SCENE_UNDERSTANDING_*` granted; fallback RK-5),
 *   A-6 (spatial audio audible; fallback: plain earcon, see [SpatialAudioPinger]).
 *
 * Constructor matches `AppGraph`: `(Application, CoroutineScope)` and touches nothing on the device.
 *
 * Lifecycle (`AppGraph.perception`, `MainActivity`): [start] with the Activity after permissions and on
 * every `onStart` (`Session.create` needs that Activity as context and `LifecycleOwner`, stack.md D7);
 * [stop] from `AppGraph.stop()` on every `onStop`. Reversible: `start` after `stop` opens a fresh run
 * (new feeders, no anchors, no stale pose). [ensureStarted] only retries a session that came up
 * UNAVAILABLE ([SessionStartPolicy]). [scope] is the graph scope; only `AppGraph.release()` cancels it.
 *
 * Threading (docs/architecture.md): every session API is called from [scope] (`Dispatchers.Default`);
 * `Session.create` runs under `Dispatchers.IO` (stack.md D7).
 *
 * Type bridges the feeder snippets below call (`v3()`, `toVector3()`, `toTracking()`, `toPlaneKind()`) do not
 * exist yet: R3 writes them as private top-level functions in this file. `TrackingState` and `PlaneLabel`
 * are final classes with static instances, not enums (javap, arcore rc02), so each `when` needs an `else`:
 * ```
 * private fun Vector3.v3(): Vec3 = Vec3(x, y, z)
 * private fun Vec3.toVector3(): Vector3 = Vector3(x, y, z)                 // Vector3(Float, Float, Float)
 * private fun TrackingState.toTracking(): Tracking = when (this) {
 *     TrackingState.TRACKING -> Tracking.TRACKING; TrackingState.PAUSED -> Tracking.PAUSED; else -> Tracking.STOPPED
 * }
 * private fun PlaneLabel.toPlaneKind(): PlaneKind = when (this) {
 *     PlaneLabel.WALL -> PlaneKind.WALL; PlaneLabel.FLOOR -> PlaneKind.FLOOR; PlaneLabel.CEILING -> PlaneKind.CEILING
 *     PlaneLabel.TABLE -> PlaneKind.TABLE; else -> PlaneKind.UNKNOWN
 * }
 * ```
 * `Pose(Vector3)` exists (rotation defaults to `Quaternion()` = identity), so an anchor at a point is
 * `Pose(position.toVector3())`. Q21 (docs/modules/r3-spatial-perception.md "Contract issues" 7): check on the
 * first device run whether `Session.create(context = activity)` returns the same object as Compose for XR's
 * `LocalSession.current` before relying on `session.scene` here.
 */
class XrPerceptionSession(
    private val app: Application,
    internal val scope: CoroutineScope,
) : PerceptionSource {

    private val _state = MutableStateFlow(SessionState.NOT_STARTED)

    /** Shown by the diagnostics overlay as text, never as a number (INV-7). */
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** `null` until probed with `XrDevice.getCurrentDevice(app).isDepthEstimationModeSupported(SMOOTH_ONLY)`. */
    private val _depthSupported = MutableStateFlow<Boolean?>(null)
    val depthSupported: StateFlow<Boolean?> = _depthSupported.asStateFlow()

    /** `null` until probed with `session.scene.spatialCapabilities.contains(SpatialCapability.SPATIAL_AUDIO)` (A-6). */
    private val _spatialAudioSupported = MutableStateFlow<Boolean?>(null)
    val spatialAudioSupported: StateFlow<Boolean?> = _spatialAudioSupported.asStateFlow()

    // Latest-wins buffers: a depth map or a hand sample is never queued (H-33).
    private val _depthFrames = MutableSharedFlow<DepthFrame>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _planes = MutableStateFlow<List<PlaneObservation>>(emptyList())
    private val _hands = MutableSharedFlow<HandObservation>(
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _headPose = MutableStateFlow<RawHeadPose?>(null)

    /**
     * FR-N1 depth stream, ≤ runtime rate; [XrNavigationEngine] samples it at 10 Hz.
     *
     * The feeder selects a stereo-left or mono depth/view pair, copies each smooth depth map and
     * optional confidence map into app-owned arrays, and emits only when the field of view is usable.
     * The FOV angles are treated as radians per OpenXR convention (unit still UNVERIFIED; see
     * `docs/stack.md`). It logs one trusted minimum-depth sample for A-5, never the map itself (INV-3).
     */
    override val depthFrames: Flow<DepthFrame> = _depthFrames.asSharedFlow()

    /**
     * FR-N5 plane stream.
     *
     * Feeder to implement (stack.md R3 "Planes"):
     * ```
     * Plane.subscribe(session).collect { set ->
     *     _planes.value = set.map { p ->
     *         val s = p.state.value
     *         PlaneObservation(id = p.hashCode().toString(), kind = s.label.toPlaneKind(),
     *             tracking = s.trackingState.toTracking(),
     *             center = s.centerPose.translation.v3(), normal = s.centerPose.up.v3(),
     *             axisX = s.centerPose.right.v3(), axisZ = (s.centerPose.forward * -1f).v3(),
     *             polygon = s.vertices.map { Vec2(it.x, it.y) }, timestampNanos = SystemClock.elapsedRealtimeNanos())
     *     }
     * }
     * ```
     */
    override val planes: StateFlow<List<PlaneObservation>> = _planes.asStateFlow()

    /**
     * Hand stream (R1 consumes pinch through its own `HandSource` later; R3's FR-D4 pointing is P2).
     *
     * Feeder to implement (stack.md R3 "Hands"): `Hand.left(session).state` and `Hand.right(session).state`,
     * joints transformed with `session.scene.perceptionSpace.transformPoseTo(pose, session.scene.activitySpace)`.
     */
    override val hands: Flow<HandObservation> = _hands.asSharedFlow()

    /**
     * Head pose (FR-N3/FR-N4 guidance, FR-T5 "hold still").
     *
     * The feeder collects `ArDevice.getInstance(session).state`, converts `devicePose` to yaw / pitch /
     * roll with [HeadAngles.fromForwardUp], and publishes the latest pose; `null` before the session
     * runs and after [stop]. It logs one tracking sample as device evidence.
     */
    override val headPose: StateFlow<RawHeadPose?> = _headPose.asStateFlow()

    /** The runtime session once [start] succeeds. Read by [SpatialAudioPinger] through [xrSession]. */
    private var session: Session? = null
    internal val xrSession: Session? get() = session

    /** Native anchors by id; populated by [createAnchor]. */
    private val nativeAnchors = HashMap<AnchorId, Anchor>()
    private val anchorPoses = HashMap<AnchorId, MutableStateFlow<AnchorPoseSample?>>()
    private var nextAnchorSeq = 0
    private val lifecycle = Mutex()
    private var loggedFirstDepthSample = false
    private var loggedFirstHeadPose = false

    /** Latest depth map, kept for [sampleDepthAlongBearing]; replaced on every frame, never queued. */
    @Volatile
    private var latestDepth: DepthFrame? = null

    /** One pose collector per native anchor, cancelled by [detachAnchor]. */
    private val anchorJobs = HashMap<AnchorId, Job>()

    /**
     * The Session Compose for XR already created for the activity (`LocalSession.current`). The
     * runtime allows one Session per activity, so [start] reuses this one instead of calling
     * `Session.create` again ("Session already exists for context").
     */
    private val composeSession = MutableStateFlow<Session?>(null)

    /** Called by the spatial UI with `LocalSession.current`; `null` clears it. */
    fun offerComposeSession(xrSession: Session?) {
        composeSession.value = xrSession
    }

    /** Parent of the four feeders of the current run; cancelled by [stop] so a later [start] relaunches them. */
    private var feeders: Job? = null

    /**
     * The Activity [start] was last given, for [ensureStarted]'s retry. Weak so a destroyed Activity is
     * never kept alive; cleared by [stop].
     */
    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    /**
     * Creates and configures the session (SRS R3 task 1, Sat 12:00; plan.md F1 task 1.5; A-5). Called by
     * `MainActivity` with itself after permissions and on every `onStart`, so it is also the restart after
     * [stop]. Idempotent while RUNNING / STARTING ([SessionStartPolicy]). Never throws: on any failure
     * [state] becomes [SessionState.UNAVAILABLE] and the NoOp-equivalent behavior continues (R1 speaks
     * `Cues.navigationUnavailable`, an R4 string).
     */
    suspend fun start(activity: Activity) {
        lifecycle.withLock {
            activityRef = WeakReference(activity)
            open(activity, explicit = true)
        }
    }

    /** Used by [XrNavigationEngine.start] and [XrAnchorStore.placeFromBearing]: retries an UNAVAILABLE session. */
    override suspend fun ensureStarted() {
        lifecycle.withLock {
            val activity = activityRef?.get()
            if (activity == null) {
                if (_state.value != SessionState.STOPPED) {
                    Log.w(TAG, "XrPerceptionSession.ensureStarted: start(activity) not called yet (MainActivity, D7)")
                }
                return
            }
            open(activity, explicit = false)
        }
    }

    /**
     * The device step of [start] / [ensureStarted]; runs under [lifecycle].
     *
     * Real implementation (docs/stack.md → R3; every line VERIFIED unless marked):
     * ```
     * // D7: named arg, off main, never applicationContext
     * val result = withContext(Dispatchers.IO) { Session.create(context = activity) }
     * val s = when (result) {
     *     is SessionCreateSuccess -> result.session
     *     // UnsupportedDevice / ApkRequired / TimedOut / UnknownError
     *     else -> { _state.value = SessionState.UNAVAILABLE; return }
     * }
     * val cfg = Config.Builder(s.config)
     *     .setPlaneTracking(PlaneTrackingMode.HORIZONTAL_AND_VERTICAL)   // SCENE_UNDERSTANDING_COARSE
     *     .setDepthEstimation(DepthEstimationMode.SMOOTH_ONLY)           // SCENE_UNDERSTANDING_FINE
     *     .setHandTracking(HandTrackingMode.BOTH)                        // HAND_TRACKING
     *     .setDeviceTracking(DeviceTrackingMode.SPATIAL)                 // HEAD_TRACKING
     *     .setAnchorPersistence(AnchorPersistenceMode.LOCAL)
     *     .build()
     * try {
     *     when (s.configure(cfg)) { is SessionConfigureSuccess -> Unit; else -> /* Q5: drop modes one at a time */ }
     * } catch (e: SecurityException) { /* a permission is missing: UNAVAILABLE */ }
     *   catch (e: UnsupportedOperationException) { /* a mode is unsupported: XrDevice.is…Supported() says which */ }
     * session = s; _state.value = SessionState.RUNNING
     * // A-5 / A-6 probes, after SessionConfigureSuccess (import androidx.xr.scenecore.scene for `s.scene`):
     * _spatialAudioSupported.value = s.scene.spatialCapabilities.contains(SpatialCapability.SPATIAL_AUDIO)
     * _depthSupported.value =
     *     XrDevice.getCurrentDevice(app).isDepthEstimationModeSupported(DepthEstimationMode.SMOOTH_ONLY)
     * Log.i(TAG, "probe depthSmooth=${_depthSupported.value} spatialAudio=${_spatialAudioSupported.value} (A-5, A-6)")
     * feeders = scope.launch {
     *     launch { depth feeder }; launch { plane feeder }; launch { hand feeder }; launch { head feeder }
     * }
     * ```
     * [spatialAudioSupported] is the only place the A-6 capability is read; [SpatialAudioPinger.attach] copies it
     * into `isSpatial` (all three calls VERIFIED by javap: `Scene.getSpatialCapabilities(): Set<SpatialCapability>`,
     * `SessionExt.getScene(Session)`, `XrDevice.isDepthEstimationModeSupported(DepthEstimationMode)`).
     */
    private suspend fun open(activity: Activity, explicit: Boolean) {
        if (!SessionStartPolicy.shouldOpen(_state.value, explicit)) return
        _state.value = SessionState.STARTING
        try {
            // Prefer the Session Compose for XR owns; only create one if it never appears.
            val shared = composeSession.value
                ?: withTimeoutOrNull(COMPOSE_SESSION_WAIT_MS) { composeSession.filterNotNull().first() }
            val xrSession = if (shared != null) {
                shared
            } else {
                val creationResult = try {
                    withContext(Dispatchers.IO) { Session.create(context = activity) }
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "XR session already exists for this activity and was not shared", e)
                    _state.value = SessionState.UNAVAILABLE
                    return
                }
                when (creationResult) {
                    is SessionCreateSuccess -> creationResult.session
                    else -> {
                        Log.e(TAG, "XR session creation failed: ${creationResult::class.java.simpleName}")
                        _state.value = SessionState.UNAVAILABLE
                        return
                    }
                }
            }

            val config = Config.Builder(xrSession.config)
                .setPlaneTracking(PlaneTrackingMode.HORIZONTAL_AND_VERTICAL)
                .setDepthEstimation(DepthEstimationMode.SMOOTH_ONLY)
                .setHandTracking(HandTrackingMode.BOTH)
                .setDeviceTracking(DeviceTrackingMode.SPATIAL)
                .setAnchorPersistence(AnchorPersistenceMode.LOCAL)
                .build()

            when (val configureResult = xrSession.configure(config)) {
                is SessionConfigureSuccess -> {
                    session = xrSession
                    // A-5 / A-6 probes.
                    _spatialAudioSupported.value = xrSession.scene.spatialCapabilities.contains(SpatialCapability.SPATIAL_AUDIO)
                    _depthSupported.value =
                        XrDevice.getCurrentDevice(app).isDepthEstimationModeSupported(DepthEstimationMode.SMOOTH_ONLY)
                    Log.i(TAG, "probe depthSmooth=${_depthSupported.value} spatialAudio=${_spatialAudioSupported.value} (A-5, A-6)")
                    feeders = scope.launch {
                        launchDepthFeed(xrSession)
                        launchHeadPoseFeed(xrSession)
                    }
                    _state.value = SessionState.RUNNING
                    Log.i(TAG, "XR session configured successfully; state=${_state.value}")
                }
                else -> {
                    Log.e(TAG, "XR session configuration failed: ${configureResult::class.java.simpleName}")
                    _state.value = SessionState.UNAVAILABLE
                }
            }
        } catch (e: CancellationException) {
            _state.value = SessionState.NOT_STARTED
            throw e
        } catch (e: SecurityException) {
            Log.e(TAG, "XR session setup failed because a runtime permission is missing", e)
            _state.value = SessionState.UNAVAILABLE
        } catch (e: UnsupportedOperationException) {
            Log.e(TAG, "XR session setup requested an unsupported tracking feature", e)
            _state.value = SessionState.UNAVAILABLE
        } finally {
            if (_state.value == SessionState.STARTING) {
                _state.value = SessionState.UNAVAILABLE
            }
        }
    }

    private fun CoroutineScope.launchDepthFeed(xrSession: Session): Job = launch {
        try {
            val stereo = XrDevice.getCurrentDevice(app).isRenderingModeSupported(RenderingMode.STEREO)
            val depth = if (stereo) Depth.left(xrSession) else Depth.mono(xrSession)
            val view = if (stereo) RenderViewpoint.left(xrSession) else RenderViewpoint.mono(xrSession)

            depth.state.collect { state ->
                val map = state.smoothDepthMap ?: return@collect
                val fov = view.state.value.fieldOfView
                val fovDeg = FovDeg(
                    horizontal = Math.toDegrees((fov.angleRight - fov.angleLeft).toDouble()).toFloat(),
                    vertical = Math.toDegrees((fov.angleUp - fov.angleDown).toDouble()).toFloat(),
                )
                if (!fovDeg.horizontal.isFinite() || !fovDeg.vertical.isFinite() ||
                    fovDeg.horizontal <= 0f || fovDeg.horizontal >= 180f ||
                    fovDeg.vertical <= 0f || fovDeg.vertical >= 180f
                ) {
                    Log.w(TAG, "Skipping depth sample with invalid field of view: $fovDeg")
                    return@collect
                }

                val expectedSize = state.width * state.height
                val depthValues = FloatArray(expectedSize)
                map.duplicate().apply { rewind() }.get(depthValues)
                val confidence = state.smoothConfidenceMap?.let { source ->
                    ByteArray(expectedSize).also { source.duplicate().apply { rewind() }.get(it) }
                }
                val frame = DepthFrame(
                    width = state.width,
                    height = state.height,
                    depthM = depthValues,
                    confidence = confidence,
                    fov = fovDeg,
                    timestampNanos = SystemClock.elapsedRealtimeNanos(),
                )
                latestDepth = frame
                _depthFrames.tryEmit(frame)

                if (!loggedFirstDepthSample) {
                    DepthCone.minTrustedDepth(frame)?.let { nearest ->
                        Log.i(TAG, "depth ${state.width}x${state.height}, min trusted depth $nearest m (FR-N1)")
                        loggedFirstDepthSample = true
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "XR depth feed stopped; proximity warnings are unavailable", e)
        }
    }

    private fun CoroutineScope.launchHeadPoseFeed(xrSession: Session): Job = launch {
        try {
            ArDevice.getInstance(xrSession).state.collect { state ->
                val pose = state.devicePose
                val (yaw, pitch, roll) = HeadAngles.fromForwardUp(pose.forward.toVec3(), pose.up.toVec3())
                val head = RawHeadPose(
                    position = pose.translation.toVec3(),
                    yawDeg = yaw,
                    pitchDeg = pitch,
                    rollDeg = roll,
                    tracking = state.trackingState.toTracking(),
                    timestampNanos = SystemClock.elapsedRealtimeNanos(),
                )
                _headPose.value = head

                if (!loggedFirstHeadPose && head.tracking == Tracking.TRACKING) {
                    Log.i(TAG, "head pose tracking: yaw %.1f pitch %.1f deg (FR-N3)".format(yaw, pitch))
                    loggedFirstHeadPose = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "XR head pose feed stopped; guidance and plane cues are unavailable", e)
        }
    }

    private fun Vector3.toVec3() = Vec3(x, y, z)

    private fun TrackingState.toTracking(): Tracking = when (this) {
        TrackingState.TRACKING -> Tracking.TRACKING
        TrackingState.PAUSED -> Tracking.PAUSED
        else -> Tracking.STOPPED
    }

    // TEMP-DEBUG (test only, remove before the demo): place an anchor straight ahead and log its distance every second.
    private var debugAnchorJob: Job? = null
    val debugLive = MutableStateFlow<String?>(null)
    var debugLastAnchor: AnchorId? = null
    val debugPing = MutableStateFlow("ping: not attached")

    suspend fun debugPlaceAnchorAhead(): String {
        val head = _headPose.value ?: return "no head pose yet"
        val point = sampleDepthAlongBearing(head.yawDeg, head.pitchDeg) ?: return "no trusted depth ahead"
        val id = createAnchor(point) ?: return "anchor refused"
        debugLastAnchor = id
        debugShowMarker(id)
        val start = sqrt(
            (point.x - head.position.x).let { it * it } + (point.y - head.position.y).let { it * it } +
                (point.z - head.position.z).let { it * it },
        )
        debugAnchorJob?.cancel()
        debugAnchorJob = scope.launch {
            while (true) {
                val a = anchorPoses[id]?.value
                val h = _headPose.value
                if (a != null && h != null) {
                    val dx = a.position.x - h.position.x
                    val dy = a.position.y - h.position.y
                    val dz = a.position.z - h.position.z
                    val d = sqrt(dx * dx + dy * dy + dz * dz)
                    debugLive.value = "marker %.2f m away (${a.tracking})".format(d)
                    Log.i(TAG, "DEBUG anchor ${id.value}: %.2f m away, tracking=${a.tracking}".format(d))
                }
                kotlinx.coroutines.delay(1_000)
            }
        }
        Log.i(TAG, "DEBUG anchor ${id.value} placed %.2f m ahead".format(start))
        return "marker placed %.2f m ahead".format(start)
    }

    // TEMP-DEBUG (test only, remove before the demo): a visible red label at the anchor.
    private var debugMarker: Pair<AnchorSpace, PanelEntity>? = null

    private fun debugShowMarker(id: AnchorId) {
        val xrSession = session ?: return
        val anchor = nativeAnchors[id] ?: return
        debugMarker?.let { (space, panel) -> panel.dispose(); space.dispose() }
        debugMarker = try {
            val label = android.widget.TextView(app).apply {
                text = "MARKER"
                textSize = 48f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.RED)
                gravity = android.view.Gravity.CENTER
            }
            val space = AnchorSpace.create(xrSession, anchor)
            val panel = PanelEntity.create(xrSession, label, FloatSize2d(0.3f, 0.15f), "debugMarker", Pose(), space)
            space to panel
        } catch (e: RuntimeException) {
            Log.e(TAG, "DEBUG marker label failed", e)
            null
        }
        Log.i(TAG, "DEBUG marker label shown=${debugMarker != null}")
    }

    /**
     * Ends the current run: cancels the feeders, detaches every anchor (their `track()` flows complete),
     * forgets the session and the Activity, and clears the pose and planes so nothing stale is trusted
     * after a restart (RK-5). The runtime session has no public `destroy` (it follows the Activity
     * lifecycle, `Session.create(..., lifecycleOwner = activity)`). Reversible: [start] opens a new run.
     * Never throws.
     */
    suspend fun stop() {
        lifecycle.withLock {
            feeders?.cancel()
            feeders = null
            detachAllAnchors()
            session = null
            activityRef = null
            _headPose.value = null
            _planes.value = emptyList()
            _state.value = SessionState.STOPPED
        }
    }

    /**
     * FR-N3 depth sample along a bearing, from the latest depth map: `DepthCone.pixelForBearing` →
     * `DepthCone.medianTrustedDepthAround(radius = 3)` → `head.position + dir * range` (helpers in
     * ProximityMonitor.kt, pure, tested). Roll is ignored. Not yet tried: `hitTest(session, Ray(...))`
     * against tracked planes, the fallback if the depth map proves too noisy at door range.
     */
    override suspend fun sampleDepthAlongBearing(worldYawDeg: Float, pitchDeg: Float): Vec3? {
        // Strategy 2 (depth map). `null` = "no usable depth", a contract value; R4 speaks Cues.doorNotFound.
        val head = _headPose.value?.takeIf { it.tracking == Tracking.TRACKING } ?: return null
        val frame = latestDepth ?: return null
        val relYaw = HeadAngles.wrapDeg(worldYawDeg - head.yawDeg)
        val relPitch = pitchDeg - head.pitchDeg
        val (x, y) = DepthCone.pixelForBearing(relYaw, relPitch, frame.width, frame.height, frame.fov) ?: return null
        val depth = DepthCone.medianTrustedDepthAround(frame, x, y, radius = 3) ?: return null
        // The map holds distance along the optical axis (UNVERIFIED: could be ray length); convert to ray length.
        val u = tan(Math.toRadians(relYaw.toDouble())).toFloat()
        val v = tan(Math.toRadians(relPitch.toDouble())).toFloat()
        val range = depth * sqrt(1f + u * u + v * v)
        val yawR = Math.toRadians(worldYawDeg.toDouble())
        val pitchR = Math.toRadians(pitchDeg.toDouble())
        val dir = Vec3(
            (sin(yawR) * cos(pitchR)).toFloat(),
            sin(pitchR).toFloat(),
            (-cos(yawR) * cos(pitchR)).toFloat(),
        )
        return Vec3(head.position.x + dir.x * range, head.position.y + dir.y * range, head.position.z + dir.z * range)
    }

    /**
     * `Anchor.create(session, Pose(position))` (stack.md R3 "Anchors"); on success the anchor's pose
     * stream feeds [anchorPose]. `null` when there is no session or the runtime refuses
     * (`AnchorCreateResourcesExhausted` / `AnchorCreateTrackingUnavailable`).
     */
    override suspend fun createAnchor(position: Vec3): AnchorId? {
        val xrSession = session ?: return null
        val result = try {
            Anchor.create(xrSession, Pose(Vector3(position.x, position.y, position.z)))
        } catch (e: IllegalStateException) {
            Log.e(TAG, "anchor creation failed", e)
            return null
        }
        val anchor = (result as? AnchorCreateSuccess)?.anchor ?: run {
            Log.w(TAG, "anchor not created: ${result::class.java.simpleName}")
            return null
        }
        val id = AnchorId("door-${nextAnchorSeq++}")
        val poses = MutableStateFlow<AnchorPoseSample?>(null)
        nativeAnchors[id] = anchor
        anchorPoses[id] = poses
        anchorJobs[id] = scope.launch {
            anchor.state.collect { st ->
                poses.value = AnchorPoseSample(id, st.pose.translation.toVec3(), st.trackingState.toTracking(), SystemClock.elapsedRealtimeNanos())
            }
        }
        Log.i(TAG, "anchor ${id.value} created (FR-N3)")
        return id
    }

    override fun anchorPose(id: AnchorId): Flow<AnchorPoseSample> {
        val flow = anchorPoses[id]
            ?: return flowOf(AnchorPoseSample(id, Vec3.ZERO, Tracking.STOPPED, 0L)) // unknown id → one STOPPED sample
        return flow.filterNotNull()
    }

    /** `anchor.detach()` (VERIFIED) and a final STOPPED sample so `track()` flows complete (FR-N4 TargetLost). */
    override suspend fun detachAnchor(id: AnchorId) {
        val poses = anchorPoses.remove(id)
        anchorJobs.remove(id)?.cancel()
        nativeAnchors.remove(id)?.let { anchor ->
            try {
                anchor.detach()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "anchor ${id.value} already gone: ${e.message}")
            }
        }
        if (poses != null) {
            poses.value = AnchorPoseSample(id, poses.value?.position ?: Vec3.ZERO, Tracking.STOPPED, 0L)
        }
    }

    override suspend fun detachAllAnchors() {
        for (id in anchorPoses.keys.toList()) detachAnchor(id)
    }

    // ── SceneCore spatial audio hook for SpatialAudioPinger (A-6; stack.md R3 "Spatial audio", "Entities") ──

    /** Anchor spaces parenting the ping entities, by anchor; disposed with the entity. */
    private val anchorSpaces = HashMap<AnchorId, AnchorSpace>()

    /** For [SpatialAudioPinger]'s `SoundPool.load(assets.openFd(...))`; package-internal (ADR-0005). */
    internal val assets: AssetManager get() = app.assets

    /**
     * An entity parented under `AnchorSpace.create(session, anchor)` so a sound played from it comes
     * from the anchor. `null` without a session or a known native anchor.
     */
    internal fun createAnchorEntity(id: AnchorId, name: String): AnchorEntity? {
        val xrSession = session ?: return null
        val anchor = nativeAnchors[id] ?: return null
        return try {
            val space = AnchorSpace.create(xrSession, anchor)
            val entity = Entity.create(xrSession, name)
            space.addChild(entity)
            anchorSpaces[id] = space
            AnchorEntity(id, entity)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "anchor entity for ${id.value} not created", e)
            null
        }
    }

    internal fun disposeAnchorEntity(entity: AnchorEntity) {
        (entity.native as? Entity)?.dispose()
        anchorSpaces.remove(entity.anchorId)?.dispose()
    }

    /**
     * `SpatialSoundPool.play` from the entity (all four trailing arguments passed: their defaults are
     * not recoverable from bytecode). Returns the stream id, or -1 when spatial audio is unavailable.
     */
    internal fun playPointSource(entity: AnchorEntity, soundPool: SoundPool, soundId: Int): Int {
        val xrSession = session ?: return -1
        val native = entity.native as? Entity ?: return -1
        if (!xrSession.scene.spatialCapabilities.contains(SpatialCapability.SPATIAL_AUDIO)) return -1 // A-6 fallback
        return SpatialSoundPool.play(
            session = xrSession,
            soundPool = soundPool,
            soundID = soundId,
            params = PointSourceParams(),
            entity = native,
            volume = 1f,
            priority = 0,
            loop = 0,
            rate = 1f,
        )
    }

    companion object {
        internal const val TAG = "vysion"

        /** How long [start] waits for Compose for XR to publish its Session before creating one. */
        private const val COMPOSE_SESSION_WAIT_MS = 5_000L
    }
}
