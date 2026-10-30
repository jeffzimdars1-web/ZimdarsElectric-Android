package com.zimdarselectric.app.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingFailureReason
import com.zimdarselectric.app.AppViewModel
import com.zimdarselectric.app.data.JobBuild
import com.zimdarselectric.app.data.JobBuild.Part
import com.zimdarselectric.app.data.JobBuild.PartType
import com.zimdarselectric.app.data.JobBuild.Run
import com.zimdarselectric.app.data.JobBuild.RunType
import com.zimdarselectric.app.data.JobBuild.V3
import com.zimdarselectric.app.data.JobBuild.Wall
import com.zimdarselectric.app.data.qtyText
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.arcore.getUpdatedPlanes
import io.github.sceneview.ar.arcore.isValid
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.SphereNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberOnGestureListener
import kotlin.math.abs
import kotlin.math.atan2
import kotlinx.coroutines.launch

private enum class BuildMode(val label: String) { PLACE("Place"), RUN("Run"), STUD("Mark stud") }

private fun partColor(t: PartType) = when (t) {
    PartType.RECEPTACLE -> Color(0xFFFF9A3C)
    PartType.GFCI -> Color(0xFFFF5252)
    PartType.SWITCH -> Color(0xFF4DA3FF)
    PartType.SWITCH3 -> Color(0xFFB57BFF)
    PartType.LIGHT -> Color(0xFFFFE04D)
    PartType.WAFER -> Color(0xFFFFFFFF)
    PartType.JBOX -> Color(0xFF9AA3A8)
    PartType.PANEL -> Color(0xFF55606A)
}

private fun runColor(t: RunType) = when {
    t.conduit -> Color(0xFFB8C0C6)
    t.gauge <= 12 -> Color(0xFFFFD21F) // yellow jacket
    else -> Color(0xFFF5F5F0)          // white jacket
}

/** Point the camera at a wall or ceiling, place boxes and devices, draw runs, and get the material list. */
@Composable
fun CameraBuildScreen(vm: AppViewModel, quoteId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    var hasCam by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCam = it }
    LaunchedEffect(Unit) { if (!hasCam) ask.launch(Manifest.permission.CAMERA) }
    if (!hasCam) {
        Column(Modifier.fillMaxSize().background(Z.bg).padding(22.dp)) {
            Text("←", color = Z.text, fontSize = 22.sp, modifier = Modifier.clickable { onBack() }.padding(8.dp))
            Spacer(Modifier.height(20.dp))
            Title("Build in camera")
            Spacer(Modifier.height(10.dp))
            Text("The camera is needed to build the job on the wall. Nothing is recorded or sent anywhere.", color = Z.muted)
            Spacer(Modifier.height(16.dp))
            NeonButton("Allow camera", Modifier.fillMaxWidth()) { ask.launch(Manifest.permission.CAMERA) }
        }
        return
    }
    BuildAr(vm, quoteId, onBack)
}

