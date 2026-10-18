package com.vysion.xr.platform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.OrbiterPosition
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialRow
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.runtime.Session
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.scene
import com.vysion.xr.AppGraph
import kotlinx.coroutines.launch

/**
 * The Galaxy XR layout: passthrough Full Space with one floating control panel (FR-G1), the
 * large-print transcript panel (FR-T2) and the outline overlay (FR-N2/FR-N3). Owner: R1 (SRS §8
 * "App shell, passthrough Full Space … panels", Sat 11:00 / Sat 18:00). Assumption A-2: Full Space
 * keeps passthrough on while our panels are shown; the fallback from docs/assumptions.md
 * (`preferredPassthroughOpacity = 1.0f`) is applied unconditionally in [forcePassthrough].
 *
 * APIs, all VERIFIED in docs/stack.md → R1 "Compose for XR 1.0.0-beta01" and re-checked with
 * `javap` against the AAR: `Subspace`, `SpatialRow`, `SpatialPanel`, `SubspaceModifier.width /
 * height / movable / resizable`, `Orbiter(OrbiterPosition.BottomCenter())`, `LocalSession`,
 * `LocalSpatialCapabilities`, `session.scene.spatialEnvironment.preferredPassthroughOpacity`,
 * `SpatialCapability.PASSTHROUGH_CONTROL`, `scene.requestFullSpace()`.
 *
 * Layout: a `SpatialRow` with the transparent overlay panel on the left (passthrough shows through
 * the un-drawn pixels; whether a `SpatialPanel` composites transparently is part of the A-2 check)
 * and the transcript panel on the right, with the control panel as a bottom-center `Orbiter`.
 * Drawing straight into passthrough is UNVERIFIED; the snippet from docs/stack.md is kept here for
 * the Sat 21:00 device check, not compiled:
 * ```
 * // stack.md: UNVERIFIED — SpatialExternalSurface(stereoMode = StereoMode.Mono, modifier) { /* draw outlines */ }
 * ```
 * When spatial UI is not enabled (Home Space, or a runtime without the feature) the shell requests
 * Full Space once (docs/stack.md Q4 fallback) and shows [FlatShell] meanwhile (ADR-0006).
 *
 * [diagnostics] is the FR-G5 overlay slot that `MainActivity` fills with R5's `DiagnosticsOverlay`, so
 * this package never imports `com.vysion.xr.diagnostics` (ADR-0005).
 */
@Composable
fun SpatialShell(graph: AppGraph, diagnostics: @Composable () -> Unit) {
    val capabilities = LocalSpatialCapabilities.current
    val session = LocalSession.current

    // One Session per activity: hand Compose's to R3 so perception never calls Session.create twice.
    LaunchedEffect(session) {
        graph.perception?.offerComposeSession(session)
    }
    // A-2 fallback: ask for full passthrough as soon as we are allowed to control it.
    LaunchedEffect(session, capabilities.isPassthroughControlEnabled) {
        session?.let { forcePassthrough(it) }
    }
    // Q4 fallback: the manifest property should already put us in Full Space; ask once if it did not.
    LaunchedEffect(session, capabilities.isSpatialUiEnabled) {
        if (session != null && !capabilities.isSpatialUiEnabled) session.scene.requestFullSpace()
    }

    if (!capabilities.isSpatialUiEnabled) {
        FlatShell(graph, diagnostics)
        return
    }

    val shell = rememberShellState(graph)
    var diagnosticsShown by rememberSaveable { mutableStateOf(false) }
    // TEMP-DEBUG (test only, remove before the demo)
    val debugScope = androidx.compose.runtime.rememberCoroutineScope()
    var debugStatus by androidx.compose.runtime.remember { mutableStateOf("Place marker ahead") }
    val debugLiveFlow: kotlinx.coroutines.flow.StateFlow<String?> =
        androidx.compose.runtime.remember { graph.perception?.debugLive ?: kotlinx.coroutines.flow.MutableStateFlow(null) }
    val debugLive by debugLiveFlow.collectAsState()
    val debugPingFlow: kotlinx.coroutines.flow.StateFlow<String> =
        androidx.compose.runtime.remember { graph.perception?.debugPing ?: kotlinx.coroutines.flow.MutableStateFlow("ping: no XR") }
    val debugPing by debugPingFlow.collectAsState()
    var debugCue by androidx.compose.runtime.remember { mutableStateOf("no guidance cue yet") }
    LaunchedEffect(Unit) {
        graph.navigationEngine.cues.collect { cue ->
            debugCue = when (cue) {
                is com.vysion.xr.core.NavigationCue.DoorBearing ->
                    "door at ${cue.clock} o'clock, %.1f m${if (cue.inView) "" else " (out of view)"}".format(cue.distanceM)
                else -> cue.toString()
            }
        }
    }

    Subspace {
        SpatialRow {
            // FR-N2 / FR-N3: outlines over passthrough. Transparent background; only the boxes are drawn.
            SpatialPanel(SubspaceModifier.width(OVERLAY_PANEL_WIDTH).height(OVERLAY_PANEL_HEIGHT)) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
                    shell.overlay?.let { OverlayCanvas(renderer = it, modifier = Modifier.fillMaxSize()) }
                }
            }
            // FR-T2: 48 sp white on black (21:1); movable and resizable by the user.
            SpatialPanel(
                SubspaceModifier.width(TRANSCRIPT_PANEL_WIDTH).height(TRANSCRIPT_PANEL_HEIGHT).movable().resizable(),
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    TranscriptPanel(
                        transcript = shell.transcript,
                        progress = shell.progress,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // TEMP-DEBUG (test only, remove before the demo): marker test button.
                    Box(modifier = Modifier.align(Alignment.TopStart).padding(PANEL_MARGIN)) {
                        androidx.compose.material3.Button(onClick = {
                            debugScope.launch {
                                debugStatus = graph.perception?.debugPlaceAnchorAhead() ?: "no XR session"
                                graph.perception?.debugLastAnchor?.let { id ->
                                    if (!graph.navigationEngine.isRunning.value) graph.navigationEngine.start()
                                    graph.navigationEngine.guideTo(id)
                                }
                            }
                        }) { androidx.compose.material3.Text(debugLive ?: debugStatus) }
                    }
                    Box(modifier = Modifier.align(Alignment.BottomStart).padding(PANEL_MARGIN)) {
                        androidx.compose.foundation.layout.Column {
                            androidx.compose.material3.Text(debugCue, color = Color.Yellow)
                            androidx.compose.material3.Text(debugPing, color = Color.Cyan)
                        }
                    }
                    if (diagnosticsShown) {
                        Box(modifier = Modifier.align(Alignment.TopEnd).padding(PANEL_MARGIN)) {
                            diagnostics() // FR-G5 (R5's DiagnosticsOverlay, passed in by MainActivity)
                        }
                    }
                }
                // FR-G1: the one floating control panel, orbiting the transcript's bottom edge.
                Orbiter(OrbiterPosition.BottomCenter()) {
                    ControlPanel(
                        commands = graph.commandBus,
                        listening = shell.listening,
                        mode = shell.mode,
                        state = shell.state,
                        diagnosticsShown = diagnosticsShown,
                        onToggleDiagnostics = { diagnosticsShown = !diagnosticsShown },
                    )
                }
            }
        }
    }
}

/**
 * A-2 fallback (docs/assumptions.md; docs/stack.md → R1 "Passthrough"): prefer full passthrough
 * when the capability is present. Gated, because setting the preference without the capability is
 * ignored at best.
 */
private fun forcePassthrough(session: Session) {
    val scene = session.scene
    if (scene.spatialCapabilities.contains(SpatialCapability.PASSTHROUGH_CONTROL)) {
        scene.spatialEnvironment.preferredPassthroughOpacity = FULL_PASSTHROUGH
    }
}

/** docs/stack.md → R1 snippet: 1400 x 824 dp transcript panel at the default 1.75 m. */
private val TRANSCRIPT_PANEL_WIDTH = 1400.dp
private val TRANSCRIPT_PANEL_HEIGHT = 824.dp
private val OVERLAY_PANEL_WIDTH = 1100.dp
private val OVERLAY_PANEL_HEIGHT = 824.dp
private val PANEL_MARGIN = 12.dp
private const val FULL_PASSTHROUGH = 1.0f
