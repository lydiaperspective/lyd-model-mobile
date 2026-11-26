package com.lydmodel.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import java.util.Locale
import kotlin.math.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { LydModelApp() }
    }
}

private enum class ShapeKind { CUBE, SPHERE, CYLINDER }
private data class ModelObject(
    val id: Long, val kind: ShapeKind,
    val x: Float = 0f, val y: Float = 0f, val z: Float = 0f,
    val sx: Float = 20f, val sy: Float = 20f, val sz: Float = 20f
)

@Composable
private fun LydModelApp() {
    var objects by remember { mutableStateOf(listOf(ModelObject(1, ShapeKind.CUBE))) }
    var selectedId by remember { mutableStateOf<Long?>(1) }
    var nextId by remember { mutableLongStateOf(2L) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var status by remember { mutableStateOf("Ready to design") }
    var toolsExpanded by remember { mutableStateOf(true) }
    var fullScreen by remember { mutableStateOf(false) }
    var panelWidth by remember { mutableFloatStateOf(290f) }
    val context = LocalContext.current
    val selected = objects.firstOrNull { it.id == selectedId }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/stl")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(StlExporter.export(objects)) }
                ?: error("Could not open destination")
        }.onSuccess { status = "STL exported successfully" }
            .onFailure { status = "Export failed: ${it.localizedMessage}" }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF55D6BE), secondary = Color(0xFF8EA7FF),
        background = Color(0xFF10151D), surface = Color(0xFF1A222D))) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF10151D)) {
            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                val availableWidth = maxWidth
                val wideLayout = availableWidth >= 700.dp || availableWidth > maxHeight
                val compact = maxHeight < 520.dp
                Column(Modifier.fillMaxSize()) {
                    if (fullScreen) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            ModelViewport(objects, selectedId, zoom,
                                { zoom = (zoom * it).coerceIn(.35f, 3.5f) },
                                { selectedId = it },
                                { id, dx, dy -> objects = objects.map { obj ->
                                    if (obj.id == id) obj.copy(x = obj.x + dx, y = obj.y + dy) else obj
                                } })
                            Column(Modifier.align(Alignment.TopEnd).padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { fullScreen = false }) { Text("Exit focus") }
                                Button(onClick = { fullScreen = false; toolsExpanded = true }) { Text("Show tools") }
                            }
                            WorkspaceHints(compact, Modifier.align(Alignment.BottomCenter))
                        }
                    } else {
                    TopBar(
                        onUndo = { status = "Undo history is planned" },
                        onRedo = { status = "Redo history is planned" },
                        onExport = { exportLauncher.launch("lyd-model.stl") },
                        compact = compact,
                        toolsExpanded = toolsExpanded,
                        onToggleTools = { toolsExpanded = !toolsExpanded },
                        onToggleFullscreen = { fullScreen = true }
                    )
                    if (wideLayout) {
                        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                WorkspaceLabel(compact)
                                Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)
                                    .background(Color(0xFF151E29), RoundedCornerShape(22.dp))) {
                                    ModelViewport(objects, selectedId, zoom,
                                        { zoom = (zoom * it).coerceIn(.35f, 3.5f) },
                                        { selectedId = it },
                                        { id, dx, dy -> objects = objects.map {
                                            if (it.id == id) it.copy(x = it.x + dx, y = it.y + dy) else it
                                        } })
                                    WorkspaceHints(compact, Modifier.align(Alignment.BottomCenter))
                                }
                            }
                            if (toolsExpanded) {
                            Column(Modifier.width(panelWidth.dp.coerceAtMost(availableWidth * .48f))
                                .fillMaxHeight().background(Color(0xFF1A2532), RoundedCornerShape(18.dp))
                                .padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("TOOLS", color = Color.White, fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { toolsExpanded = false }) { Text("Hide") }
                                }
                                if (toolsExpanded && selected != null) Inspector(selected, { axis, value ->
                                    objects = objects.map { o -> if (o.id != selected.id) o else when(axis) {
                                        0 -> o.copy(sx = value); 1 -> o.copy(sy = value); else -> o.copy(sz = value)
                                    } }
                                }, {
                                    objects = objects.filterNot { it.id == selected.id }
                                    selectedId = objects.lastOrNull()?.id
                                }, {
                                    val copy = selected.copy(id = nextId++, x = selected.x + 8f, y = selected.y + 8f)
                                    objects = objects + copy
                                    selectedId = copy.id
                                }, compact = compact)
                                ShapeTray(onAdd = { kind ->
                                    val obj = ModelObject(nextId++, kind, x = (objects.size % 4) * 5f)
                                    objects = objects + obj; selectedId = obj.id
                                    status = "${kind.name.lowercase().replaceFirstChar { it.uppercase() }} added"
                                }, compact = compact)
                                Spacer(Modifier.weight(1f))
                                Text("PANEL WIDTH: " + panelWidth.roundToInt() + " dp",
                                    color = Color(0xFF9BA9BB), fontSize = 9.sp)
                                Slider(value = panelWidth, onValueChange = { panelWidth = it },
                                    valueRange = 220f..360f)
                                Text(status, color = Color(0xFF8292A6), fontSize = 11.sp,
                                    modifier = Modifier.padding(6.dp))
                            }
                            }
                        }
                    } else {
                        WorkspaceLabel(compact)
                        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
                            .background(Color(0xFF151E29), RoundedCornerShape(22.dp))) {
                            ModelViewport(objects, selectedId, zoom,
                                { zoom = (zoom * it).coerceIn(.35f, 3.5f) },
                                { selectedId = it },
                                { id, dx, dy -> objects = objects.map {
                                    if (it.id == id) it.copy(x = it.x + dx, y = it.y + dy) else it
                                } })
                            WorkspaceHints(compact, Modifier.align(Alignment.BottomCenter))
                        }
                        if (toolsExpanded && selected != null) Inspector(selected, { axis, value ->
                            objects = objects.map { o -> if (o.id != selected.id) o else when(axis) {
                                0 -> o.copy(sx = value); 1 -> o.copy(sy = value); else -> o.copy(sz = value)
                            } }
                        }, {
                            objects = objects.filterNot { it.id == selected.id }
                            selectedId = objects.lastOrNull()?.id
                        }, {
                            val copy = selected.copy(id = nextId++, x = selected.x + 8f, y = selected.y + 8f)
                            objects = objects + copy; selectedId = copy.id
                        }, compact = compact)
                        if (toolsExpanded) ShapeTray(onAdd = { kind ->
                            val obj = ModelObject(nextId++, kind, x = (objects.size % 4) * 5f)
                            objects = objects + obj; selectedId = obj.id
                            status = kind.name.lowercase().replaceFirstChar { it.uppercase() } + " added"
                        }, compact = compact)
                        Text(status, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                            color = Color(0xFF8292A6), fontSize = 10.sp, maxLines = 1)
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceLabel(compact: Boolean) {
    Text("UNTITLED DESIGN  •  MILLIMETRES",
        Modifier.padding(start = 8.dp, top = if (compact) 2.dp else 8.dp),
        color = Color(0xFF9BA9BB), fontSize = 10.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun WorkspaceHints(compact: Boolean, modifier: Modifier = Modifier) {
    Text(if (compact) "Drag to move • Pinch to zoom" else "Drag an object to move  •  Pinch to zoom",
        modifier.padding(10.dp), color = Color(0xFF8D9CAF), fontSize = 10.sp)
}

@Composable
private fun TopBar(
    onUndo: () -> Unit, onRedo: () -> Unit, onExport: () -> Unit,
    compact: Boolean = false, toolsExpanded: Boolean, onToggleTools: () -> Unit,
    onToggleFullscreen: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = if (compact) 3.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("LYD MODEL", color = Color.White, fontWeight = FontWeight.Black, fontSize = if (compact) 16.sp else 19.sp)
            Text("TOUCH 3D STUDIO", color = Color(0xFF55D6BE), fontSize = 8.sp, letterSpacing = 1.5.sp)
        }
        TextButton(onClick = onToggleTools) { Text(if (toolsExpanded) "Hide tools" else "Show tools") }
        TextButton(onClick = onToggleFullscreen) { Text("Focus") }
        IconButton(onClick = onUndo) { Icon(Icons.Default.Undo, "Undo", tint = Color.LightGray) }
        IconButton(onClick = onRedo) { Icon(Icons.Default.Redo, "Redo", tint = Color.LightGray) }
        Button(onClick = onExport, contentPadding = PaddingValues(horizontal = 14.dp)) {
            Icon(Icons.Default.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("STL")
        }
    }
}

@Composable
private fun ModelViewport(
    objects: List<ModelObject>, selectedId: Long?, zoom: Float,
    onZoom: (Float) -> Unit, onSelect: (Long?) -> Unit, onMove: (Long, Float, Float) -> Unit
) {
    var yaw by remember { mutableFloatStateOf(45f) }
    var pitch by remember { mutableFloatStateOf(32f) }
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var dragTarget by remember { mutableStateOf<Long?>(null) }
    var orbiting by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()
            .onSizeChanged { canvasSize = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(objects, canvasSize, zoom, yaw, pitch) {
                detectTapGestures { point ->
                    val hit = objects.asReversed().firstOrNull { obj ->
                        val p = project3d(obj.x, obj.y, obj.z + obj.sz / 2f, canvasSize, zoom, yaw, pitch)
                        (point - p).getDistance() < (max(obj.sx, obj.sy) * zoom * .9f).coerceAtLeast(24f)
                    }
                    onSelect(hit?.id)
                }
            }
            .pointerInput(objects, selectedId, zoom, yaw, pitch, canvasSize) {
                detectDragGestures(
                    onDragStart = { point ->
                        val hit = objects.asReversed().firstOrNull { obj ->
                            val p = project3d(obj.x, obj.y, obj.z + obj.sz / 2f, canvasSize, zoom, yaw, pitch)
                            (point - p).getDistance() < (max(obj.sx, obj.sy) * zoom * 1.1f).coerceAtLeast(28f)
                        }
                        dragTarget = hit?.id
                        orbiting = hit == null
                    },
                    onDragEnd = { dragTarget = null; orbiting = false },
                    onDragCancel = { dragTarget = null; orbiting = false }
                ) { change, amount ->
                    change.consume()
                    if (orbiting) {
                        yaw = (yaw + amount.x * .45f) % 360f
                        pitch = (pitch - amount.y * .35f).coerceIn(5f, 89f)
                    } else {
                        dragTarget?.let { id ->
                            val scale = (zoom * 1.15f).coerceAtLeast(.2f)
                            val a = Math.toRadians(yaw.toDouble())
                            val dx = (amount.x * cos(a) + amount.y * sin(a)) / scale
                            val dy = (-amount.x * sin(a) + amount.y * cos(a)) / scale
                            onMove(id, dx.toFloat(), dy.toFloat())
                        }
                    }
                }
            }
            .pointerInput(Unit) { detectTransformGestures { _, _, scale, _ -> onZoom(scale) } }
        ) {
            val center = Offset(size.width / 2f, size.height * .55f)
            for (i in -12..12) {
                drawLine(Color(0xFF273444),
                    project3d(i * 10f, -120f, 0f, size, zoom, yaw, pitch),
                    project3d(i * 10f, 120f, 0f, size, zoom, yaw, pitch), 1f)
                drawLine(Color(0xFF273444),
                    project3d(-120f, i * 10f, 0f, size, zoom, yaw, pitch),
                    project3d(120f, i * 10f, 0f, size, zoom, yaw, pitch), 1f)
            }
            drawCircle(Color(0xFF55D6BE), 3f, center)

            objects.forEach { obj ->
                val base = when (obj.kind) {
                    ShapeKind.CUBE -> Color(0xFF648CEB)
                    ShapeKind.SPHERE -> Color(0xFFB17AE8)
                    ShapeKind.CYLINDER -> Color(0xFF50B9A6)
                }
                val centerPoint = project3d(obj.x, obj.y, obj.z + obj.sz / 2f, size, zoom, yaw, pitch)
                val scale = perspectiveScale(obj.x, obj.y, obj.z + obj.sz / 2f, size, zoom, yaw, pitch)
                when (obj.kind) {
                    ShapeKind.CUBE -> {
                        val x0 = obj.x - obj.sx / 2f; val x1 = obj.x + obj.sx / 2f
                        val y0 = obj.y - obj.sy / 2f; val y1 = obj.y + obj.sy / 2f
                        val z0 = obj.z; val z1 = obj.z + obj.sz
                        val v = listOf(
                            floatArrayOf(x0,y0,z0), floatArrayOf(x1,y0,z0),
                            floatArrayOf(x1,y1,z0), floatArrayOf(x0,y1,z0),
                            floatArrayOf(x0,y0,z1), floatArrayOf(x1,y0,z1),
                            floatArrayOf(x1,y1,z1), floatArrayOf(x0,y1,z1)
                        )
                        val faces = listOf(
                            intArrayOf(0,1,2,3), intArrayOf(4,7,6,5),
                            intArrayOf(0,4,5,1), intArrayOf(1,5,6,2),
                            intArrayOf(2,6,7,3), intArrayOf(3,7,4,0)
                        )
                        faces.map { face ->
                            val depth = face.map { v[it] }.map {
                                cameraDepth(it[0],it[1],it[2],yaw,pitch)
                            }.average()
                            face to depth
                        }.sortedByDescending { it.second }.forEachIndexed { faceIndex, pair ->
                            val path = Path()
                            pair.first.forEachIndexed { index, vi ->
                                val q = project3d(v[vi][0],v[vi][1],v[vi][2],size,zoom,yaw,pitch)
                                if (index == 0) path.moveTo(q.x,q.y) else path.lineTo(q.x,q.y)
                            }
                            path.close()
                            val shade = when (faceIndex % 3) { 0 -> .48f; 1 -> .82f; else -> .64f }
                            drawPath(path, base.copy(alpha = shade))
                            if (obj.id == selectedId) drawPath(path, Color(0xFF55D6BE), style = Stroke(1.8f))
                        }
                    }
                    ShapeKind.SPHERE -> {
                        val radius = max(obj.sx, max(obj.sy, obj.sz)) * .5f * scale
                        drawCircle(base.copy(alpha = .9f), radius.coerceAtLeast(3f), centerPoint)
                        drawCircle(Color.White.copy(alpha = .22f), radius.coerceAtLeast(3f) * .72f,
                            centerPoint + Offset(-radius * .22f, -radius * .25f))
                        if (obj.id == selectedId) drawCircle(Color(0xFF55D6BE), radius.coerceAtLeast(3f),
                            centerPoint, style = Stroke(2f))
                    }
                    ShapeKind.CYLINDER -> {
                        val rx = obj.sx * .5f * scale
                        val ry = obj.sy * .5f * scale * max(.18f, sin(Math.toRadians(pitch.toDouble())).toFloat())
                        val top = project3d(obj.x,obj.y,obj.z+obj.sz,size,zoom,yaw,pitch)
                        val bottom = project3d(obj.x,obj.y,obj.z,size,zoom,yaw,pitch)
                        drawLine(base.copy(alpha = .72f), Offset(top.x-rx,top.y), Offset(bottom.x-rx,bottom.y), rx * .12f)
                        drawLine(base.copy(alpha = .72f), Offset(top.x+rx,top.y), Offset(bottom.x+rx,bottom.y), rx * .12f)
                        drawOval(base.copy(alpha = .55f), androidx.compose.ui.geometry.Offset(top.x-rx,top.y-ry),
                            androidx.compose.ui.geometry.Size(rx*2,ry*2))
                        drawOval(base, androidx.compose.ui.geometry.Offset(top.x-rx,top.y-ry),
                            androidx.compose.ui.geometry.Size(rx*2,ry*2), style = Stroke(if (obj.id == selectedId) 3f else 1f))
                        drawOval(base.copy(alpha = .4f), androidx.compose.ui.geometry.Offset(bottom.x-rx,bottom.y-ry),
                            androidx.compose.ui.geometry.Size(rx*2,ry*2), style = Stroke(1f))
                    }
                }
            }
        }

        Row(Modifier.align(Alignment.TopStart).padding(8.dp)
            .horizontalScroll(rememberScrollState())
            .background(Color(0xDD182331), RoundedCornerShape(12.dp)).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { yaw = 0f; pitch = 89f }) { Text("Top", color = Color.White, fontSize = 10.sp) }
            TextButton(onClick = { yaw = 0f; pitch = 8f }) { Text("Front", color = Color.White, fontSize = 10.sp) }
            TextButton(onClick = { yaw = 180f; pitch = 8f }) { Text("Back", color = Color.White, fontSize = 10.sp) }
            TextButton(onClick = { yaw = 90f; pitch = 8f }) { Text("Right", color = Color.White, fontSize = 10.sp) }
            TextButton(onClick = { yaw = 270f; pitch = 8f }) { Text("Left", color = Color.White, fontSize = 10.sp) }
            TextButton(onClick = { yaw = 0f; pitch = 5f }) { Text("Bottom", color = Color.White, fontSize = 10.sp) }
            TextButton(onClick = { yaw = 45f; pitch = 32f }) { Text("Home", color = Color(0xFF55D6BE), fontSize = 10.sp) }
        }
        Text("DRAG EMPTY SPACE TO ORBIT", Modifier.align(Alignment.BottomEnd).padding(10.dp),
            color = Color(0xFF8D9CAF), fontSize = 9.sp)
    }
}

