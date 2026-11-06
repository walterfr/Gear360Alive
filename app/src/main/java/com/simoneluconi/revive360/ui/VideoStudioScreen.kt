package com.simoneluconi.revive360.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.media.MediaSaver
import com.simoneluconi.revive360.media.PhoneItem
import com.simoneluconi.revive360.media.PhoneMedia
import com.simoneluconi.revive360.media.TelemetryHud
import com.simoneluconi.revive360.media.VideoStitcher
import com.simoneluconi.revive360.protocol.FileKind
import com.simoneluconi.revive360.stitch.AspectPreset
import com.simoneluconi.revive360.stitch.CameraMetadata
import com.simoneluconi.revive360.stitch.DeepTracker360
import com.simoneluconi.revive360.stitch.DualFisheye
import com.simoneluconi.revive360.stitch.GyroSample
import com.simoneluconi.revive360.stitch.Keyframe
import com.simoneluconi.revive360.stitch.KeyframeTimeline
import com.simoneluconi.revive360.stitch.Mat3
import com.simoneluconi.revive360.stitch.Projection
import com.simoneluconi.revive360.stitch.ShotTemplate
import com.simoneluconi.revive360.stitch.SphereVideoView
import com.simoneluconi.revive360.stitch.ViewState
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Insta360-style 360° Video Player & Reframe Keyframe Studio.
 *
 * Plays dual-fisheye MP4 videos on the 360° sphere in real time, applies `vrot` FlowState
 * horizon stabilization, lets the user add/remove camera [Keyframe]s (Yaw, Pitch, Roll, FOV,
 * Normal / Little Planet) across the timeline, and exports GPU-stitched MP4s in 9:16, 16:9, 1:1
 * or 360° 4K Equirectangular with Spherical V1 metadata.
 */