@Composable
private fun BuildAr(vm: AppViewModel, quoteId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberARCameraNode(engine)
    val nodes = rememberNodes()
    val frameRef = remember { arrayOfNulls<Frame>(1) }
    val nodeToPart = remember { HashMap<Node, Int>() }

    val parts = remember { mutableStateListOf<Part>() }
    val runs = remember { mutableStateListOf<Run>() }
    val history = remember { mutableStateListOf<Char>() } // 'P' part, 'R' run, 'W' wall
    var nextId by remember { mutableIntStateOf(1) }
    var wall by remember { mutableStateOf<Wall?>(null) }
    var floorY by remember { mutableStateOf<Double?>(null) }
    var mode by remember { mutableStateOf(BuildMode.PLACE) }
    var partType by remember { mutableStateOf(PartType.RECEPTACLE) }
    var runType by remember { mutableStateOf(RunType.NM14_2) }
    var runStart by remember { mutableStateOf<Int?>(null) }
    var cutaway by remember { mutableStateOf(true) }
    var oldWork by remember { mutableStateOf(true) }
    var showList by remember { mutableStateOf(false) }
    var tracking by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf("Move the phone slowly so it can see the wall. Then tap to place.") }

    // Colors made once and shared by all the shapes.
    val mats = remember(materialLoader) { BuildMats(materialLoader) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun toF3(v: V3) = Float3(v.x.toFloat(), v.y.toFloat(), v.z.toFloat())
    fun yawFor(dir: V3) = Math.toDegrees(atan2(-dir.z, dir.x)).toFloat()

    // Rebuild what's drawn whenever the plan changes (a few dozen shapes at most).
    LaunchedEffect(parts.toList(), runs.toList(), wall, cutaway, floorY, runStart) {
        val old = nodes.toList()
        nodes.clear()
        nodeToPart.clear()
        // Free the old shapes once the scene has let go of them.
        scope.launch { kotlinx.coroutines.delay(400); old.forEach { runCatching { it.destroy() } } }
        val w = wall
        if (w != null && cutaway) {
            val along = w.along; val n = V3(w.normal.x, 0.0, w.normal.z).norm()
            val floor = floorY ?: (w.stud.y - 1.0)
            val midY = floor + 1.22
            val yaw = yawFor(along)
            val base = V3(w.stud.x, midY, w.stud.z)
            nodes += CubeNode(engine, size = Float3(5f, 2.44f, 0.004f), materialInstance = mats.wallShade).apply {
                position = toF3(base + n * 0.003); rotation = Float3(0f, yaw, 0f); isTouchable = false
            }
            val studBack = n * -(0.0127 + 0.0445)
            for (k in -6..6) {
                nodes += CubeNode(engine, size = Float3(0.038f, 2.44f, 0.089f), materialInstance = mats.stud).apply {
                    position = toF3(base + along * (k * JobBuild.STUD_OC_M) + studBack); rotation = Float3(0f, yaw, 0f); isTouchable = false
                }
            }
            listOf(floor + 0.019, floor + 2.44 - 0.019).forEach { py ->
                nodes += CubeNode(engine, size = Float3(5f, 0.038f, 0.089f), materialInstance = mats.stud).apply {
                    position = toF3(V3(w.stud.x, py, w.stud.z) + studBack); rotation = Float3(0f, yaw, 0f); isTouchable = false
                }
            }
        }
        val byId = parts.associateBy { it.id }
        runs.forEach { r ->
            val a = byId[r.from] ?: return@forEach; val b = byId[r.to] ?: return@forEach
            val t = if (r.type.conduit) 0.018f else 0.012f
            JobBuild.path(a, b).zipWithNext().forEach { (p, q) ->
                val d = q - p; val len = d.length.toFloat()
                if (len < 0.005f) return@forEach
                val vertical = abs(d.y) > kotlin.math.hypot(d.x, d.z)
                nodes += CubeNode(engine, size = if (vertical) Float3(t, len, t) else Float3(len, t, t), materialInstance = mats.run.getValue(r.type)).apply {
                    position = toF3((p + q) * 0.5)
                    if (!vertical) rotation = Float3(0f, yawFor(d), 0f)
                    isTouchable = false
                }
            }
        }
        parts.forEach { p ->
            val node: Node = if (p.type.ceiling)
                CylinderNode(engine, radius = 0.055f, height = 0.04f, materialInstance = mats.part.getValue(p.type)).apply { position = toF3(p.pos) }
            else CubeNode(engine, size = Float3(0.055f, 0.08f, 0.05f), materialInstance = mats.part.getValue(p.type)).apply {
                position = toF3(p.pos)
                w?.let { rotation = Float3(0f, yawFor(it.along), 0f) }
            }
            nodeToPart[node] = p.id
            nodes += node
            if (p.id == runStart) nodes += SphereNode(engine, radius = 0.03f, materialInstance = mats.start).apply {
                position = toF3(p.pos + V3(0.0, 0.07, 0.0)); isTouchable = false
            }
        }
    }

    fun nearestPart(pos: V3): Part? = parts.minByOrNull { (it.pos - pos).length }?.takeIf { (it.pos - pos).length < 0.3 }

    fun onTap(x: Float, y: Float, node: Node?) {
        val f = frameRef[0] ?: return
        val tapped = node?.let { nodeToPart[it] ?: it.parent?.let { pnt -> nodeToPart[pnt] } }
        val hit = runCatching { f.hitTest(x, y) }.getOrNull().orEmpty().firstOrNull { it.isValid(depthPoint = true, point = true) }
        val pos = hit?.hitPose?.let { V3(it.tx().toDouble(), it.ty().toDouble(), it.tz().toDouble()) }
        when (mode) {
            BuildMode.PLACE -> {
                if (pos == null) { hint = "Couldn't find the surface there. Move the phone a little and tap again."; return }
                parts += Part(nextId++, partType, pos); history += 'P'
                hint = "${partType.label} placed. Tap to add more, or switch to Run to connect them."
            }
            BuildMode.RUN -> {
                val target = tapped?.let { id -> parts.firstOrNull { it.id == id } } ?: pos?.let { nearestPart(it) }
                if (target == null) { hint = "Tap right on a box to connect it."; return }
                val start = runStart
                if (start == null || start == target.id) { runStart = target.id; hint = "Now tap the box this ${runType.label} goes to." }
                else {
                    runs += Run(start, target.id, runType); history += 'R'
                    runStart = target.id
                    hint = "Connected. Keep tapping to continue from here, or tap Done."
                }
            }
            BuildMode.STUD -> {
                val h = hit ?: run { hint = "Aim at the wall where you found a stud and tap the center of it."; return }
                val ny = h.hitPose.yAxis
                val normal = V3(ny[0].toDouble(), 0.0, ny[2].toDouble())
                if (normal.length < 0.6) { hint = "That looks like a floor or ceiling. Tap on the wall itself."; return }
                wall = Wall(pos!!, normal.norm()); history += 'W'; cutaway = true; mode = BuildMode.PLACE
                hint = "Studs drawn 16\" on center from that stud. Line one up with your stud finder; tap Mark stud again to move it."
            }
        }
    }

    fun undo() {
        when (history.removeLastOrNull()) {
            'R' -> runs.removeLastOrNull()
            'P' -> parts.removeLastOrNull()?.let { p -> runs.removeAll { it.from == p.id || it.to == p.id }; if (runStart == p.id) runStart = null }
            'W' -> wall = null
        }
    }

    fun savePhoto() {
        val surface = findSurface(hostView.rootView)
        if (surface == null || surface.width == 0 || Build.VERSION.SDK_INT < 29) { hint = "Couldn't take the picture on this phone."; return }
        val bmp = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surface, bmp, { result ->
            if (result != PixelCopy.SUCCESS) { hint = "Couldn't take the picture. Try again."; return@request }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Job build ${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Zimdars Electric")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            val ok = uri != null && runCatching { context.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }.isSuccess
            hint = if (ok) "Saved to your Gallery (Pictures › Zimdars Electric)." else "Couldn't save the picture."
        }, Handler(Looper.getMainLooper()))
    }

    val takeoff = JobBuild.takeoff(parts.toList(), runs.toList(), wall, oldWork)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            materialLoader = materialLoader,
            cameraNode = cameraNode,
            childNodes = nodes,
            planeRenderer = true,
            sessionConfiguration = { session, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                config.depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                config.lightEstimationMode = Config.LightEstimationMode.DISABLED
                config.focusMode = Config.FocusMode.AUTO
            },
            onSessionFailed = { e -> failed = e.message ?: "AR couldn't start." },
            onTrackingFailureChanged = { tracking = it },
            onSessionUpdated = { _, frame ->
                frameRef[0] = frame
                if (floorY == null) frame.getUpdatedPlanes()
                    .filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == com.google.ar.core.TrackingState.TRACKING }
                    .minOfOrNull { it.centerPose.ty().toDouble() }?.let { floorY = it }
            },
            onGestureListener = rememberOnGestureListener(onSingleTapConfirmed = { e, node -> onTap(e.x, e.y, node) }),
        )

        // ---- Top bar ----
        Column(Modifier.fillMaxWidth().background(Color(0xCC111315)).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(Z.raised, cut()).clickable { onBack() }.padding(horizontal = 14.dp, vertical = 8.dp)) { Text("←", color = Z.text, fontSize = 18.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Label("Build in camera", color = Z.neon)
                    val ft = takeoff.feetByRun.values.sum()
                    Text("${parts.size} boxes · ${runs.size} runs · ${qtyText(Math.round(ft).toDouble())} ft" + if (takeoff.holes > 0) " · ${takeoff.holes} holes" else "",
                        color = Z.text, fontSize = 15.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(failed?.let { "AR couldn't start: $it. Your phone needs Google Play Services for AR (play.google.com/store/apps/details?id=com.google.ar.core)." }
                ?: tracking?.let { trackingHelp(it) } ?: hint, color = if (failed != null || tracking != null) Z.warn else Z.muted, fontSize = 13.sp)
        }

        // ---- Bottom controls ----
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xE0111315)).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BuildMode.entries.forEach { m ->
                    Chip(m.label, selected = mode == m, modifier = Modifier.weight(1f)) {
                        mode = m
                        if (m != BuildMode.RUN) runStart = null
                        hint = when (m) {
                            BuildMode.PLACE -> "Pick what to place, then tap the wall or ceiling."
                            BuildMode.RUN -> "Pick the cable or conduit, then tap the first box."
                            BuildMode.STUD -> "Find a stud with your stud finder, then tap its center on the wall."
                        }
                    }
                }
                Chip("Undo", selected = false, enabled = history.isNotEmpty(), modifier = Modifier.weight(1f)) { undo() }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (mode) {
                    BuildMode.PLACE -> PartType.entries.forEach { t -> Chip(t.label, selected = partType == t, dot = partColor(t)) { partType = t } }
                    BuildMode.RUN -> {
                        RunType.entries.forEach { t -> Chip(t.label, selected = runType == t, dot = runColor(t)) { runType = t } }
                        if (runStart != null) Chip("Done", selected = false) { runStart = null; hint = "Run finished. Tap a box to start another." }
                    }
                    BuildMode.STUD -> Text("Studs are drawn 16\" on center from the one you mark. It's a planning view: the phone can't see inside the wall.",
                        color = Z.muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(if (cutaway) "Cutaway on" else "Cutaway off", selected = cutaway && wall != null, enabled = wall != null, modifier = Modifier.weight(1f)) { cutaway = !cutaway }
                Chip("Photo", selected = false, modifier = Modifier.weight(1f)) { savePhoto() }
                Chip("List (${takeoff.lines.size})", selected = true, enabled = takeoff.lines.isNotEmpty(), modifier = Modifier.weight(1.3f)) { showList = true }
            }
        }

        if (showList) TakeoffSheet(takeoff, oldWork, onOldWork = { oldWork = it }, onClose = { showList = false }) {
            vm.addBuildToQuote(quoteId, takeoff)
            showList = false
            onBack()
        }
    }
}