private fun cameraDepth(x: Float, y: Float, z: Float, yaw: Float, pitch: Float): Float {
    val a = Math.toRadians(yaw.toDouble()); val p = Math.toRadians(pitch.toDouble())
    val depthAlongView = (x * sin(a) + y * cos(a)).toFloat()
    return depthAlongView * cos(p).toFloat() + z * sin(p).toFloat()
}

private fun perspectiveScale(
    x: Float, y: Float, z: Float, size: androidx.compose.ui.geometry.Size,
    zoom: Float, yaw: Float, pitch: Float
): Float {
    val focal = size.minDimension * 1.15f
    val distance = 520f
    return focal / (distance - cameraDepth(x,y,z,yaw,pitch)).coerceAtLeast(100f) * zoom
}

private fun project3d(
    x: Float, y: Float, z: Float, size: androidx.compose.ui.geometry.Size,
    zoom: Float, yaw: Float, pitch: Float
): Offset {
    val a = Math.toRadians(yaw.toDouble()); val p = Math.toRadians(pitch.toDouble())
    val right = (x * cos(a) - y * sin(a)).toFloat()
    val depth = (x * sin(a) + y * cos(a)).toFloat()
    val vertical = z * cos(p).toFloat() - depth * sin(p).toFloat()
    val scale = perspectiveScale(x,y,z,size,zoom,yaw,pitch)
    return Offset(size.width / 2f + right * scale, size.height * .55f - vertical * scale)
}

