package com.lydmodel.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
    val selected = objects.firstOrNull { it.id == selectedId }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/stl")) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(StlExporter.export(objects)) }
                ?: error("Could not open destination")
        }.onSuccess { status = "STL exported successfully" }
            .onFailure { status = "Export failed: ${it.localizedMessage}" }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF55D6BE), secondary = Color(0xFF8EA7FF),
        background = Color(0xFF10151D), surface = Color(0xFF1A222D))) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF10151D)) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopBar(onUndo = { status = "Undo history is planned" },
                    onRedo = { status = "Redo history is planned" },
                    onExport = { exportLauncher.launch("lyd-model.stl") })
                Text("UNTITLED DESIGN  •  MILLIMETRES", Modifier.padding(start = 18.dp, top = 12.dp),
                    color = Color(0xFF9BA9BB), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp)
                    .background(Color(0xFF151E29), RoundedCornerShape(22.dp))) {
                    ModelViewport(objects, selectedId, zoom, { zoom = (zoom * it).coerceIn(.45f, 2.8f) },
                        { selectedId = it }, { id, dx, dy ->
                            objects = objects.map { if (it.id == id) it.copy(x = it.x + dx, y = it.y + dy) else it }
                        })
                    Surface(Modifier.align(Alignment.TopStart).padding(12.dp),
                        color = Color(0xCC202B39), shape = RoundedCornerShape(10.dp)) {
                        Text("3D WORKSPACE", Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            color = Color(0xFFB8C7D9), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Drag to move  •  Pinch to zoom", Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        color = Color(0xFF8D9CAF), fontSize = 11.sp)
                }
                if (selected != null) Inspector(selected, { axis, value ->
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
                })
                ShapeTray { kind ->
                    val obj = ModelObject(nextId++, kind, x = (objects.size % 4) * 5f)
                    objects = objects + obj
                    selectedId = obj.id
                    status = "${kind.name.lowercase().replaceFirstChar { it.uppercase() }} added"
                }
                Text(status, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    color = Color(0xFF8292A6), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun TopBar(onUndo: () -> Unit, onRedo: () -> Unit, onExport: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("LYD MODEL", color = Color.White, fontWeight = FontWeight.Black, fontSize = 19.sp)
            Text("TOUCH 3D STUDIO", color = Color(0xFF55D6BE), fontSize = 9.sp, letterSpacing = 2.sp)
        }
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
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var dragId by remember { mutableStateOf<Long?>(null) }
    Canvas(Modifier.fillMaxSize()
        .pointerInput(objects, canvasSize, zoom) {
            detectTapGestures { point ->
                val hit = objects.asReversed().firstOrNull { obj ->
                    val p = project(obj.x, obj.y, obj.z, canvasSize, zoom)
                    (point - p).getDistance() < (max(obj.sx, obj.sy) * zoom * 1.5f).coerceAtLeast(30f)
                }
                onSelect(hit?.id)
            }
        }
        .pointerInput(selectedId, zoom) {
            detectDragGestures(onDragStart = { dragId = selectedId },
                onDragEnd = { dragId = null }, onDragCancel = { dragId = null }) { change, amount ->
                change.consume()
                dragId?.let { onMove(it, amount.x / (zoom * 1.4f), -amount.y / (zoom * 1.4f)) }
            }
        }
        .pointerInput(Unit) { detectTransformGestures { _, _, scale, _ -> onZoom(scale) } }
    ) {
        canvasSize = size
        val center = Offset(size.width / 2f, size.height * .56f)
        for (i in -12..12) {
            drawLine(Color(0xFF273444), project(i * 10f, -120f, 0f, size, zoom),
                project(i * 10f, 120f, 0f, size, zoom), 1f)
            drawLine(Color(0xFF273444), project(-120f, i * 10f, 0f, size, zoom),
                project(120f, i * 10f, 0f, size, zoom), 1f)
        }
        drawCircle(Color(0xFF55D6BE), 3f, center)
        objects.forEach { obj ->
            val p = project(obj.x, obj.y, obj.z, size, zoom)
            val w = obj.sx * zoom * 1.4f; val h = obj.sy * zoom * .72f; val depth = obj.sz * zoom * 1.15f
            val top = Path().apply { moveTo(p.x,p.y-depth); lineTo(p.x+w,p.y-depth-h*.5f)
                lineTo(p.x,p.y-depth-h); lineTo(p.x-w,p.y-depth-h*.5f); close() }
            val left = Path().apply { moveTo(p.x-w,p.y-depth-h*.5f); lineTo(p.x,p.y-depth-h)
                lineTo(p.x,p.y); lineTo(p.x-w,p.y-h*.5f); close() }
            val right = Path().apply { moveTo(p.x,p.y-depth-h); lineTo(p.x+w,p.y-depth-h*.5f)
                lineTo(p.x+w,p.y-h*.5f); lineTo(p.x,p.y); close() }
            val base = when(obj.kind) {
                ShapeKind.CUBE -> Color(0xFF648CEB); ShapeKind.SPHERE -> Color(0xFFB17AE8); ShapeKind.CYLINDER -> Color(0xFF50B9A6)
            }
            drawPath(left, base.copy(alpha = .72f)); drawPath(right, base.copy(alpha = .48f)); drawPath(top, base)
            if (obj.id == selectedId) {
                drawPath(top, Color(0xFF55D6BE), style = Stroke(3f))
                drawCircle(Color(0xFF55D6BE), 5f, Offset(p.x, p.y-depth-h))
            }
        }
    }
}

private fun project(x: Float, y: Float, z: Float, size: androidx.compose.ui.geometry.Size, zoom: Float): Offset {
    return Offset(size.width/2f + (x-y)*.9f*zoom, size.height*.56f + (x+y)*.42f*zoom - z*1.35f*zoom)
}

@Composable
private fun Inspector(obj: ModelObject, onSize: (Int, Float) -> Unit, onDelete: () -> Unit, onDuplicate: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
        .background(Color(0xFF1A2532), RoundedCornerShape(16.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SELECTED  •  ${obj.kind.name}", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onDuplicate, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.ContentCopy, "Duplicate", tint = Color(0xFFB5C6DA)) }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF8B8B)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DimensionControl("W", obj.sx) { onSize(0, it) }
            DimensionControl("D", obj.sy) { onSize(1, it) }
            DimensionControl("H", obj.sz) { onSize(2, it) }
        }
    }
}

@Composable
private fun DimensionControl(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.width(104.dp)) {
        Text(label, color = Color(0xFF8EA0B5), fontSize = 10.sp)
        Slider(value = value.coerceIn(2f, 100f), onValueChange = onChange, valueRange = 2f..100f)
        Text("${value.roundToInt()} mm", color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun ShapeTray(onAdd: (ShapeKind) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("ADD SHAPE", Modifier.padding(start = 18.dp, bottom = 8.dp), color = Color(0xFF9BA9BB),
            fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            items(ShapeKind.entries) { kind ->
                Surface(onClick = { onAdd(kind) }, color = Color(0xFF202C3A), shape = RoundedCornerShape(13.dp)) {
                    Row(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
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