@Composable
fun VideoStudioScreen(path: String? = null, phoneItem: PhoneItem? = null) {
    val nav = LocalActions.current
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val controller = nav.app.controller
    val files by controller.files.collectAsStateWithLifecycle()
    val cameraFile = path?.let { p -> files?.firstOrNull { it.path == p } }
    val title = phoneItem?.name ?: cameraFile?.name ?: path?.substringAfterLast('/') ?: "360° Studio"
    val baseName = phoneItem?.baseName ?: cameraFile?.baseName ?: title.substringBeforeLast('.')
    val shotTime = phoneItem?.time ?: cameraFile?.mtime ?: (System.currentTimeMillis() / 1000L)

    var fetchProgress by remember { mutableFloatStateOf(-1f) }
    var localFile by remember { mutableStateOf<File?>(null) }
    var model by remember { mutableStateOf(DualFisheye()) }
    var gyroSamples by remember { mutableStateOf<List<GyroSample>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(1L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var flowState by remember { mutableStateOf(true) }
    var viewFinder by remember { mutableStateOf(false) }
    var nadirPatch by remember { mutableStateOf(false) }
    var showTelemetry by remember { mutableStateOf(false) }
    var gpxTrack by remember { mutableStateOf<List<TelemetryHud.GpxPoint>>(emptyList()) }
    var trackingTarget by remember { mutableStateOf(false) }
    var keyframeSpeed by remember { mutableFloatStateOf(1f) }
    var preset by remember { mutableStateOf(AspectPreset.VERTICAL_9_16) }
    val keyframes = remember { mutableStateListOf<Keyframe>() }
    var sphereView by remember { mutableStateOf<SphereVideoView?>(null) }

    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }

    val gpxPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val parsed = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                        ?.let { TelemetryHud.parseGpx(it) } ?: emptyList()
                }
                if (parsed.isNotEmpty()) {
                    gpxTrack = parsed
                    showTelemetry = true
                    nav.message("Loaded ${parsed.size} GPS points", MessageKind.GOOD)
                } else {
                    nav.message("No valid GPX trackpoints found", MessageKind.BAD)
                }
            }
        }
    }

    // ViewFinder: sample phone rotation sensor (gimbal-lock free via relative 3x3 rotation matrix)
    DisposableEffect(viewFinder) {
        if (!viewFinder) return@DisposableEffect onDispose {}
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || sensor == null) {
            viewFinder = false
            return@DisposableEffect onDispose {}
        }
        var baseRot: FloatArray? = null
        var baseView = sphereView?.view ?: ViewState()
        var smoothYaw = baseView.yaw
        var smoothPitch = baseView.pitch
        var lastKfMs = -1000L
        val rot = FloatArray(9)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val sv = sphereView ?: return
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                val b = baseRot
                if (b == null) {
                    baseRot = rot.clone()
                    baseView = sv.view
                    smoothYaw = baseView.yaw
                    smoothPitch = baseView.pitch
                    return
                }
                // Relative rotation R_rel = R_0^T * R_t (row-major from SensorManager)
                // Forward axis in phone camera frame is -Z (col 2); project onto base frame
                val relX = -(b[0] * rot[2] + b[3] * rot[5] + b[6] * rot[8])
                val relY = -(b[1] * rot[2] + b[4] * rot[5] + b[7] * rot[8])
                val relZ = -(b[2] * rot[2] + b[5] * rot[5] + b[8] * rot[8])
                val dYaw = kotlin.math.atan2(relX.toDouble(), (-relZ).toDouble()).toFloat()
                val dPitch = kotlin.math.asin(relY.coerceIn(-1f, 1f).toDouble()).toFloat()
                val alpha = 0.22f
                val targetYaw = baseView.yaw + dYaw
                smoothYaw = DeepTracker360.wrapAngle(smoothYaw + DeepTracker360.wrapAngle(targetYaw - smoothYaw) * alpha)
                smoothPitch = (smoothPitch + (baseView.pitch - dPitch - smoothPitch) * alpha)
                    .coerceIn(-Math.toRadians(88.0).toFloat(), Math.toRadians(88.0).toFloat())
                sv.manualOverride = true
                sv.setView(sv.view.copy(yaw = smoothYaw, pitch = smoothPitch))
                if (playing && positionMs - lastKfMs >= 250L) {
                    lastKfMs = positionMs
                    val kf = Keyframe(
                        timeUs = positionMs * 1_000L,
                        yaw = smoothYaw,
                        pitch = smoothPitch,
                        roll = sv.view.roll,
                        fov = sv.view.fov,
                        projection = sv.view.projection,
                    )
                    val updated = KeyframeTimeline.upsert(keyframes.toList(), kf)
                    keyframes.clear()
                    keyframes.addAll(updated)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
        }
    }

    LaunchedEffect(path, phoneItem) {
        if (localFile != null) return@LaunchedEffect
        error = null
        try {
            val fetched = when {
                phoneItem != null -> withContext(Dispatchers.IO) { PhoneMedia.local(context, phoneItem) }
                cameraFile != null -> controller.original(cameraFile) { done, total ->
                    fetchProgress = if (total > 0) done.toFloat() / total else 0f
                }
                else -> error("Video not found")
            }
            fetchProgress = 1f
            val cal = withContext(Dispatchers.Default) { CameraMetadata.readCalibration(fetched) }
            val gyro = withContext(Dispatchers.Default) { CameraMetadata.readMp4Gyro(fetched) }
            model = cal
            gyroSamples = gyro
            localFile = fetched
            sphereView?.setModel(cal)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Failed to load video"
        }
    }

    LaunchedEffect(playing, player, flowState, keyframes.size) {
        while (playing) {
            val mp = player ?: break
            val pos = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L)
            positionMs = pos
            val timeUs = pos * 1_000L
            val level = if (flowState && gyroSamples.isNotEmpty()) {
                CameraMetadata.sampleGyroAt(gyroSamples, timeUs)?.horizonLockMatrix() ?: Mat3.identity()
            } else Mat3.identity()
            val sv = sphereView
            if (sv != null) {
                val nextView = if (keyframes.isNotEmpty() && !sv.userInteracting && !sv.manualOverride && !viewFinder) {
                    KeyframeTimeline.evaluate(keyframes, timeUs, sv.view, level)
                } else {
                    sv.view.copy(level = level)
                }
                sv.setView(nextView)
            }
            delay(16L)
        }
    }

    fun startPlaybackOn(surface: Surface, file: File) {
        runCatching { player?.release() }
        val mp = MediaPlayer().apply {
            setDataSource(file.path)
            setSurface(surface)
            isLooping = true
            prepare()
            durationMs = duration.toLong().coerceAtLeast(1L)
            start()
        }
        player = mp
        playing = true
    }

    ScreenScaffold(title = title, scroll = false) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val readyFile = localFile
            if (readyFile != null) {
                AndroidView(
                    factory = { ctx ->
                        SphereVideoView(
                            context = ctx,
                            model = model,
                            onSurfaceReady = { surface -> startPlaybackOn(surface, readyFile) },
                            onSurfaceDestroyed = {
                                runCatching { player?.stop() }
                                playing = false
                            },
                        ).also {
                            it.setNadirPatch(nadirPatch)
                            sphereView = it
                        }
                    },
                    update = { it.setNadirPatch(nadirPatch) },
                    modifier = if (preset == AspectPreset.EQUIRECT_360) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.aspectRatio(preset.ratio, matchHeightConstraintsFirst = preset.ratio < 1f)
                    },
                )
                if (showTelemetry) {
                    val timeUs = positionMs * 1_000L
                    val curGyro = CameraMetadata.sampleGyroAt(gyroSamples, timeUs)
                    val gForce = TelemetryHud.computeAngularGForce(gyroSamples, timeUs)
                    val gpxPt = TelemetryHud.sampleGpxAt(gpxTrack, timeUs)
                    val curSpeed = KeyframeTimeline.evaluateSpeed(keyframes, timeUs, keyframeSpeed)
                    val pitchDeg = Math.toDegrees((curGyro?.pitch ?: 0f).toDouble())
                    val rollDeg = Math.toDegrees((curGyro?.roll ?: 0f).toDouble())
                    val gpsStr = if (gpxPt != null) " · ${"%.1f".format(gpxPt.speedKmh)} km/h · ${"%.0f".format(gpxPt.ele)}m" else ""
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Label(
                            "HUD · ${"%.2f".format(gForce)}G · Pitch ${"%.0f".format(pitchDeg)}° · Roll ${"%.0f".format(rollDeg)}°$gpsStr · ${"%.1f".format(curSpeed)}x",
                            size = 11.sp,
                            color = Color.White,
                            mono = true,
                        )
                    }
                }
            } else if (error == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spinner()
                    Spacer(Modifier.height(10.dp))
                    Label(
                        if (fetchProgress in 0f..0.99f) "Downloading video… ${(fetchProgress * 100).toInt()}%"
                        else "Preparing 360° Studio…",
                        size = 14.sp,
                        color = Color.White,
                    )
                }
            } else {
                Label(error!!, color = colors.bad)
            }
        }

        // Timeline scrubber & Keyframe controls
        Card(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(if (playing) "Pause" else "Play", enabled = localFile != null && !exporting) {
                    val mp = player ?: return@PillButton
                    if (playing) {
                        mp.pause()
                        playing = false
                    } else {
                        mp.start()
                        playing = true
                    }
                }
                Label(
                    "${formatMs(positionMs)} / ${formatMs(durationMs)}",
                    Modifier.weight(1f),
                    size = 13.sp,
                    color = colors.text2,
                    mono = true,
                )
                PillButton("+ Keyframe", enabled = localFile != null && !exporting) {
                    val sv = sphereView
                    val v = sv?.view ?: ViewState()
                    val kf = Keyframe(
                        timeUs = positionMs * 1_000L,
                        yaw = v.yaw,
                        pitch = v.pitch,
                        roll = v.roll,
                        fov = v.fov,
                        projection = v.projection,
                        speed = keyframeSpeed,
                    )
                    val updated = KeyframeTimeline.upsert(keyframes.toList(), kf)
                    keyframes.clear()
                    keyframes.addAll(updated)
                    sv?.manualOverride = false
                    nav.message("Keyframe (${keyframeSpeed.toInt()}x) at ${formatMs(positionMs)} (${keyframes.size} total)", MessageKind.GOOD)
                }
                if (keyframes.isNotEmpty()) {
                    PillButton("Clear", enabled = !exporting) {
                        keyframes.clear()
                        sphereView?.manualOverride = false
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            ValueSlider(
                value = ((positionMs.toFloat() / durationMs.coerceAtLeast(1L)) * 1000f).toInt().coerceIn(0, 1000),
                range = 0..1000,
                onChange = { step ->
                    val targetMs = (step / 1000f * durationMs).toLong()
                    positionMs = targetMs
                    runCatching { player?.seekTo(targetMs.toInt()) }
                    val timeUs = targetMs * 1_000L
                    val level = if (flowState && gyroSamples.isNotEmpty()) {
                        CameraMetadata.sampleGyroAt(gyroSamples, timeUs)?.horizonLockMatrix() ?: Mat3.identity()
                    } else Mat3.identity()
                    sphereView?.let { sv ->
                        sv.manualOverride = false
                        sv.setView(KeyframeTimeline.evaluate(keyframes, timeUs, sv.view, level))
                    }
                },
                enabled = localFile != null && !exporting,
            )

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AspectPreset.entries.forEach { p ->
                    Chip(p.label, selected = preset == p) { preset = p }
                }
                Chip("FlowState", selected = flowState) { flowState = !flowState }
                Chip("ViewFinder", selected = viewFinder) {
                    viewFinder = !viewFinder
                    if (!viewFinder) sphereView?.manualOverride = false
                }
                Chip("Little Planet", selected = sphereView?.view?.projection == Projection.STEREOGRAPHIC) {
                    val sv = sphereView ?: return@Chip
                    val nextProj = if (sv.view.projection == Projection.STEREOGRAPHIC) Projection.RECTILINEAR else Projection.STEREOGRAPHIC
                    val nextView = if (nextProj == Projection.STEREOGRAPHIC) {
                        sv.view.copy(projection = Projection.STEREOGRAPHIC, pitch = (Math.PI / 2).toFloat(), fov = 260f)
                    } else {
                        sv.view.copy(projection = Projection.RECTILINEAR, pitch = 0f, fov = 90f)
                    }
                    sv.setView(nextView)
                }
                Chip("Nadir Patch", selected = nadirPatch) { nadirPatch = !nadirPatch }
                Chip("HUD Stats", selected = showTelemetry) { showTelemetry = !showTelemetry }
                Chip(if (gpxTrack.isEmpty()) "+ GPX" else "GPX (${gpxTrack.size})", selected = gpxTrack.isNotEmpty()) {
                    gpxPicker.launch("*/*")
                }
                Chip(if (trackingTarget) "Tracking…" else "Deep Track", selected = trackingTarget) {
                    val src = localFile ?: return@Chip
                    if (trackingTarget || exporting) return@Chip
                    val sv = sphereView
                    val startView = sv?.view ?: ViewState()
                    val startUs = positionMs * 1_000L
                    val totalUs = durationMs * 1_000L
                    val curModel = model
                    val curGyro = gyroSamples
                    val curFlow = flowState
                    trackingTarget = true
                    scope.launch {
                        try {
                            val tracked = withContext(Dispatchers.Default) {
                                DeepTracker360.trackVideo(
                                    videoFile = src,
                                    model = curModel,
                                    startYaw = startView.yaw,
                                    startPitch = startView.pitch,
                                    fov = startView.fov,
                                    durationUs = totalUs,
                                    startTimeUs = startUs,
                                    gyroSamples = curGyro,
                                    flowState = curFlow,
                                )
                            }
                            keyframes.clear()
                            keyframes.addAll(tracked)
                            sv?.manualOverride = false
                            nav.message("Deep Track locked (${tracked.size} keyframes)", MessageKind.GOOD)
                        } catch (e: Exception) {
                            nav.message("Deep Track error: ${e.message}", MessageKind.BAD)
                        } finally {
                            trackingTarget = false
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (spd in listOf(1f, 2f, 4f, 8f)) {
                    Chip("${spd.toInt()}x", selected = keyframeSpeed == spd) { keyframeSpeed = spd }
                }
                ShotTemplate.entries.forEach { tpl ->
                    Chip(tpl.label, selected = false) {
                        val sv = sphereView
                        val generated = KeyframeTimeline.buildTemplate(
                            template = tpl,
                            durationUs = durationMs * 1_000L,
                            baseYaw = sv?.view?.yaw ?: 0f,
                            gyroSamples = gyroSamples,
                        )
                        keyframes.clear()
                        keyframes.addAll(generated)
                        sv?.manualOverride = false
                        nav.message("Applied Shot Lab: ${tpl.label}", MessageKind.GOOD)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            if (exporting) {
                Label("Exporting ${preset.label} via GPU… ${(exportProgress * 100).toInt()}%", size = 14.sp)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.line)) {
                    Box(Modifier.fillMaxWidth(exportProgress.coerceIn(0.02f, 1f)).height(6.dp).clip(CircleShape).background(colors.accent))
                }
            } else {
                PillButton(
                    "Export ${preset.label} MP4",
                    Modifier.fillMaxWidth(),
                    icon = AppIcons.Video,
                    style = ButtonStyle.PRIMARY,
                    enabled = localFile != null,
                ) {
                    val src = localFile ?: return@PillButton
                    val curView = sphereView?.view ?: ViewState()
                    val kfs = keyframes.toList()
                    val chosenPreset = preset
                    val useFlow = flowState
                    val useNadir = nadirPatch
                    val useHud = showTelemetry
                    val curGpx = gpxTrack
                    runCatching { player?.pause() }
                    playing = false
                    exporting = true
                    exportProgress = 0f
                    scope.launch {
                        val suffix = if (chosenPreset == AspectPreset.EQUIRECT_360) "_360.mp4" else "_${chosenPreset.name.lowercase()}.mp4"
                        val outName = "$baseName$suffix"
                        val outFile = File(context.cacheDir, outName)
                        try {
                            withContext(Dispatchers.Default) {
                                VideoStitcher.export(
                                    VideoStitcher.ExportConfig(
                                        input = src,
                                        output = outFile,
                                        preset = chosenPreset,
                                        keyframes = kfs,
                                        defaultView = curView,
                                        flowStateStabilization = useFlow,
                                        nadirPatch = useNadir,
                                        hudTelemetry = useHud,
                                        gpxTrack = curGpx,
                                    )
                                ) { exportProgress = it }
                                MediaSaver.publish(context, outName, FileKind.VIDEO, "video/mp4", shotTime, outFile)
                            }
                            EventLog.log("Video Studio exported $outName")
                            nav.message("Saved $outName in ${MediaSaver.FOLDER}", MessageKind.GOOD)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            EventLog.log("Video export failed: ${e.message}")
                            nav.message("Export failed: ${e.message}", MessageKind.BAD)
                        } finally {
                            outFile.delete()
                            exporting = false
                        }
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}