@Composable
private fun Inspector(obj: ModelObject, onSize: (Int, Float) -> Unit, onDelete: () -> Unit, onDuplicate: () -> Unit, compact: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
        .background(Color(0xFF1A2532), RoundedCornerShape(16.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SELECTED  •  ${obj.kind.name}", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onDuplicate, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.ContentCopy, "Duplicate", tint = Color(0xFFB5C6DA)) }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF8B8B)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DimensionControl("W", obj.sx, Modifier.weight(1f)) { onSize(0, it) }
            DimensionControl("D", obj.sy, Modifier.weight(1f)) { onSize(1, it) }
            DimensionControl("H", obj.sz, Modifier.weight(1f)) { onSize(2, it) }
        }
    }
}

@Composable
private fun DimensionControl(label: String, value: Float, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    Column(modifier) {
        Text(label, color = Color(0xFF8EA0B5), fontSize = 10.sp)
        Slider(value = value.coerceIn(2f, 100f), onValueChange = onChange, valueRange = 2f..100f)
        Text("${value.roundToInt()} mm", color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun ShapeTray(onAdd: (ShapeKind) -> Unit, compact: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("ADD SHAPE", Modifier.padding(start = 8.dp, bottom = if (compact) 3.dp else 8.dp), color = Color(0xFF9BA9BB),
            fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            items(ShapeKind.entries) { kind ->
                Surface(onClick = { onAdd(kind) }, color = Color(0xFF202C3A), shape = RoundedCornerShape(13.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, null, tint = Color(0xFF55D6BE), modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(kind.name.lowercase().replaceFirstChar { it.uppercase() }, color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private object StlExporter {
    fun export(objects: List<ModelObject>): String = buildString {
        appendLine("solid lyd_model")
        objects.forEach { obj ->
            val facets = when(obj.kind) { ShapeKind.CUBE -> cube(obj); ShapeKind.SPHERE -> sphere(obj); ShapeKind.CYLINDER -> cylinder(obj) }
            facets.forEach { t ->
                val n = normal(t[0],t[1],t[2])
                appendLine(" facet normal ${fmt(n[0])} ${fmt(n[1])} ${fmt(n[2])}")
                appendLine("  outer loop")
                t.forEach { v -> appendLine("   vertex ${fmt(v[0])} ${fmt(v[1])} ${fmt(v[2])}") }
                appendLine("  endloop"); appendLine(" endfacet")
            }
        }
        appendLine("endsolid lyd_model")
    }
    private fun fmt(v: Float) = String.format(Locale.US, "%.5f", v)
    private fun normal(a: FloatArray,b: FloatArray,c: FloatArray): FloatArray {
        val u=floatArrayOf(b[0]-a[0],b[1]-a[1],b[2]-a[2]); val v=floatArrayOf(c[0]-a[0],c[1]-a[1],c[2]-a[2])
        val x=u[1]*v[2]-u[2]*v[1]; val y=u[2]*v[0]-u[0]*v[2]; val z=u[0]*v[1]-u[1]*v[0]
        val len=sqrt(x*x+y*y+z*z).coerceAtLeast(.000001f)
        return floatArrayOf(x/len,y/len,z/len)
    }
    private fun tri(a: FloatArray,b: FloatArray,c: FloatArray)=arrayOf(a,b,c)
    private fun cube(o: ModelObject): List<Array<FloatArray>> {
        val x0=o.x-o.sx/2; val x1=o.x+o.sx/2; val y0=o.y-o.sy/2; val y1=o.y+o.sy/2; val z0=o.z; val z1=o.z+o.sz
        val v=listOf(floatArrayOf(x0,y0,z0),floatArrayOf(x1,y0,z0),floatArrayOf(x1,y1,z0),floatArrayOf(x0,y1,z0),
            floatArrayOf(x0,y0,z1),floatArrayOf(x1,y0,z1),floatArrayOf(x1,y1,z1),floatArrayOf(x0,y1,z1))
        val faces=listOf(intArrayOf(0,2,1,0,3,2),intArrayOf(4,5,6,4,6,7),intArrayOf(0,1,5,0,5,4),
            intArrayOf(1,2,6,1,6,5),intArrayOf(2,3,7,2,7,6),intArrayOf(3,0,4,3,4,7))
        return faces.flatMap { f -> (0..5 step 3).map { i -> tri(v[f[i]],v[f[i+1]],v[f[i+2]]) } }
    }
    private fun sphere(o: ModelObject): List<Array<FloatArray>> {
        val out=mutableListOf<Array<FloatArray>>(); val lat=12; val lon=24
        fun point(a:Int,b:Int):FloatArray {
            val phi=PI*a/lat; val theta=2*PI*b/lon
            return floatArrayOf(o.x+o.sx/2*cos(theta).toFloat()*sin(phi).toFloat(),o.y+o.sy/2*sin(theta).toFloat()*sin(phi).toFloat(),o.z+o.sz/2+o.sz/2*cos(phi).toFloat())
        }
        for(a in 0 until lat) for(b in 0 until lon) {
            val p=point(a,b); val q=point(a+1,b); val r=point(a+1,b+1); val s=point(a,b+1)
            out.add(tri(p,q,r)); out.add(tri(p,r,s))
        }
        return out
    }
    private fun cylinder(o: ModelObject): List<Array<FloatArray>> {
        val out=mutableListOf<Array<FloatArray>>(); val n=32
        val bottom=floatArrayOf(o.x,o.y,o.z); val top=floatArrayOf(o.x,o.y,o.z+o.sz)
        for(i in 0 until n) {
            val a=2*PI*i/n; val b=2*PI*(i+1)/n
            fun p(t:Double,z:Float)=floatArrayOf(o.x+o.sx/2*cos(t).toFloat(),o.y+o.sy/2*sin(t).toFloat(),z)
            val a0=p(a,o.z); val b0=p(b,o.z); val a1=p(a,o.z+o.sz); val b1=p(b,o.z+o.sz)
            out.add(tri(a0,b0,b1)); out.add(tri(a0,b1,a1)); out.add(tri(bottom,b0,a0)); out.add(tri(top,a1,b1))
        }
        return out
    }
}
