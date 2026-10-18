# R3 Spatial Perception and Navigation

## Purpose

R3 turns the headset's own perception stack into the three things Navigation speaks: a proximity warning from the depth map, the door's bearing and distance from a native anchor and the head pose, and (P1) wall / floor cues from plane labels. SRS §8 says R3 **Owns** "ARCore for Jetpack XR session, depth, planes, anchors, hands, `NavigationEngine`, spatial audio cues" and **Hands off** "`NavigationCues` flow (proximity, bearing, distance) and `AnchorStore`", due **Sat 19:00**. Nothing in this package builds a sentence (R4 words every cue in `voice/Cues.kt`, INV-4) and nothing touches the camera image (R2 runs the detector; R3 only loops it).

## Files you own

| Path | What it is | Status |
|---|---|---|
| `app/src/main/kotlin/com/vysion/xr/perception/XrPerceptionSession.kt` | Value types (`Vec3`, `DepthFrame`, `PlaneObservation`, `HandObservation`, `RawHeadPose`, `AnchorPoseSample`), `HeadAngles` (pure), `SessionStartPolicy` (pure: when `start(activity)` / `ensureStarted()` open a session), the internal `PerceptionSource` port, and `XrPerceptionSession(Application, CoroutineScope)`: creates/configures the ARCore for Jetpack XR `Session` from the Activity `MainActivity` passes to `start(activity)`, stops reversibly, feeds depth / planes / hands / head-pose flows, owns native anchors, exposes the SceneCore entity hook for the pinger | stub (device calls) + pure (`HeadAngles`, `SessionStartPolicy`) |
| `.../perception/XrHeadPoseSource.kt` | `AngularSpeed` + `HeadPoseTracker` (pure, FR-T5) and `XrHeadPoseSource(XrPerceptionSession)` implementing `HeadPoseSource` | pure + real mapping |
| `.../perception/NoOpHeadPoseSource.kt` | `HeadPoseSource` for the S25 Ultra / emulator / `-PuseFakes=true`: `pose` always `null` | fake (NoOp, fully working) |
| `.../perception/ProximityMonitor.kt` | `DepthCone` (60° cone pixel math, trusted-minimum, bearing → pixel, median window) and `ProximityMonitor` (1.0 m threshold, 2 s debounce, stale-frame guard) | pure, tested |
| `.../perception/PlaneSemantics.kt` | `PlaneGeometry` (ray/plane, point-in-polygon, ray/polygon exit, wall distance, floor edge) and `PlaneSemantics` (`WallAhead` / `FloorEnds` within 1.5 m) | pure, tested |
| `.../perception/XrAnchorStore.kt` | `BearingAngles` (LEFT −30°, CENTER 0°, RIGHT +30°), `RelativeGeometry` (anchor + head → `RelativeTarget`), `XrAnchorStore(XrPerceptionSession, HeadPoseSource)` implementing `AnchorStore` | pure + real logic; device calls behind the session stubs |
| `.../perception/NoOpAnchorStore.kt` | `AnchorStore` for non-XR devices: `placeFromBearing` → `null`, `track` completes at once | fake (NoOp, fully working) |
| `.../perception/XrNavigationEngine.kt` | `Clocks`, `RepeatGate`, and `XrNavigationEngine(session, frames, engine, anchors, headPose, scope)` implementing `NavigationEngine`: one Job, four loops (proximity 10 Hz, planes, detector, guidance 3 s), pause/resume gating, `cues` SharedFlow(16, DROP_OLDEST) | real loop code; device calls behind `PerceptionSource` / `AnchorPinger` |
| `.../perception/NoOpNavigationEngine.kt` | `NavigationEngine` for non-XR devices: `isRunning` false, empty flows, no-op calls | fake (NoOp, fully working) |
| `.../perception/SpatialAudioPinger.kt` | internal `AnchorPinger` port (`isSpatial`, `attach`, `ping`, `detach`) and `SpatialAudioPinger(XrPerceptionSession)`: SceneCore point-source ping at the anchor entity every 3 s while guiding; `isSpatial` goes `false` whenever a ping cannot play (A-6) | stub |
| `app/src/test/kotlin/com/vysion/xr/perception/PerceptionFakes.kt` | `FakePerceptionSource`, `FakeFrameSource`, `FakeInferenceEngine`, `FakeAnchorStore`, `FakePinger` (scriptable `playsSpatially`), `TestBitmaps`, `TestInstances`, `depthFrame()` / `head()` builders | fake (test only) |
| `.../perception/ProximityMonitorTest.kt`, `PlaneSemanticsTest.kt`, `RelativeGeometryTest.kt`, `AngularSpeedTest.kt`, `XrAnchorStoreTest.kt`, `XrNavigationEngineTest.kt`, `XrPerceptionSessionTest.kt` | JUnit 4 specs for every pure function, the engine's loop structure (`kotlinx-coroutines-test`) and the session's start/stop policy | tests |
| `docs/modules/r3-spatial-perception.md` | this page | doc |

## Contracts you provide / consume

