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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.viewinterop.AndroidView
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

internal enum class ShapeKind { CUBE, SPHERE, CYLINDER }
internal data class ModelObject(
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
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { fullScreen = false }) { Text("Exit focus") }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { fullScreen = false; toolsExpanded = true }) { Text("Show tools") }
                            }
                            ModelViewport(objects, selectedId, zoom,
                                { zoom = (zoom * it).coerceIn(.35f, 3.5f) },
                                { selectedId = it },
                                { id, dx, dy -> objects = objects.map { obj ->
                                    if (obj.id == id) obj.copy(x = obj.x + dx, y = obj.y + dy) else obj
                                } }, showHints = true)
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
                                }, { axis, amount ->
                                    objects = objects.map { o -> if (o.id != selected.id) o else when (axis) {
                                        0 -> o.copy(x = o.x + amount)
                                        1 -> o.copy(y = o.y + amount)
                                        else -> o.copy(z = (o.z + amount).coerceAtLeast(0f))
                                    } }
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
                        }, { axis, amount ->
                            objects = objects.map { o -> if (o.id != selected.id) o else when (axis) {
                                0 -> o.copy(x = o.x + amount)
                                1 -> o.copy(y = o.y + amount)
                                else -> o.copy(z = (o.z + amount).coerceAtLeast(0f))
                            } }
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
    if (compact) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LYD MODEL", color = Color.White, fontWeight = FontWeight.Black, fontSize = 19.sp, maxLines = 1)
                    Text("TOUCH 3D STUDIO", color = Color(0xFF55D6BE), fontSize = 8.sp, letterSpacing = 1.5.sp)
                }
                Button(onClick = onExport, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(Icons.Default.FileDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp)); Text("STL")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onToggleTools, contentPadding = PaddingValues(horizontal = 5.dp)) {
                    Text(if (toolsExpanded) "Hide tools" else "Show tools", maxLines = 1)
                }
                TextButton(onClick = onToggleFullscreen, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("Focus") }
                IconButton(onClick = onUndo, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.Undo, "Undo", tint = Color.LightGray) }
                IconButton(onClick = onRedo, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.Redo, "Redo", tint = Color.LightGray) }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LYD MODEL", color = Color.White, fontWeight = FontWeight.Black, fontSize = 19.sp, maxLines = 1)
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
}

@Composable
private fun ModelViewport(
    objects: List<ModelObject>, selectedId: Long?, zoom: Float,
    onZoom: (Float) -> Unit, onSelect: (Long?) -> Unit, onMove: (Long, Float, Float) -> Unit,
    showHints: Boolean = true
) {
    var cameraPreset by remember { mutableIntStateOf(6) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                ModelGLSurfaceView(context, onSelect, onMove, onZoom).also {
                    it.updateScene(objects, selectedId, cameraPreset, zoom)
                }
            },
            update = { view -> view.updateScene(objects, selectedId, cameraPreset, zoom) }
        )
        Row(Modifier.align(Alignment.TopStart).padding(8.dp)
            .horizontalScroll(rememberScrollState())
            .background(Color(0xDD182331), RoundedCornerShape(12.dp)).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { cameraPreset = 0 }) { Text("Top", color = Color.White, fontSize = 10.sp) }
            TextButton(onClick = { cameraPreset = 1 }) { Text("Front", color = Color.White, fontSize = 10.sp) }
            TextButton(onClick = { cameraPreset = 2 }) { Text("Back", color = Color.White, fontSize = 10.sp) }
            TextButton(onClick = { cameraPreset = 3 }) { Text("Right", color = Color.White, fontSize = 10.sp) }
            TextButton(onClick = { cameraPreset = 4 }) { Text("Left", color = Color.White, fontSize = 10.sp) }
            TextButton(onClick = { cameraPreset = 5 }) { Text("Bottom", color = Color.White, fontSize = 10.sp) }
            TextButton(onClick = { cameraPreset = 6 }) { Text("Home", color = Color(0xFF55D6BE), fontSize = 10.sp) }
        }
        if (showHints) {
            Text("Drag empty space to orbit  •  Drag object to move  •  Pinch to zoom",
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 10.dp)
                    .background(Color(0xCC182331), RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                color = Color(0xFFB5C2D1), fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Inspector(obj: ModelObject, onSize: (Int, Float) -> Unit, onDelete: () -> Unit, onDuplicate: () -> Unit, onNudge: (Int, Float) -> Unit, compact: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
        .background(Color(0xFF1A2532), RoundedCornerShape(16.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SELECTED  •  ${obj.kind.name}", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onDuplicate, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.ContentCopy, "Duplicate", tint = Color(0xFFB5C6DA)) }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF8B8B)) }
        }
        Text("SIZE  •  MILLIMETRES", color = Color(0xFF8EA0B5), fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DimensionControl("Width", obj.sx, Modifier.weight(1f)) { onSize(0, it) }
            DimensionControl("Depth", obj.sy, Modifier.weight(1f)) { onSize(1, it) }
            DimensionControl("Height", obj.sz, Modifier.weight(1f)) { onSize(2, it) }
        }
        Divider(color = Color(0xFF344252), modifier = Modifier.padding(vertical = 5.dp))
        Text("MOVE  •  1 MM NUDGE", color = Color(0xFF8EA0B5), fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("X" to 0, "Y" to 1, "Z" to 2).forEach { (axis, index) ->
                Text(axis, color = Color.White, fontSize = 11.sp)
                OutlinedButton(onClick = { onNudge(index, -1f) }, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)) { Text("−") }
                OutlinedButton(onClick = { onNudge(index, 1f) }, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)) { Text("+") }
            }
        }
        Text("Position: X ${obj.x.toInt()}  •  Y ${obj.y.toInt()}  •  Z ${obj.z.toInt()} mm",
            color = Color(0xFF9BA9BB), fontSize = 10.sp)
    }
}

@Composable
private fun DimensionControl(label: String, value: Float, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value % 1f == 0f) value.toInt().toString() else value.toString()) }
    Column(modifier) {
        Text("$label (mm)", color = Color(0xFF8EA0B5), fontSize = 10.sp)
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw.filter { it.isDigit() || it == '.' }.take(8)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { text.toFloatOrNull()?.takeIf { it in 0.1f..1000f }?.let(onChange) }),
        )
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