private class BuildMats(materialLoader: io.github.sceneview.loaders.MaterialLoader) {
    val part = PartType.entries.associateWith { materialLoader.createColorInstance(partColor(it)) }
    val run = RunType.entries.associateWith { materialLoader.createColorInstance(runColor(it)) }
    val wallShade = materialLoader.createColorInstance(Color(0f, 0.02f, 0.05f, 0.62f))
    val stud = materialLoader.createColorInstance(Color(0.85f, 0.68f, 0.42f, 0.9f))
    val start = materialLoader.createColorInstance(Color(0xFFDCFF34))
}

private fun trackingHelp(r: TrackingFailureReason) = when (r) {
    TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark. Turn on a light."
    TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast. Slow down."
    TrackingFailureReason.INSUFFICIENT_FEATURES -> "Plain walls are hard to see. Aim so a corner, outlet or the floor edge is in view."
    TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera is busy. Close other camera apps."
    else -> "Move the phone slowly so it can see the room."
}

private fun findSurface(v: View): SurfaceView? {
    if (v is SurfaceView) return v
    if (v is ViewGroup) for (i in 0 until v.childCount) findSurface(v.getChildAt(i))?.let { return it }
    return null
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, dot: Color? = null, onClick: () -> Unit) {
    Row(
        modifier.height(40.dp)
            .background(if (selected) Z.neonTint else Z.raised, cut(6.dp))
            .border(1.dp, if (selected) Z.neon else Z.line, cut(6.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (dot != null) { Box(Modifier.size(10.dp).background(dot)); Spacer(Modifier.width(6.dp)) }
        Text(text.uppercase(), color = if (!enabled) Z.line else if (selected) Z.neon else Z.text, fontSize = 11.sp, maxLines = 1, softWrap = false, fontFamily = Mono)
    }
}

@Composable
private fun TakeoffSheet(t: JobBuild.Takeoff, oldWork: Boolean, onOldWork: (Boolean) -> Unit, onClose: () -> Unit, onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Z.bg).padding(horizontal = 22.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(Z.raised, cut()).clickable { onClose() }.padding(horizontal = 14.dp, vertical = 10.dp)) { Text("←", color = Z.text, fontSize = 18.sp) }
            Spacer(Modifier.width(14.dp))
            Column {
                Label("From the camera", color = Z.info)
                Text("MATERIAL LIST", fontFamily = Condensed, fontSize = 24.sp, color = Z.text)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionHeader("01", "Walls")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Closed (old work)", selected = oldWork, modifier = Modifier.weight(1f)) { onOldWork(true) }
                Chip("Open framing", selected = !oldWork, modifier = Modifier.weight(1f)) { onOldWork(false) }
            }
            if (t.feetByRun.isNotEmpty()) {
                SectionHeader("02", "Runs measured")
                t.feetByRun.forEach { (type, ft) -> Text("${type.label}: ${qtyText(Math.round(ft * 10) / 10.0)} ft", color = Z.text, fontSize = 15.sp) }
                Text("Includes 1 ft extra at each box.", color = Z.muted, fontSize = 12.sp)
            }
            SectionHeader("03", "Material", "${t.lines.size} items")
            t.lines.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name, color = Z.text, fontSize = 15.sp)
                        if (l.why.isNotBlank()) Text(l.why, color = Z.muted, fontSize = 11.sp)
                    }
                    Text("${qtyText(l.qty)} ${l.unit}", fontFamily = Mono, fontSize = 13.sp, color = Z.text)
                }
                Divider()
            }
            if (t.notes.isNotEmpty()) {
                SectionHeader("04", "Notes")
                t.notes.forEach { Text("• $it", color = Z.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
            }
            Spacer(Modifier.height(14.dp))
            Text("Estimates from the camera are close, not exact. Check lengths on site before you buy.", color = Z.muted, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
        }
        NeonButton("Add to quote", Modifier.fillMaxWidth(), small = "Prices from your stock") { onAdd() }
    }
}