| Direction | Kotlin signature (`core/Contracts.kt`) | Other side |
|---|---|---|
| provide | `interface NavigationEngine { val cues: Flow<NavigationCue>; val detections: Flow<List<Detection>>; val isRunning: StateFlow<Boolean>; suspend fun start(); suspend fun pause(); suspend fun resume(); suspend fun stop(); suspend fun guideTo(anchor: AnchorId); suspend fun clearTarget() }` → `perception.XrNavigationEngine` / `NoOpNavigationEngine` | R1 `ModeController` (calls `pause` before and `resume` after every one-shot command, ADR-0004; turns each cue into words through `ModePhrasebook.navigationCue(cue)` and `Speaker.say`, and draws the door band through `DoorHighlight.boxFor(clock, inView)`), R1 `OverlayRenderer` (`detections`), R4 (`Cues.kt` wording of every `NavigationCue`) |
| provide | `sealed interface NavigationCue { Proximity(distanceM) · DoorBearing(clock, distanceM, inView, pinged = true) · WallAhead(distanceM) · FloorEnds(distanceM) · Arrived · TargetLost }` | R4 words them; R1 routes `Arrived` to the ARRIVAL prompt (FR-N6) and plays `Earcon.PING` through the `Speaker` when `DoorBearing.pinged` is false (A-6 fallback) |
| provide | `interface AnchorStore { suspend fun placeFromBearing(bearing: HorizontalBearing): AnchorId?; fun track(id: AnchorId): Flow<RelativeTarget>; suspend fun remove(id: AnchorId); suspend fun clear() }` → `perception.XrAnchorStore` / `NoOpAnchorStore` | R1 `ModeController` (after the RK-6 door vote: `placeFromBearing` then `navigation.guideTo(id)`), R3 `XrNavigationEngine` (`track`) |
| provide | `data class RelativeTarget(bearingDeg: Float, clock: Int, distanceM: Float, inView: Boolean)` | R1 (door highlight when `inView`) |
| provide | `interface HeadPoseSource { val pose: StateFlow<HeadPose?> }` with `data class HeadPose(yawDeg, pitchDeg, rollDeg, angularSpeedDegPerS, timestampNanos)` → `perception.XrHeadPoseSource` / `NoOpHeadPoseSource` | R1 (FR-T5 "hold still" when `angularSpeedDegPerS` is high at capture; `null` = no pose, skip the check) |
| consume | `interface FrameSource { val analysisFrames: Flow<Bitmap>; … }` | R1 `platform.CameraFrameSource` / `FakeFrameSource` (bitmaps owned by R1's source, a fresh one per frame; R3 never recycles or logs them, INV-3/S-53) |
| consume | `interface InferenceEngine { suspend fun detect(bitmap: Bitmap): List<Detection>; … }` | R2 `inference.ExecuTorchInferenceEngine` / `FakeInferenceEngine` (one `Mutex`, INV-1; `InferenceException` → detector off, Navigation continues on depth) |
| consume | `object ClockFace { fun fromRelativeYawDeg(yawDeg: Float): Int }`, `object Distances { fun roundToHalfMeter(m): Float; PROXIMITY_THRESHOLD_M = 1.0; ARRIVAL_RADIUS_M = 1.0; PLANE_CUE_RANGE_M = 1.5 }` (`core/Units.kt`) | R5 (owner of `core`) |
| constructors (fixed by `AppGraph.kt`) | `XrPerceptionSession(Application, CoroutineScope)` · `XrHeadPoseSource(XrPerceptionSession)` / `NoOpHeadPoseSource()` · `XrAnchorStore(XrPerceptionSession, HeadPoseSource)` / `NoOpAnchorStore()` · `XrNavigationEngine(XrPerceptionSession, FrameSource, InferenceEngine, AnchorStore, HeadPoseSource, CoroutineScope)` / `NoOpNavigationEngine()` | R1 (`AppGraph`): the XR set is wired only when `DeviceProfile.isXr && !BuildConfig.USE_FAKES` |
| lifecycle (fixed by `AppGraph.kt` / `MainActivity.kt`) | `AppGraph.perception: XrPerceptionSession?` (null off the headset) · `MainActivity.startGraph()` (after permissions on every `onStart`, and again from `onPermissionResult`) runs `lifecycleScope.launch { graph.startPerception(this) }`, which joins the previous `stop()`'s perception teardown and then calls `perception.start(activity)` (R1 open issue 8) · `AppGraph.stop()` (every `onStop`) calls `perception.stop()`, reversible · only `AppGraph.release()` cancels the graph scope | R1 |

Inside the package, `PerceptionSource` (device port) and `AnchorPinger` are `internal` interfaces so the engine, store and head-pose source can be unit-tested against `FakePerceptionSource` / `FakePinger`; they never cross the package (ADR-0005).

### Bearing and clock conventions (every R3 file, `core.ClockFace`, R4's phrasing)

- World frame is OpenXR / Jetpack XR: **+X right, +Y up, −Z forward**, meters (`Pose.forward = rotation * (0, 0, −1)`).
- `RawHeadPose.yawDeg`: 0 = facing world −Z, **positive = turned to the right** (clockwise seen from above), wrapped to (−180, 180]. `pitchDeg` positive = looking up; `rollDeg` positive = right ear down. Built from `Pose.forward` / `Pose.up` by `HeadAngles.fromForwardUp` (tested).
- World bearing of a point from the head: `atan2(dx, −dz)`. Relative bearing = `wrap(worldBearing − headYaw)`, 0 = ahead, positive = right (matches `RelativeTarget.bearingDeg`).
- Clock face (`ClockFace.fromRelativeYawDeg`, 30° per hour): **12 = ahead, 3 = right, 6 = behind, 9 = left**; the SRS example "door, 2 o'clock, 3 meters" is a relative bearing of +60° at 3.0 m.
- Distance spoken for the door is horizontal (XZ, the walking distance) rounded to 0.5 m (`Distances.roundToHalfMeter`); `inView` = |relative bearing| ≤ 45° (R1 draws the door highlight only then).
- VLM answer → bearing offset (`BearingAngles`): LEFT = −30°, CENTER = 0°, RIGHT = +30° added to a head yaw. **Which yaw:** the intent is the yaw at `captureStill()` time, but `placeFromBearing(bearing)` has no time parameter, so `XrAnchorStore` uses the yaw at call time, which `ModeController.findDoor` reaches only after both VLM answers (up to 2 × 15 s). A user who turns meanwhile gets an anchor in the wrong direction: Contract issue 6 below. **Which angle:** the thirds are of the center-cropped still, so the centers are `±atan((2/3)·tan(hfovCrop/2))` (≈ `±hfovCrop/3`); ±30° is the placeholder until R1 logs `vysion: still hfov=<deg> deg (A-1)` (stack.md Q3 correction; R1 camera P0 item), then set `LEFT_DEG` / `RIGHT_DEG` from it and record the value in `docs/assumptions.md` A-1.
- Head angular speed (FR-T5): `sqrt(Δyaw² + Δpitch²) / Δt`, Δyaw wrapped the short way, monotonic nanos; `0` when Δt ≤ 0.

### Cone math (FR-N1, `DepthCone`)

Pinhole model of the depth image of size W×H with measured FOV (`RenderViewpoint.…fieldOfView`): focal lengths `fx = (W/2) / tan(hfov/2)`, `fy = (H/2) / tan(vfov/2)` pixels; optical center `cx = (W−1)/2`, `cy = (H−1)/2`. Pixel (x, y) looks along `((x−cx)/fx, (y−cy)/fy, 1)`, so its angle off the axis is `θ = atan(sqrt(u² + v²))` with `u = (x−cx)/fx`, `v = (y−cy)/fy`. **A pixel is inside the 60° cone iff `u² + v² ≤ tan²(30°) ≈ 0.333`**, i.e. the disc of radius `0.577·fx` (horizontally) / `0.577·fy` (vertically) around the center, clamped to the image when the FOV is narrower than 60°. `minTrustedDepth` takes the smallest depth in that disc among pixels with depth ≥ 0.1 m (0 = no data) and confidence ≥ 128/255; `ProximityMonitor` emits `Proximity(min)` when `min < 1.0 m`, at most once per 2 s, and ignores maps older than 500 ms (a map buffered during a pause). Bearing → pixel for FR-N3: `x = cx + fx·tan(relYaw)`, `y = cy − fy·tan(relPitch)`; the depth there is the median of a 7×7 trusted window.

## Assumptions to verify first

| # | Owner | Assumption | By | Result | Evidence | Checked by |
|---|---|---|---|---|---|---|
| A-5 | R3 | ARCore for Jetpack XR `1.0.0-alpha15` (SRS wording; the build pins `1.0.0-rc02`) depth and plane tracking return data on the headset with the `SCENE_UNDERSTANDING_*` permissions granted | Sat 12:00 | | | |
| A-6 | R3 | SceneCore spatial audio point source is available and audible through the headset speakers | Sat 13:00 | | | |

If it fails (verbatim from `docs/assumptions.md`):

| Failed | Then |
|---|---|
| A-5 | RK-5 fallback: plane hit tests and detector box height as the distance proxy |
| A-6 | Non-spatial earcon through `Speaker.earcon(PING)`; say the bearing more often (every 2 s) |

Note the pin is `arcore = "1.0.0-rc02"` (`gradle/libs.versions.toml`, stack.md §1); the SRS's alpha15 is four releases stale, so every `alpha15` on this page is SRS wording, not the version you build against. The SRS gives two verify times for ARCore: Sat 13:00 in its External interfaces table (§6) and Sat 12:00 in §8 and §12; this repo uses **Sat 12:00** (gate 1), the earlier one. `docs/stack.md` Q5 (does `configure` succeed with all four modes?) and Q6 (does `Depth.left/mono` deliver a non-null `smoothDepthMap`?) are the two yes/no checks that answer A-5; Q11 answers A-6. Record results in `docs/assumptions.md`, not here.

## Task checklist

- [x] P0 ARCore for Jetpack XR session (`arcore:1.0.0-alpha15`) created from the Compose for XR session; depth, plane, hand, and device-pose configs enabled. Sat 12:00
    SRS wording; what the code does: the pin is `1.0.0-rc02`, and `XrPerceptionSession` creates its own session with `Session.create(context = activity)` while R1's `SpatialShell` reads Compose for XR's `LocalSession.current`. Whether those are one session or two is Q21 (Contract issue 7): log both identities on the first run. Set the A-5/A-6 probes in `open()` (`_depthSupported`, `_spatialAudioSupported`; snippet in the `open()` KDoc) and log `vysion: probe depthSmooth=… spatialAudio=… (A-5, A-6)`. Write the type bridges `v3()` / `toVector3()` / `toTracking()` / `toPlaneKind()` as private functions in the same file (class KDoc; cheat-sheet row "Math and type bridges").
    Files: `XrPerceptionSession.kt` (`start(activity)`, the four feeders) · FR: FR-N1, FR-N3, FR-N5, A-5 · Verify: `scripts/install_headset.sh --logcat | grep -E "vysion.*(SessionConfigureSuccess|depth [0-9]+x[0-9]+|plane (WALL|FLOOR))"` prints one depth line (`depth WxH, min trusted depth N m`) and one plane line; `XrPerceptionSession.state` reads `RUNNING`
- [x] P0 Proximity: sample the depth texture in the forward 60-degree cone at 10 Hz; emit `Proximity(distanceM)` when under 1.0 m with 2 s debounce. Sat 15:00
    Files: `ProximityMonitor.kt` (done, pure), `XrPerceptionSession.kt` depth feeder, `XrNavigationEngine.proximityLoop` (done) · FR: FR-N1 · Verify: `./gradlew testDebugUnitTest --tests "com.vysion.xr.perception.ProximityMonitorTest"` green; on the headset walk at a wall: `adb logcat -s vysion.latency` prints `proximity cue emitted N ms after depth copy (earcon not included; FR-N1 target <= 300 ms)` with N ≤ 300 and lines at least 2 s apart. N covers depth copy → cue emitted only; the NFR end point is the earcon start (docs/nfr.md), so the benchmark row also needs R1's earcon timing on the hazard lane (or a screen+audio recording); record both in `docs/benchmarks.md`
- [x] P0 `AnchorStore`: given a bearing (left/center/right from the VLM) and a depth sample along it, create an anchor; expose bearing and distance from the current head pose. Sat 18:00
    Depends on: the P0 session item above (`XrPerceptionSession.open()` is still `TODO(FR-N1)`) and its head-pose feeder. Until both land there is no session and no head pose, so `placeFromBearing` logs `placeFromBearing: no tracked head pose …` and returns `null` on every call, so once the camera and the VLM work "find the door" ends in `doorNotFound` every time (today it ends earlier, in `inferenceFailed`, because `CameraFrameSource.captureStill()` is still `TODO()`). FR-N3 end to end also needs R1's `captureStill()` (A-1) and R2's VLM on the headset (gate 4, A-13); see Fallbacks.
    Files: `XrAnchorStore.kt` (logic done), `XrPerceptionSession.kt` (`sampleDepthAlongBearing`, `createAnchor`, `anchorPose`) · FR: FR-N3, FR-N4 · Verify: `./gradlew testDebugUnitTest --tests "com.vysion.xr.perception.XrAnchorStoreTest" --tests "com.vysion.xr.perception.RelativeGeometryTest"` green; on the headset `adb logcat -s vysion | grep "anchor .* placed"` shows `anchor door-0 placed: bearing CENTER (0.0°), N m, clock 12 (FR-N3)` for a door straight ahead
- [x] P0 `NavigationCues` flow: proximity, door bearing in clock-face form, distance in meters rounded to 0.5. Sat 19:00
    Files: `XrNavigationEngine.kt` (done), `NoOpNavigationEngine.kt` (done) · FR: FR-N1, FR-N2, FR-N3, FR-N6 · Verify: `./gradlew testDebugUnitTest --tests "com.vysion.xr.perception.XrNavigationEngineTest"` green (start idempotent, pause/resume gating, 3 s cadence restarting on resume, `pinged` + 2 s A-6 fallback, Arrived, TargetLost); on the headset R1's collector logs `vysion: cue DoorBearing(clock=2, distanceM=3.0, inView=true, pinged=true)` every 3 s while guiding (the cue log and the door-vote log are still to add: R1's P0 `ModeController` item, r1-xr-platform.md; they block this verification and gate 7's evidence; until they land, use the overlay door band and the spoken bearing)
- [x] P0 Spatial audio: a SceneCore point source at the anchor playing a ping every 3 s while guiding. Sat 21:00
    Timing: Sat 13:00 is only the A-6 probe (one ping: audible? positioned?); this P0 is due Sat 21:00 and merges at gate 6 (Sat 22:00, `plan.md` task 6.2); FR-N3 end to end is gate 7 (F7). The A-6 capability (`_spatialAudioSupported`) is set in `XrPerceptionSession.open()` (session item above); `SpatialAudioPinger.attach` copies it. The sound loads asynchronously: register `setOnLoadCompleteListener` and treat the sound as ready only on status 0; until then `ping()` leaves `isSpatial` unchanged (`SpatialAudioPinger.attach` KDoc). `XrPerceptionSession.app` is private: add `internal val assets: AssetManager get() = app.assets` for `openFd(PING_ASSET)`. Needs R4's `app/src/main/assets/sounds/ping.ogg` (r4 page, `Speaker` P0 item, exact `ffmpeg` command there).
    Files: `SpatialAudioPinger.kt`, `XrPerceptionSession.kt` (`createAnchorEntity`, `playPointSource`) · FR: FR-N3, A-6 · Verify: `adb logcat -s vysion | grep -c "TODO(FR-N3) SpatialAudioPinger"` is 0 while guiding; a blindfolded teammate points at the ping source within ±30° on 3 of 3 tries (log the result in `docs/assumptions.md` A-6)
- [x] P1 Plane semantics: wall-ahead and floor-ends cues from plane labels within 1.5 m on the heading. Sun 09:00
    Files: `PlaneSemantics.kt` (done, pure), `XrPerceptionSession.kt` plane feeder, `XrNavigationEngine.planeLoop` (done) · FR: FR-N5 · Verify: `./gradlew testDebugUnitTest --tests "com.vysion.xr.perception.PlaneSemanticsTest"` green; on the headset walk toward a wall: `vysion: cue WallAhead(distanceM=1.5)` (R1's cue log, see above) appears once per 3 s inside 1.5 m and not outside; a FLOOR plane's logged normal is ≈ (0, 1, 0) (confirms the plane-frame assumption)
- [ ] P1 Keep guiding when the door leaves the field of view; test by turning 180 degrees and back. Sun 10:00
    Files: `XrAnchorStore.track` (done), `XrNavigationEngine.guidanceLoop` (done) · FR: FR-N4 · Verify: procedure in "How to test without the headset → The 180° test"; logcat (R1's cue log) shows `DoorBearing(clock=6, …, inView=false)` while turned away and `clock=12, inView=true` after turning back, with no `TargetLost` in between
- [ ] P2 Pointing: index-finger ray from hand tracking to a region for region-only describe. Sun 11:00
    Files: `XrPerceptionSession.kt` hand feeder (`HandObservation.indexProximal` → `indexTip` ray), a new pure `PointingRay` helper · FR: FR-D4 · Verify: unit test that a ray from proximal to tip hits a 1 m box at 2 m inside a `NormalizedBox`; headset: pointing at a cup makes R1 pass the region box to R2's prompt (needs an additive contract change, see Contract issues)

## API cheat-sheet

Copied from `docs/stack.md` §3 → R3 (sources: API files at `https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/xr/{runtime/runtime,arcore/arcore,scenecore/scenecore}/api/1.0.0-rc01.txt`; guides `https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore` (`/planes`, `/depth`, `/hands`, `/anchors`, `/device-pose`) and `https://developer.android.com/develop/xr/jetpack-xr-sdk/add-spatial-audio`). Tag per row: **VERIFIED** = stack.md XR-PS + XR-AC, re-checked on 2026-10-17 by `javap` against the cached `arcore-1.0.0-rc02` / `scenecore-1.0.0-rc02` / `runtime-1.0.0-rc02` jars (the build resolves runtime rc02; 1.1.0-alpha01 differs only in `SpatialApiVersions` and the internal `XrInstanceManager`, the classes R3 uses are byte-identical: stack.md "Corrections found at build time", D2 row); **UNVERIFIED** = nobody checked, use only behind a `// stack.md: UNVERIFIED` comment.

| Need | Exact API | Tag |
|---|---|---|
| Create | `suspend fun Session.Companion.create(context: Context, coroutineContext: CoroutineContext = EmptyCoroutineContext, lifecycleOwner: LifecycleOwner = context as LifecycleOwner): SessionCreateResult`; call with **named** `context =` inside `withContext(Dispatchers.IO)`; never `applicationContext` (D7) | VERIFIED |
| Create results | `SessionCreateSuccess(session)`, `SessionCreateUnsupportedDevice`, `SessionCreateApkRequired`, `SessionCreateTimedOut`, `SessionCreateUnknownError`; sealed nonexhaustive → keep `else ->`; a missing permission is a `SecurityException` | VERIFIED |
| Configure | `Config.Builder(session.config).setPlaneTracking(PlaneTrackingMode.HORIZONTAL_AND_VERTICAL).setDepthEstimation(DepthEstimationMode.SMOOTH_ONLY).setHandTracking(HandTrackingMode.BOTH).setDeviceTracking(DeviceTrackingMode.SPATIAL).setAnchorPersistence(AnchorPersistenceMode.LOCAL).build()`; `session.configure(cfg): SessionConfigureResult` (`SessionConfigureSuccess` …); throws `SecurityException` / `UnsupportedOperationException`; no `headTracking` field: head = `DeviceTrackingMode.SPATIAL` + `HEAD_TRACKING` | VERIFIED |
| Capability probe | `XrDevice.getCurrentDevice(context).isDepthEstimationModeSupported(mode)` / `.isHandTrackingModeSupported(mode)` / `.isRenderingModeSupported(RenderingMode.STEREO)` | VERIFIED |
| Depth | `Depth.left(session)` / `right` / `mono`; `state: StateFlow<Depth.State>` → `width`, `height`, `smoothDepthMap: FloatBuffer?` (m), `rawDepthMap`, `smoothConfidenceMap: ByteBuffer?` (0..255), `rawConfidenceMap`; unsupported map → `IllegalStateException` | VERIFIED |
| Depth FOV | `RenderViewpoint.left/right/mono(session).state.value.fieldOfView: androidx.xr.runtime.math.FieldOfView(angleLeft, angleRight, angleUp, angleDown)`; package is `runtime.math` (stack.md row says `RenderViewpoint … fieldOfView` without the class); angles assumed radians per OpenXR `XrFovf`, left/down negative | class VERIFIED, unit UNVERIFIED |
| Planes | `Plane.subscribe(session): StateFlow<Collection<Plane>>`; `plane.state.value` → `trackingState: TrackingState {TRACKING, PAUSED, STOPPED}`, `label: PlaneLabel {UNKNOWN, WALL, FLOOR, CEILING, TABLE}`, `centerPose: Pose`, `extents: FloatSize2d`, `vertices: List<Vector2>`; `plane.type: PlaneType`; `plane.createAnchor(pose): AnchorResult` | VERIFIED (which `centerPose` axis is the normal: UNVERIFIED, see `PlaneObservation`) |
| Hands | `Hand.left(session)` / `Hand.right(session)`; `state.value.handJoints: Map<HandJointType, Pose>`, `trackingState`; `Hand.getPrimaryHandSide(contentResolver): HandSide`; pinch = `Vector3.distance(thumbTip.translation, indexTip.translation) < 0.05f` after `session.scene.perceptionSpace.transformPoseTo(pose, session.scene.activitySpace)` | VERIFIED |
| Anchors | `Anchor.create(session, pose): AnchorResult` → `AnchorCreateSuccess(anchor)` / `AnchorCreateResourcesExhausted` / `AnchorCreateTrackingUnavailable`; `anchor.state.value.pose / .trackingState`; `anchor.detach()`; `suspend fun persist(): UUID`; `Anchor.load(session, uuid)` | VERIFIED |
| Hit test | top-level `hitTest(session, Ray(origin, direction)): List<HitResult>` (`import androidx.xr.arcore.hitTest`; Java facade `Interaction.hitTest`); `HitResult(distance, hitPose, trackable)`; `Ray` / `Pose` / `Vector3` in `androidx.xr.runtime.math` | VERIFIED |
| Head pose | `ArDevice.getInstance(session).state.value.devicePose: Pose` (+ `trackingState`); `Pose.translation / rotation / forward / up / right`; `session.scene.perceptionSpace.getScenePoseFromPerceptionPose(pose)` bridges to SceneCore | VERIFIED |
| Spatial audio | `SpatialSoundPool.play(session, soundPool: SoundPool, soundID: Int, params: PointSourceParams, entity: Entity, volume: Float, priority: Int, loop: Int, rate: Float): Int` (pass all four trailing args); `PointSourceParams()` no-arg; gate on `session.scene.spatialCapabilities.contains(SpatialCapability.SPATIAL_AUDIO)`; `AudioAttributes` must not be `USAGE_MEDIA` nor `CONTENT_TYPE_MUSIC/MOVIE/SPEECH` → use `USAGE_ASSISTANCE_SONIFICATION` + `CONTENT_TYPE_SONIFICATION` | VERIFIED |
| Entities | `AnchorSpace.create(session, anchor: androidx.xr.arcore.Anchor)`; `Entity.create(session, name, pose, parent)` with 1–4-arg overloads; `entity.addChild(child)`, `entity.dispose()`; `import androidx.xr.scenecore.scene` for `session.scene` (`SessionExt`) | VERIFIED |
| Math and type bridges | `androidx.xr.runtime.math`: `Vector3(x: Float, y: Float, z: Float)` with `x/y/z`; `Pose(translation: Vector3 = Vector3(), rotation: Quaternion = Quaternion())` (`Quaternion()` = identity (0, 0, 0, 1)), also `Pose(Vector3)`; `Vector2(x, y)`. `TrackingState`, `PlaneLabel` and `SpatialCapability` are final classes with static instances, **not enums**: a `when` over them needs `else`. The bridges `v3()`, `toVector3()`, `toTracking()`, `toPlaneKind()` that the `XrPerceptionSession` snippets call do not exist yet; R3 writes them as private functions in that file (exact code in its class KDoc) | VERIFIED (javap on runtime/arcore/scenecore rc02, 2026-10-17; stack.md "Corrections") |

```kotlin
// Adapted from docs/stack.md §3 → R3 (VERIFIED names; imports one per line). Not verbatim: stack.md's sample
// speaks (`speaker.say(Cues.NO_DOOR)`, `speaker.error(…)`), which R3 never does (no Speaker here, INV-4; neither
// call exists). R3 returns null and R1 speaks `ModePhrasebook.doorNotFound`. The WALL-hit filter, the explicit
// AnchorSpace parent and the load-complete listener below are stack.md's.
import androidx.xr.arcore.Anchor
import androidx.xr.arcore.AnchorCreateSuccess
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.Depth
import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.arcore.Plane
import androidx.xr.arcore.PlaneLabel
import androidx.xr.arcore.RenderViewpoint
import androidx.xr.arcore.TrackingState
import androidx.xr.arcore.hitTest
import android.media.AudioAttributes
import android.media.SoundPool
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
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Ray
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.AnchorSpace
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.PointSourceParams
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.SpatialSoundPool
import androidx.xr.scenecore.scene

// 1. Session (D7): named arg, off the main thread
val r = withContext(Dispatchers.IO) { Session.create(context = activity) }
val session = (r as? SessionCreateSuccess)?.session ?: return
val cfg = Config.Builder(session.config)
    .setPlaneTracking(PlaneTrackingMode.HORIZONTAL_AND_VERTICAL)   // SCENE_UNDERSTANDING_COARSE
    .setDepthEstimation(DepthEstimationMode.SMOOTH_ONLY)           // SCENE_UNDERSTANDING_FINE
    .setHandTracking(HandTrackingMode.BOTH)                        // HAND_TRACKING
    .setDeviceTracking(DeviceTrackingMode.SPATIAL)                 // HEAD_TRACKING
    .setAnchorPersistence(AnchorPersistenceMode.LOCAL)
    .build()
try { when (session.configure(cfg)) { is SessionConfigureSuccess -> Unit; else -> Unit /* Q5: drop modes one at a time */ } }
catch (e: SecurityException) { /* permission missing */ } catch (e: UnsupportedOperationException) { /* mode unsupported */ }

// 2. Depth → DepthFrame (proximity cone, meters)
val stereo = XrDevice.getCurrentDevice(app).isRenderingModeSupported(RenderingMode.STEREO)
val depth = if (stereo) Depth.left(session) else Depth.mono(session)
val view = if (stereo) RenderViewpoint.left(session) else RenderViewpoint.mono(session)
depth.state.collect { d -> val buf = d.smoothDepthMap ?: return@collect; val fov = view.state.value.fieldOfView /* … */ }

// 3. Planes → PlaneObservation ("wall ahead" / "floor ends")
Plane.subscribe(session).collect { planes -> planes.forEach { p -> val s = p.state.value
    if (s.trackingState == TrackingState.TRACKING && s.label == PlaneLabel.WALL) { s.centerPose; s.extents; s.vertices } } }

// 4. Head pose + hit test + anchor (door)
val head = ArDevice.getInstance(session).state.value.devicePose
val hits = hitTest(session, Ray(origin = head.translation, direction = head.forward))   // XrAnchorStore: forwardForYaw(head yaw + offset)
val hit = hits.firstOrNull { (it.trackable as? Plane)?.state?.value?.label == PlaneLabel.WALL } ?: hits.firstOrNull()
when (val a = Anchor.create(session, hit?.hitPose ?: return)) {
    is AnchorCreateSuccess -> a.anchor   // later: a.anchor.state.value.pose; a.anchor.detach()
    else -> null                         // → AnchorStore.placeFromBearing returns null → Cues.doorNotFound (R4)
}

// 5. Spatial ping at the anchor (SceneCore)
val anchorSpace = AnchorSpace.create(session, anchor).apply { parent = session.scene.activitySpace }
val pingEntity: Entity = Entity.create(session, "doorPing")
anchorSpace.addChild(pingEntity)
val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
var id = -1   // usable only after the load completes (asynchronous)
pool.setOnLoadCompleteListener { _, loaded, status -> if (status == 0) id = loaded }
pool.load(session.assets.openFd("sounds/ping.ogg"), 1)   // XrPerceptionSession needs `internal val assets` (app is private)
if (id >= 0 && session.scene.spatialCapabilities.contains(SpatialCapability.SPATIAL_AUDIO)) {
    SpatialSoundPool.play(session = session, soundPool = pool, soundID = id, params = PointSourceParams(),
        entity = pingEntity, volume = 1f, priority = 0, loop = 0, rate = 1f)   // every 3 s from XrNavigationEngine
}
```

Hour-one probes (stack.md §5): Q5 `configure` → `SessionConfigureSuccess` with all four modes; Q6 non-null `smoothDepthMap`; Q11 an audibly positioned ping. Device applicability is "XR Headsets" per the guides with no per-device matrix: treat `SessionConfigureSuccess` on the Galaxy XR as the proof.

## Fallbacks

| # | Risk | Likelihood | Impact | Mitigation | Owner | Decide by |
|---|---|---|---|---|---|---|
| RK-5 | ARCore depth noisy or missing | Low | Proximity cue unreliable | Fall back to plane hit tests and detector box height as a distance proxy; only speak distances we trust | R3 | Sat 15:00 |
| RK-6 | Door one-shot returns the wrong side | Medium | Guides the user to a wall | Ask the VLM twice on two frames and require agreement; otherwise say "I can't find a door, turn slowly" | R2, R3 | Sun 09:00 |

How the code already leans toward each fallback: RK-5 → `DepthCone.minTrustedDepth` drops low-confidence pixels and returns `null` (silence, not a guess) when nothing is trusted; `XrPerceptionSession.sampleDepthAlongBearing` documents the `hitTest` path first, the depth-map path second; the detector-box-height proxy would feed a second `ProximityMonitor`-like source in `XrNavigationEngine.proximityLoop` (not written: decide at Sat 15:00). RK-6 → the vote is R1's; `XrAnchorStore.placeFromBearing` places whatever bearing wins and returns `null` for `NONE`, so the controller speaks `Cues.doorNotFound`. A-6 → wired end to end on R3's side: `SpatialAudioPinger.isSpatial` goes `false` whenever a ping cannot play (no entity, no sound, no `SPATIAL_AUDIO`, `play` failed; also the skeleton stub), `XrNavigationEngine` reads it right after `ping()` and emits `DoorBearing(…, pinged = false)`, and its guidance period drops from 3 s to 2 s (`FALLBACK_GUIDANCE_PERIOD_MS`, "say the bearing more often"). R1 plays `Earcon.PING` through the `Speaker` for each `pinged = false` cue. `docs/stack.md` Q11's "stereo-panned SoundPool from bearing" is superseded by this A-6 fallback (stack.md "Corrections"); a stereo pan is not planned.

**FR-N3 when A-1 or A-13 fails.** "Find the door" needs a world-camera still on the headset (A-1, R1) and the VLM on the headset (A-13, R2). If either fails, FR-N3 is cut from the headset demo (`plan.md` F7 rollback): Navigation demos proximity (FR-N1) and wall / floor cues (FR-N5) from depth and planes only, with no detector outlines when A-1 failed (the detector needs camera frames); the demo script drops the "find the door" beat (`docs/demo.md` 0:50) and the README Limitations section says so. The S25 Ultra cannot stand in: it runs `NoOpNavigationEngine`, so "find the door" there says `navigationUnavailable`.

## How to test without the headset

- **What the NoOps do.** `AppGraph` wires `NoOpHeadPoseSource` / `NoOpAnchorStore` / `NoOpNavigationEngine` whenever `!DeviceProfile.isXr || BuildConfig.USE_FAKES`, i.e. on the S25 Ultra, on the laptop emulator and for `./gradlew installDebug -PuseFakes=true` on any device. They are complete: Navigation reports not running, `cues` and `detections` are empty flows, `placeFromBearing` returns `null` (never reached through the controller: `ModeController.findDoor` first sees `isRunning == false` and speaks `navigationUnavailable`, not a crash), `pose` is `null` (R1 skips the hold-still check). Nothing logs a `TODO`.
- **What the test fakes do** (`app/src/test/kotlin/com/vysion/xr/perception/PerceptionFakes.kt`): `FakePerceptionSource` lets a test push depth maps (`depth.tryEmit(depthFrame(centerM = 0.5f, …))`), planes, head poses and anchor poses, and records `sampleDepthAlongBearing` calls; like the real session, an anchor id it never created (`createAnchor`) is STOPPED at once; `FakeFrameSource` / `FakeInferenceEngine` are the minimum of R1's and R2's contracts (frames are allocated `Bitmap` shells, never read); `FakeAnchorStore.track` is a channel the test fills and closes to simulate tracking loss; `FakePinger` counts attach / ping / detach and scripts what a ping finds out (`playsSpatially` → `isSpatial`).
- **Unit tests** (JUnit 4, JVM, `./gradlew testDebugUnitTest --tests "com.vysion.xr.perception.*"`): `ProximityMonitorTest` (12: cone bounds, exact cone test, trusted minimum, 1.0 m threshold, 2 s debounce, stale maps, bearing → pixel, median window), `PlaneSemanticsTest` (11: wall ahead / beyond / off-polygon / turned, nearest wall, floor ends, adjacent floor continues, out of range, untracked, primitives), `RelativeGeometryTest` (10: 12 / 3 / 6 / 10 o'clock, the SRS "2 o'clock, 3 meters" example, half-meter rounding, bearing offsets, wrap, yaw/pitch/roll from forward/up), `AngularSpeedTest` (5), `XrAnchorStoreTest` (6: placement needs a tracked pose, samples at yaw + offset, NONE / no depth / no anchor, `track` follows a 180° turn and completes on STOPPED, an unknown id completes at once, a detach completes `track` even with no head pose), `XrNavigationEngineTest` (9: idempotent start / stop, proximity gated by pause, detector skips frames while paused and survives `InferenceException`, 3 s bearing + ping then `Arrived`, guidance paused with the rest, a tick due during a pause is dropped and the cadence restarts on resume, `pinged` and the 2 s A-6 fallback, `TargetLost` on completion and on 6 s silence, plane cues gated per type), `XrPerceptionSessionTest` (3: `start` reopens a stopped session and never doubles a running one, `ensureStarted` only retries an UNAVAILABLE session, `stop` leaves no pose / planes / session). All 56 ran green under `./gradlew testDebugUnitTest` on the complete tree on 2026-10-17 (Kotlin 2.4.21, coroutines 1.11.0, JUnit 4.13.2).
- **`-PuseFakes=true` on the headset** gives you the NoOps for R3 (not the XR classes) so R1/R2/R4 can work without a session; to exercise the XR classes you need the headset without the flag.
- **The 180° test (FR-N4, SRS "test by turning 180 degrees and back").** On the headset: (1) stand 3 m from a door, say "find the door"; wait for `DoorBearing(clock=12, distanceM=3.0, inView=true, pinged=true)` and the ping ahead (`pinged=false` means A-6 failed: the ping is R1's non-spatial earcon and cannot be localized, so log that and skip the ping half of steps 2–4). (2) Turn in place 180° over ~3 s; the next cues must read `clock=6, inView=false`, the ping must sound behind you, and `adb logcat -s vysion | grep TargetLost` (R1's cue log) must stay empty. (3) Hold for 10 s turned away (longer than `TRACK_STALE_NANOS` = 6 s): still no `TargetLost`, because the native anchor keeps TRACKING out of view and the head pose keeps the samples flowing. (4) Turn back: `clock=12, inView=true` within one 3 s tick. (5) Say "stop": speech stops within 300 ms, but guidance does **not** end (`ModeController.onStop` only silences and cancels the one-shot; Contract issue 8): the next `DoorBearing` arrives within 3 s. (6) Walk to the door: inside 1 m the engine emits `Arrived`, R1 runs the ARRIVAL prompt and then calls `clearTarget()`: exactly one `pinger.detach`, no further bearings. Attach the logcat excerpt to the PR.

## Definition of done

Per [`CONTRIBUTING.md`](../../CONTRIBUTING.md) "Definition of done for a task": FR id in the PR title (`r3: FR-N1: …`), `./gradlew assembleDebug testDebugUnitTest`, `scripts/check_privacy.sh` and `scripts/check_cues.sh` green, the module checkbox above ticked in the same PR, every number in `docs/benchmarks.md`. Device evidence R3 must attach:

1. A-5: one `vysion` logcat line with the depth map size and a measured minimum depth, one with a plane label and extents, and `XrPerceptionSession.state == RUNNING` (Sat 12:00).
2. A-6: the ping probe result (audible? positioned?) written into `docs/assumptions.md` (Sat 13:00).
3. FR-N1: `vysion.latency` lines "proximity cue emitted N ms after depth copy (earcon not included …)" with N ≤ 300 and ≥ 2 s apart, copied into `docs/benchmarks.md` "Proximity warning after threshold crossed" labeled as depth → cue (the earcon start comes later, on R1's hazard lane).
4. FR-N3: an `anchor … placed` line and three consecutive successful door finds from different starting bearings in the demo room (gate 7 verification in `plan.md`), each with its spoken bearing/distance and the two VLM answers: `adb logcat -s vysion | grep -E "door vote|anchor .* placed|cue (DoorBearing|Arrived)"` (the `door vote` and `cue` lines are R1's, still to add in R1's P0 `ModeController` item).
5. FR-N4: the 180° test transcript above. FR-N5: a `WallAhead` line inside 1.5 m and none outside.

## Contract issues

1. **RESOLVED 2026-10-17 — the `Activity` that `Session.create` needs.** Was: `AppGraph` kept the session private and nothing handed it the `Activity` that `Session.create(context = activity)` needs as context and `LifecycleOwner` (stack.md D7), so `XrPerceptionSession` registered `Application.ActivityLifecycleCallbacks` to remember the resumed activity. Now (orchestrator decision 5): `AppGraph` exposes `val perception: XrPerceptionSession?`, `MainActivity.startGraph()` starts it after permissions on every `onStart` (the exact call is below), and `AppGraph.stop()` (every `onStop`) calls `perception.stop()`; only `AppGraph.release()` cancels the scope. R3 side: the `ActivityLifecycleCallbacks` are gone; `start(activity)` keeps the Activity weakly; `stop()` cancels the feeders, detaches every anchor, clears the session, Activity, pose and planes and sets `STOPPED`, and `start()` after `stop()` opens a fresh run (`SessionStartPolicy`, tested in `XrPerceptionSessionTest`). Today the call is `lifecycleScope.launch { graph.startPerception(this) }` in `MainActivity.startGraph()` (not `perception?.start(this)`): `AppGraph.startPerception` joins the previous `stop()`'s teardown first (R1 open issue 8), and `startGraph()` runs from `onStart` and again from `onPermissionResult`, so a first attempt that failed on a missing `SCENE_UNDERSTANDING_*` grant is retried once the XR permissions arrive. `ensureStarted()` (from `XrNavigationEngine.start()` and `XrAnchorStore.placeFromBearing`) stays as a harmless backup: it retries an UNAVAILABLE session with the last Activity, and never opens one that was never started or that `onStop` stopped.
2. **RESOLVED 2026-10-17 — A-6 fallback contract path.** Was: R3 holds no `Speaker` and `DoorBearing` had no "pinged" flag. Now (orchestrator decision 4, `core/Contracts.kt`): `DoorBearing(clock, distanceM, inView, pinged: Boolean = true)`. R3 side: `AnchorPinger` exposes `isSpatial`; `SpatialAudioPinger.ping()` sets it `false` whenever a ping cannot play (including the skeleton stub, so the user still hears R1's earcon) and `true` when one plays; `XrNavigationEngine` reads it right after `ping()` and emits `pinged = isSpatial != false` (null = not probed yet = pinged), and while it is `false` the guidance period is 2 s instead of 3 s (the second half of the A-6 fallback). Tested in `XrNavigationEngineTest`. Consumer side (R1): `ModeController.onCue` plays `speaker.earcon(Earcon.PING)` before the words of a `DoorBearing` with `pinged == false`.
3. **`HeadPose` has no position.** Bearing and distance need the head position, so R3 keeps an internal `RawHeadPose` with it; no contract change requested. If R1 ever needs the position (transcript anchoring, FR-T2 P1), add `positionM: Vec3?` additively.
4. **RESOLVED 2026-10-17 — stale rows in `docs/stack.md` §3-R3 found by `javap`.** Appended as four rows under stack.md "Corrections found at build time": `FieldOfView` is `androidx.xr.runtime.math.FieldOfView` (not `androidx.xr.runtime`); `Session` also exposes `coroutineScope` and `activity`; `DeviceTrackingMode` has `LAST_KNOWN`, `SPATIAL_LAST_KNOWN`, `INERTIAL`, `INERTIAL_LAST_KNOWN` besides `DISABLED` / `SPATIAL`; and the runtime note is updated: the rc02 runtime AAR is now cached and is what the build resolves, and 1.1.0-alpha01 differs from it in `SpatialApiVersions` and the internal `XrInstanceManager` only (the classes R3 uses are byte-identical), so D2 stands.
5. **FR-D4 (P2) needs a region in the describe call.** `InferenceEngine.describe(bitmap, prompt)` has no region parameter; pointing would need `describe(bitmap.crop(region), …)` done by R1 from a `NormalizedBox` R3 provides. Proposed additive type: `data class PointingRegion(val box: NormalizedBox)` in `core`, emitted by a future `PointingSource`. Not started (P2).
6. **OPEN — capture-time pose for `placeFromBearing`.** The VLM's left / center / right is relative to the head at `captureStill()` time, but `placeFromBearing(bearing)` reads the head pose when it is called, after both VLM answers (up to 2 × 15 s later). Proposed additive change (R5 merges it alone, AGENTS.md §6; listed in `docs/contracts.md` "Open contract proposals"): `suspend fun placeFromBearing(bearing: HorizontalBearing, capturedAtNanos: Long? = null): AnchorId?`. R1 passes `System.nanoTime()` taken right after the first `captureStill()` returns. R3 keeps a 30 s ring buffer of `RawHeadPose` stamped with `System.nanoTime()` at copy time (a second stamp, because `RawHeadPose.timestampNanos` is `SystemClock.elapsedRealtimeNanos()`, a different clock) and uses the sample nearest `capturedAtNanos`, or the current pose when it is `null` or older than the buffer. `NoOpAnchorStore` and the test fakes take the new parameter and ignore it. Until it lands, the anchor uses the call-time yaw.
7. **OPEN — one XR session or two (Q21).** R1's `SpatialShell` uses Compose for XR's `LocalSession.current`; `XrPerceptionSession` creates its own with `Session.create(context = activity)`; the SRS says the ARCore session is "created from the Compose for XR session". Nothing says whether these are the same object, or whether two can coexist, which matters for `session.scene`, `AnchorSpace` and `SpatialSoundPool`. Check on the first device run (R3 + R1, Sat 12:00): log `System.identityHashCode` of both sessions under `vysion` (`SpatialShell` can log `LocalSession.current`; `open()` logs its own). If they differ and either session misbehaves (no scene capabilities, anchors not rendering audio), R1 hands `LocalSession.current` to `AppGraph.startPerception` and R3 configures that session instead of creating one (an `AppGraph`/`MainActivity` change, announced like a contract change). Recorded as Q21 in `docs/stack.md` "Corrections".
8. **OPEN — does "stop" end door guidance?** Code today: no. `Command.Stop` silences speech and cancels the in-flight one-shot (FR-G4); the target stays set and the next `DoorBearing` speaks within 3 s (R1 open issue 5). Guidance ends on `Arrived` (R1 calls `clearTarget()` after the ARRIVAL prompt) or `TargetLost`. Decision: R4 + R1 at the Sat 21:00 blindfold test, landing by gate 6 (Sat 22:00); if "stop" must end guidance, R1 adds `navigation.clearTarget()` to `ModeController.onStop` when a target is set, and the 180° test step 5 above changes to "exactly one `pinger.detach`".

## Dependencies needed

None: everything R3 imports is pinned in `gradle/libs.versions.toml` (`androidx.xr.runtime:runtime`, `androidx.xr.arcore:arcore`, `androidx.xr.scenecore:scenecore` 1.0.0-rc02, `kotlinx-coroutines` 1.11.0, `junit` 4.13.2, `kotlinx-coroutines-test` 1.11.0). One **asset**, not a dependency: `app/src/main/assets/sounds/ping.ogg`, the ping earcon `SpatialAudioPinger` loads (`PING_ASSET`); R4 owns earcons (`voice/Earcons`); the file is a checklist line in R4's P0 `Speaker` item (r4-voice-accessibility.md: mono, 60 ms, the `Earcons.PING` tone = `Earcons.toneFor(Earcon.PING)`, rendered with the `ffmpeg` command on that page; there is no `EarconSynth.pcm(Earcon)` overload, the signature is `EarconSynth.pcm(tone: Tone, sampleRateHz)`; due by Sat 13:00 for the A-6 probe), and R3 references the path only.
