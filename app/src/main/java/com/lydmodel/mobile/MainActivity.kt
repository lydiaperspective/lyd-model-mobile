package com.lydmodel.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
    val sx: Float = 20f, val sy: Float = 20f, val sz: Float = 20f,
    val name: String = kind.name.lowercase().replaceFirstChar { it.uppercase() },
    val visible: Boolean = true,
    val hollow: Boolean = false,
    val groupId: Long? = null,
    val mergeId: Long? = null
)

internal data class DimensionMarker(val axis: Int, val x: Float, val y: Float, val value: Float)

@Composable
private fun LydModelApp() {
    var objects by remember { mutableStateOf(listOf(ModelObject(1, ShapeKind.CUBE))) }
    var selectedId by remember { mutableStateOf<Long?>(1) }
    var checkedIds by remember { mutableStateOf(setOf<Long>()) }
    var nextId by remember { mutableLongStateOf(2L) }
    var nextGroupId by remember { mutableLongStateOf(1L) }
    var nextMergeId by remember { mutableLongStateOf(1L) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var status by remember { mutableStateOf("Ready to design") }
    var toolsExpanded by remember { mutableStateOf(true) }
    var penMode by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    var panelWidth by remember { mutableFloatStateOf(290f) }
    var workplaneWidth by remember { mutableFloatStateOf(200f) }
    var workplaneDepth by remember { mutableFloatStateOf(200f) }
    var gridSnap by remember { mutableFloatStateOf(1f) }
    var resizeStep by remember { mutableFloatStateOf(5f) }
    var showSettings by remember { mutableStateOf(false) }
    var cameraRequest by remember { mutableStateOf<CameraRequest?>(null) }
    var nextCameraRequest by remember { mutableLongStateOf(0L) }
    val requestCamera: (Long?) -> Unit = { shapeId ->
        cameraRequest = CameraRequest(++nextCameraRequest, shapeId)
    }
    val context = LocalContext.current
    val selected = objects.firstOrNull { it.id == selectedId }
    val moveRemainders = remember { mutableMapOf<Long, FloatArray>() }
    val resizeRemainders = remember { mutableMapOf<Long, FloatArray>() }
    val moveShape: (Long, Float, Float, Float) -> Unit = { id, dx, dy, dz ->
        val remainder = moveRemainders.getOrPut(id) { floatArrayOf(0f, 0f, 0f) }
        objects = moveShapeSet(objects, id, dx, dy, dz, gridSnap, remainder)
    }
    val resizeShape: (Long, Int, Float, Float, Float) -> Unit = { id, handle, dx, dy, dz ->
        val remainder = resizeRemainders.getOrPut(id) { floatArrayOf(0f, 0f, 0f) }
        objects = resizeShapeSet(objects, id, handle, dx, dy, dz, resizeStep, remainder)
    }
    val shapeAction: (String, List<Long>) -> Unit = { action, ids ->
        when (action) {
            "Group" -> if (ids.size > 1) { val gid = nextGroupId++; objects = objects.map { if (it.id in ids) it.copy(groupId = gid) else it } }
            "Ungroup" -> { val groups = objects.filter { it.id in ids }.mapNotNull { it.groupId }.toSet(); objects = objects.map { if (it.groupId != null && it.groupId in groups) it.copy(groupId = null) else it } }
            "Merge" -> if (ids.size > 1) { val mid = nextMergeId++; objects = objects.map { if (it.id in ids) it.copy(mergeId = mid) else it } }
            "Unmerge" -> { val merges = objects.filter { it.id in ids }.mapNotNull { it.mergeId }.toSet(); objects = objects.map { if (it.mergeId != null && it.mergeId in merges) it.copy(mergeId = null) else it } }
            "Toggle solid / hole" -> objects = objects.map { if (it.id in ids) it.copy(hollow = !it.hollow) else it }
        }
    }
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
                                IconButton(onClick = { showSettings = true }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Default.Settings, "Settings", tint = Color.White)
                                }
                                Button(onClick = { fullScreen = false; toolsExpanded = true }) { Text("Show tools") }
                            }
                            ModelViewport(objects, selectedId, zoom, penMode,
                                { zoom = (zoom * it).coerceIn(.35f, 3.5f) },
                                { selectedId = it },
                                moveShape, resizeShape, showHints = true, cameraRequest = cameraRequest,
                                workplaneWidth = workplaneWidth, workplaneDepth = workplaneDepth,
                                onCameraRequest = requestCamera,
                                onEditDimension = { id, axis, value ->
                                    resizeRemainders.remove(id)
                                    objects = setShapeDimension(objects, id, axis, value)
                                })
                        }
                    } else {
                    TopBar(
                        onExport = { exportLauncher.launch("lyd-model.stl") },
                        onOpenSettings = { showSettings = true },
                        compact = compact,
                        toolsExpanded = toolsExpanded,
                        onToggleTools = { toolsExpanded = !toolsExpanded },
                        onToggleFullscreen = { fullScreen = true },
                        landscape = wideLayout,
                        penMode = penMode,
                        onTogglePenMode = { penMode = !penMode }
                    )
                    if (wideLayout) {
                        val density = LocalDensity.current.density
                        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                WorkspaceLabel(compact)
                                Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)
                                    .background(Color(0xFF151E29), RoundedCornerShape(22.dp))) {
                                    ModelViewport(objects, selectedId, zoom, penMode,
                                        { zoom = (zoom * it).coerceIn(.35f, 3.5f) },
                                        { selectedId = it },
                                        moveShape, resizeShape, cameraRequest = cameraRequest, workplaneWidth = workplaneWidth,
                                workplaneDepth = workplaneDepth, onCameraRequest = requestCamera,
                                onEditDimension = { id, axis, value ->
                                    resizeRemainders.remove(id)
                                    objects = setShapeDimension(objects, id, axis, value)
                                })
                                }
                            }
                            if (toolsExpanded) {
                            Box(Modifier.width(24.dp).fillMaxHeight().pointerInput(density, panelWidth) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    panelWidth = (panelWidth - drag.x / density).coerceIn(210f, availableWidth.value * .55f)
                                }
                            }, contentAlignment = Alignment.Center) {
                                Box(Modifier.width(4.dp).height(56.dp).background(Color(0xFF536579), RoundedCornerShape(4.dp)))
                            }
                            Column(Modifier.width(panelWidth.dp.coerceIn(210.dp, availableWidth * .55f))
                                .fillMaxHeight().background(Color(0xFF1A2532), RoundedCornerShape(18.dp))
                                .verticalScroll(rememberScrollState()).padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("TOOLS", color = Color.White, fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { toolsExpanded = false }) { Text("Hide") }
                                }
                                if (toolsExpanded && selected != null) Inspector(selected, {
                                    objects = objects.filterNot { it.id == selected.id }
                                    selectedId = objects.lastOrNull()?.id
                                }, {
                                    val copy = selected.copy(id = nextId++, x = selected.x + 8f, y = selected.y + 8f,
                                        name = "${selected.name} copy")
                                    objects = objects + copy
                                    selectedId = copy.id
                                }, { axis, amount ->
                                    objects = moveRelated(objects, selected.id,
                                        if (axis == 0) amount else 0f, if (axis == 1) amount else 0f,
                                        if (axis == 2) amount else 0f)
                                }, compact = compact, onFocusShape = { requestCamera(selected.id) },
                                    onToggleHollow = { objects = objects.map { if (it.id == selected.id) it.copy(hollow = !it.hollow) else it } })
                                LayersPanel(objects, selectedId, { selectedId = it }, { id, visible ->
                                    objects = objects.map { if (it.id == id) it.copy(visible = visible) else it }
                                }, { id ->
                                    objects = objects.filterNot { it.id == id }
                                    if (selectedId == id) selectedId = objects.lastOrNull()?.id
                                }, onRename = { id, name -> objects = objects.map { if (it.id == id) it.copy(name = name) else it } }, onShapeAction = shapeAction)
                                ShapeTray(onAdd = { kind ->
                                    val obj = newShape(kind, objects, nextId++, objects.size)
                                    objects = objects + obj; selectedId = obj.id
                                    status = "${kind.name.lowercase().replaceFirstChar { it.uppercase() }} added"
                                }, compact = compact)
                                Text(status, color = Color(0xFF8292A6), fontSize = 11.sp,
                                    modifier = Modifier.padding(6.dp))
                            }
                            }
                        }
                    } else {
                        WorkspaceLabel(compact)
                        Box(Modifier.weight(if (toolsExpanded) .62f else 1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
                            .background(Color(0xFF151E29), RoundedCornerShape(22.dp))) {
                            ModelViewport(objects, selectedId, zoom, penMode,
                                { zoom = (zoom * it).coerceIn(.35f, 3.5f) },
                                { selectedId = it },
                                moveShape, resizeShape, cameraRequest = cameraRequest, workplaneWidth = workplaneWidth,
                                workplaneDepth = workplaneDepth, onCameraRequest = requestCamera,
                                onEditDimension = { id, axis, value ->
                                    resizeRemainders.remove(id)
                                    objects = setShapeDimension(objects, id, axis, value)
                                })
                        }
                        if (toolsExpanded) Column(Modifier.weight(.38f).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).padding(bottom = 4.dp)) {
                        if (selected != null) Inspector(selected, {
                            objects = objects.filterNot { it.id == selected.id }
                            selectedId = objects.lastOrNull()?.id
                        }, {
                            val copy = selected.copy(id = nextId++, x = selected.x + 8f, y = selected.y + 8f,
                                name = "${selected.name} copy")
                            objects = objects + copy; selectedId = copy.id
                        }, { axis, amount -> objects = moveRelated(objects, selected.id,
                            if (axis == 0) amount else 0f, if (axis == 1) amount else 0f, if (axis == 2) amount else 0f) },
                            compact = compact, onFocusShape = { requestCamera(selected.id) },
                            onToggleHollow = { objects = objects.map { if (it.id == selected.id) it.copy(hollow = !it.hollow) else it } })
                        LayersPanel(objects, selectedId, { selectedId = it }, { id, visible ->
                            objects = objects.map { if (it.id == id) it.copy(visible = visible) else it }
                        }, { id ->
                            objects = objects.filterNot { it.id == id }
                            if (selectedId == id) selectedId = objects.lastOrNull()?.id
                        }, onRename = { id, name -> objects = objects.map { if (it.id == id) it.copy(name = name) else it } }, onShapeAction = shapeAction)
                        ShapeTray(onAdd = { kind ->
                            val obj = newShape(kind, objects, nextId++, objects.size)
                            objects = objects + obj; selectedId = obj.id
                            status = kind.name.lowercase().replaceFirstChar { it.uppercase() } + " added"
                        }, compact = compact)
                        }
                        Text(status, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                            color = Color(0xFF8292A6), fontSize = 10.sp, maxLines = 1)
                    }
                    }
                    if (showSettings) SettingsDialog(workplaneWidth, workplaneDepth, gridSnap, resizeStep,
                        onDismiss = { showSettings = false },
                        onApply = { width, depth, snap, resize ->
                            workplaneWidth = width
                            workplaneDepth = depth
                            gridSnap = snap
                            resizeStep = resize
                            showSettings = false
                        })
                }
            }
        }
    }
}

private fun snapToStep(value: Float, step: Float): Float =
    if (step <= 0f) value else (value / step).roundToInt() * step

private fun resizeFromHandle(obj: ModelObject, handle: Int, dx: Float, dy: Float, dz: Float,
                             step: Float, remainder: FloatArray): ModelObject {
    val sxSign = when (handle) { 0,2,4,6,8 -> -1f; 1,3,5,7,9 -> 1f; else -> 0f }
    val sySign = when (handle) { 0,1,4,5,10 -> -1f; 2,3,6,7,11 -> 1f; else -> 0f }
    val szSign = when (handle) { in 0..3, 12 -> -1f; in 4..7, 13 -> 1f; else -> 0f }
    fun resized(current: Float, change: Float, axis: Int): Float {
        if (change == 0f) return current
        val raw = current + change + remainder[axis]
        val next = snapToStep(raw.coerceAtLeast(.1f), step).coerceAtLeast(.1f)
        remainder[axis] = if (raw <= .1f) 0f else raw - next
        return next
    }
    val nextX = resized(obj.sx, sxSign * dx, 0)
    val nextY = resized(obj.sy, sySign * dy, 1)
    val nextZ = resized(obj.sz, szSign * dz, 2)
    val actualX = nextX - obj.sx; val actualY = nextY - obj.sy; val actualZ = nextZ - obj.sz
    return obj.copy(sx=nextX, sy=nextY, sz=nextZ,
        x=obj.x + sxSign * actualX / 2f,
        y=obj.y + sySign * actualY / 2f,
        z=obj.z + if (szSign < 0f) -actualZ else 0f)
}

private fun relatedShapes(objects: List<ModelObject>, id: Long): List<ModelObject> {
    val shape = objects.firstOrNull { it.id == id } ?: return emptyList()
    return when {
        shape.groupId != null -> objects.filter { it.groupId == shape.groupId }
        shape.mergeId != null -> objects.filter { it.mergeId == shape.mergeId }
        else -> listOf(shape)
    }
}

private fun shapeBounds(shapes: List<ModelObject>): ModelObject? {
    if (shapes.isEmpty()) return null
    val minX = shapes.minOf { it.x - it.sx / 2f }; val maxX = shapes.maxOf { it.x + it.sx / 2f }
    val minY = shapes.minOf { it.y - it.sy / 2f }; val maxY = shapes.maxOf { it.y + it.sy / 2f }
    val minZ = shapes.minOf { it.z }; val maxZ = shapes.maxOf { it.z + it.sz }
    val first = shapes.first()
    return ModelObject(first.id, ShapeKind.CUBE, (minX + maxX) / 2f, (minY + maxY) / 2f,
        minZ, maxX - minX, maxY - minY, maxZ - minZ, name = first.name,
        groupId = first.groupId, mergeId = first.mergeId)
}

private fun transformShapeSet(shapes: List<ModelObject>, oldBounds: ModelObject, newBounds: ModelObject): List<ModelObject> {
    val sxScale = newBounds.sx / oldBounds.sx.coerceAtLeast(.1f)
    val syScale = newBounds.sy / oldBounds.sy.coerceAtLeast(.1f)
    val szScale = newBounds.sz / oldBounds.sz.coerceAtLeast(.1f)
    return shapes.map { shape -> shape.copy(
        x = newBounds.x + (shape.x - oldBounds.x) * sxScale,
        y = newBounds.y + (shape.y - oldBounds.y) * syScale,
        z = newBounds.z + (shape.z - oldBounds.z) * szScale,
        sx = (shape.sx * sxScale).coerceAtLeast(.1f),
        sy = (shape.sy * syScale).coerceAtLeast(.1f),
        sz = (shape.sz * szScale).coerceAtLeast(.1f)
    ) }
}

private fun resizeShapeSet(objects: List<ModelObject>, id: Long, handle: Int,
                           dx: Float, dy: Float, dz: Float, step: Float, remainder: FloatArray): List<ModelObject> {
    val shapes = relatedShapes(objects, id)
    val bounds = shapeBounds(shapes) ?: return objects
    val resizedBounds = resizeFromHandle(bounds, handle, dx, dy, dz, step, remainder)
    val resized = transformShapeSet(shapes, bounds, resizedBounds).associateBy { it.id }
    return objects.map { resized[it.id] ?: it }
}

private fun setShapeDimension(objects: List<ModelObject>, id: Long, axis: Int, value: Float): List<ModelObject> {
    val shapes = relatedShapes(objects, id)
    val bounds = shapeBounds(shapes) ?: return objects
    val adjusted = when (axis) {
        0 -> bounds.copy(sx = value)
        1 -> bounds.copy(sy = value)
        else -> bounds.copy(sz = value)
    }
    val resized = transformShapeSet(shapes, bounds, adjusted).associateBy { it.id }
    return objects.map { resized[it.id] ?: it }
}

private fun moveShapeSet(objects: List<ModelObject>, id: Long, dx: Float, dy: Float, dz: Float,
                         snap: Float, remainder: FloatArray): List<ModelObject> {
    val shapes = relatedShapes(objects, id)
    val bounds = shapeBounds(shapes) ?: return objects
    val rawX = bounds.x + dx + remainder[0]
    val rawY = bounds.y + dy + remainder[1]
    val rawZ = bounds.z + dz + remainder[2]
    val nextX = snapToStep(rawX, snap); val nextY = snapToStep(rawY, snap); val nextZ = snapToStep(rawZ, snap)
    remainder[0] = rawX - nextX; remainder[1] = rawY - nextY; remainder[2] = rawZ - nextZ
    val offsetX = nextX - bounds.x; val offsetY = nextY - bounds.y; val offsetZ = nextZ - bounds.z
    val ids = shapes.map { it.id }.toSet()
    return objects.map { if (it.id in ids) it.copy(x = it.x + offsetX, y = it.y + offsetY, z = it.z + offsetZ) else it }
}

private fun moveRelated(objects: List<ModelObject>, id: Long, dx: Float, dy: Float, dz: Float): List<ModelObject> {
    val ids = relatedShapes(objects, id).map { it.id }.toSet()
    return objects.map { if (it.id in ids) it.copy(x = it.x + dx, y = it.y + dy, z = (it.z + dz).coerceAtLeast(0f)) else it }
}

private fun newShape(kind: ShapeKind, objects: List<ModelObject>, id: Long, index: Int): ModelObject {
    val ordinal = objects.count { it.kind == kind } + 1
    return ModelObject(id, kind, x = (index % 4) * 5f,
        name = "${kind.name.lowercase().replaceFirstChar { it.uppercase() }} $ordinal")
}

@Composable
private fun LayersPanel(
    objects: List<ModelObject>, selectedId: Long?, onSelect: (Long) -> Unit,
    onVisibilityChange: (Long, Boolean) -> Unit, onDelete: (Long) -> Unit,
    onRename: (Long, String) -> Unit = { _, _ -> }, onShapeAction: (String, List<Long>) -> Unit = { _, _ -> }
) {
    var checked by remember { mutableStateOf(setOf<Long>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
        .background(Color(0xFF1A2532), RoundedCornerShape(16.dp)).padding(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("SHAPES", color = Color(0xFF8EA0B5), fontSize = 10.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
            Text("${objects.size}", color = Color(0xFF8292A6), fontSize = 10.sp)
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.MoreVert, "Shape actions", tint = Color(0xFFB5C6DA))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    listOf("Group", "Ungroup", "Merge", "Unmerge", "Toggle solid / hole", "Rename selected").forEach { action ->
                        DropdownMenuItem(text = { Text(action) }, onClick = {
                            menuOpen = false
                            if (action == "Rename selected") {
                                renameId = selectedId
                                renameText = objects.firstOrNull { it.id == selectedId }?.name.orEmpty()
                            } else onShapeAction(action, checked.ifEmpty { setOfNotNull(selectedId) }.toList())
                        })
                    }
                }
            }
        }
        if (objects.isEmpty()) {
            Text("Add a shape to get started", color = Color(0xFF8292A6), fontSize = 11.sp,
                modifier = Modifier.padding(6.dp))
        } else {
            Column(Modifier.heightIn(max = 210.dp).verticalScroll(rememberScrollState())) {
                objects.asReversed().forEach { shape ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .background(if (shape.id == selectedId) Color(0xFF293B4C) else Color.Transparent,
                            RoundedCornerShape(10.dp))
                        .clickable { onSelect(shape.id) }
                        .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = shape.id in checked, onCheckedChange = { isChecked ->
                            checked = if (isChecked) checked + shape.id else checked - shape.id
                        }, modifier = Modifier.size(38.dp))
                        Box(Modifier.size(25.dp).background(Color(0xFF34485C), RoundedCornerShape(7.dp)),
                            contentAlignment = Alignment.Center) {
                            Text(shape.name.firstOrNull()?.uppercase() ?: "S", color = Color(0xFF55D6BE),
                                fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(shape.name, color = if (shape.visible) Color.White else Color(0xFF8292A6),
                                fontSize = 12.sp, maxLines = 1)
                            Text(shape.kind.name.lowercase().replaceFirstChar { it.uppercase() },
                                color = Color(0xFF8292A6), fontSize = 9.sp, maxLines = 1)
                        }
                        if (shape.hollow) Text("HOLE", color = Color(0xFFFFB86B), fontSize = 8.sp,
                            modifier = Modifier.padding(end = 2.dp))
                        IconButton(onClick = { onVisibilityChange(shape.id, !shape.visible) },
                            modifier = Modifier.size(34.dp)) {
                            Icon(if (shape.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                if (shape.visible) "Hide ${shape.name}" else "Show ${shape.name}",
                                tint = Color(0xFFB5C6DA), modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onDelete(shape.id) }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.Delete, "Delete ${shape.name}", tint = Color(0xFF8292A6),
                                modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }
        }
    }
    if (renameId != null) AlertDialog(onDismissRequest = { renameId = null },
        title = { Text("Rename shape") },
        text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true,
            label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { renameId?.let { onRename(it, renameText.trim().ifBlank { "Shape" }) }; renameId = null }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { renameId = null }) { Text("Cancel") } })
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
    onExport: () -> Unit, onOpenSettings: () -> Unit,
    compact: Boolean = false, toolsExpanded: Boolean, onToggleTools: () -> Unit,
    onToggleFullscreen: () -> Unit, landscape: Boolean = false,
    penMode: Boolean = false, onTogglePenMode: () -> Unit = {}
) {
    if (landscape) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("LYD MODEL", color = Color.White, fontWeight = FontWeight.Black,
                    fontSize = 17.sp, maxLines = 1)
                Text("TOUCH 3D STUDIO", color = Color(0xFF55D6BE),
                    fontSize = 7.sp, letterSpacing = 1.3.sp, maxLines = 1)
            }
            TextButton(onClick = onToggleTools, contentPadding = PaddingValues(horizontal = 7.dp)) {
                Text(if (toolsExpanded) "Hide tools" else "Show tools", maxLines = 1, fontSize = 12.sp)
            }
            TextButton(onClick = onTogglePenMode, contentPadding = PaddingValues(horizontal = 7.dp)) {
                Text(if (penMode) "Pen: ON" else "Pen: OFF", maxLines = 1, fontSize = 12.sp,
                    color = if (penMode) Color(0xFF55D6BE) else Color.LightGray)
            }
            TextButton(onClick = onToggleFullscreen, contentPadding = PaddingValues(horizontal = 7.dp)) {
                Text("Focus", fontSize = 12.sp)
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Default.Settings, "Settings", tint = Color.LightGray)
            }
            Button(onClick = onExport, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) {
                Icon(Icons.Default.FileDownload, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("STL", fontSize = 12.sp)
            }
        }
    } else if (compact) {
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
                TextButton(onClick = onTogglePenMode, contentPadding = PaddingValues(horizontal = 5.dp)) {
                    Text(if (penMode) "Pen ON" else "Pen OFF",
                        color = if (penMode) Color(0xFF55D6BE) else Color.LightGray)
                }
                TextButton(onClick = onToggleFullscreen, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("Focus") }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(42.dp)) { Icon(Icons.Default.Settings, "Settings", tint = Color.LightGray) }
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
            TextButton(onClick = onTogglePenMode) {
                Text(if (penMode) "Pen ON" else "Pen OFF",
                    color = if (penMode) Color(0xFF55D6BE) else Color.LightGray)
            }
            TextButton(onClick = onToggleFullscreen) { Text("Focus") }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(42.dp)) { Icon(Icons.Default.Settings, "Settings", tint = Color.LightGray) }
            Button(onClick = onExport, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Icon(Icons.Default.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("STL")
            }
        }
    }
}

@Composable
private fun ModelViewport(
    objects: List<ModelObject>, selectedId: Long?, zoom: Float, penMode: Boolean = false,
    onZoom: (Float) -> Unit, onSelect: (Long?) -> Unit, onMove: (Long, Float, Float, Float) -> Unit,
    onResize: (Long, Int, Float, Float, Float) -> Unit = { _, _, _, _, _ -> },
    showHints: Boolean = true,
    cameraRequest: CameraRequest? = null,
    workplaneWidth: Float = 200f,
    workplaneDepth: Float = 200f,
    onCameraRequest: (Long?) -> Unit = {},
    onEditDimension: (Long, Int, Float) -> Unit = { _, _, _ -> }
) {
    var cameraPreset by remember { mutableIntStateOf(6) }
    var dimensionMarkers by remember { mutableStateOf(emptyList<DimensionMarker>()) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var editingDimension by remember { mutableIntStateOf(-1) }
    var dimensionInput by remember { mutableStateOf("") }
    val selectedShape = objects.firstOrNull { it.id == selectedId }
    Box(Modifier.fillMaxSize().onSizeChanged { viewportSize = it }) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                ModelGLSurfaceView(context, onSelect, onMove, onResize, onZoom) { markers ->
                    dimensionMarkers = markers
                }.also {
                    it.updateScene(objects, selectedId, cameraPreset, zoom, penMode, cameraRequest,
                        workplaneWidth, workplaneDepth)
                }
            },
            update = { view -> view.updateScene(objects, selectedId, cameraPreset, zoom, penMode,
                cameraRequest, workplaneWidth, workplaneDepth) }
        )
        val density = androidx.compose.ui.platform.LocalDensity.current
        dimensionMarkers.forEach { marker ->
            val chipWidth = with(density) { 76.dp.roundToPx() }
            val chipHeight = with(density) { 44.dp.roundToPx() }
            val x = (marker.x.toInt() - chipWidth / 2).coerceIn(0,
                (viewportSize.width - chipWidth).coerceAtLeast(0))
            val y = (marker.y.toInt() - chipHeight / 2).coerceIn(0,
                (viewportSize.height - chipHeight).coerceAtLeast(0))
            Surface(Modifier.offset { IntOffset(x, y) }.clickable {
                editingDimension = marker.axis
                dimensionInput = String.format(Locale.US, "%.2f", marker.value)
            }, color = Color(0xF2263544), shape = RoundedCornerShape(9.dp), tonalElevation = 3.dp) {
                Box(Modifier.widthIn(min = 68.dp).heightIn(min = 44.dp).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center) {
                    Text(String.format(Locale.US, "%.2f", marker.value), color = Color.White,
                        fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (editingDimension >= 0 && selectedShape != null) {
            val axisLabel = listOf("Width", "Depth", "Height")[editingDimension.coerceIn(0, 2)]
            AlertDialog(onDismissRequest = { editingDimension = -1 },
                title = { Text("Set $axisLabel") },
                text = {
                    OutlinedTextField(value = dimensionInput, onValueChange = { raw ->
                        dimensionInput = raw.filter { it.isDigit() || it == '.' }.take(10)
                    }, label = { Text("$axisLabel (mm)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done))
                },
                confirmButton = {
                    TextButton(onClick = {
                        dimensionInput.toFloatOrNull()?.takeIf { it in .1f..10000f }?.let {
                            onEditDimension(selectedShape.id, editingDimension, it)
                            editingDimension = -1
                        }
                    }) { Text("Apply") }
                },
                dismissButton = { TextButton(onClick = { editingDimension = -1 }) { Text("Cancel") } })
        }
        var moreViews by remember { mutableStateOf(false) }
        Column(Modifier.align(Alignment.TopStart).padding(8.dp)
            .background(Color(0xDD182331), RoundedCornerShape(14.dp)).padding(5.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            CameraNavCube(onPreset = { cameraPreset = it })
            Box {
                IconButton(onClick = { moreViews = true }, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Default.MoreVert, "More camera views", tint = Color.White)
                }
                DropdownMenu(expanded = moreViews, onDismissRequest = { moreViews = false }) {
                    listOf("Back" to 2, "Left" to 4, "Bottom" to 5, "Home view" to 6).forEach { (label, preset) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            cameraPreset = preset
                            moreViews = false
                        })
                    }
                    DropdownMenuItem(text = { Text("Fit all shapes") }, onClick = {
                        onCameraRequest(null)
                        moreViews = false
                    })
                }
            }
        }
        if (showHints) {
            Text(if (penMode) "Touch: orbit / pinch zoom  •  Pen: select, move and resize" else "Drag empty space to orbit  •  Drag object to move  •  Pinch to zoom",
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 10.dp)
                    .background(Color(0xCC182331), RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                color = Color(0xFFB5C2D1), fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun CameraNavCube(onPreset: (Int) -> Unit) {
    Canvas(Modifier.size(82.dp).pointerInput(Unit) {
        detectTapGestures { point ->
            val middle = size.width / 2f
            val topBand = size.height * .47f
            onPreset(if (point.y < topBand) 0 else if (point.x < middle) 1 else 3)
        }
    }) {
        val w = size.width
        val h = size.height
        fun face(points: List<Offset>, color: Color) {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, color)
            drawPath(path, Color(0xFF8EA7BF), style = Stroke(width = 1.5.dp.toPx()))
        }
        face(listOf(Offset(w * .5f, h * .05f), Offset(w * .92f, h * .26f),
            Offset(w * .5f, h * .48f), Offset(w * .08f, h * .26f)), Color(0xFF506B84))
        face(listOf(Offset(w * .08f, h * .26f), Offset(w * .5f, h * .48f),
            Offset(w * .5f, h * .94f), Offset(w * .08f, h * .72f)), Color(0xFF31485E))
        face(listOf(Offset(w * .5f, h * .48f), Offset(w * .92f, h * .26f),
            Offset(w * .92f, h * .72f), Offset(w * .5f, h * .94f)), Color(0xFF3D586F))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 10.dp.toPx()
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD)
        }
        drawContext.canvas.nativeCanvas.apply {
            drawText("TOP", w * .5f, h * .29f, paint)
            drawText("FRONT", w * .29f, h * .66f, paint)
            drawText("RIGHT", w * .71f, h * .66f, paint)
        }
    }
}

@Composable
private fun SettingsDialog(
    workplaneWidth: Float, workplaneDepth: Float, gridSnap: Float, resizeStep: Float,
    onDismiss: () -> Unit, onApply: (Float, Float, Float, Float) -> Unit
) {
    var widthText by remember { mutableStateOf(workplaneWidth.toString()) }
    var depthText by remember { mutableStateOf(workplaneDepth.toString()) }
    var snapText by remember { mutableStateOf(gridSnap.toString()) }
    var resizeText by remember { mutableStateOf(resizeStep.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Workspace settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Workplane size (mm)", color = Color(0xFF8EA0B5), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(widthText, { widthText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Width") }, modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(depthText, { depthText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Depth") }, modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                OutlinedTextField(snapText, { snapText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Move snap (mm)") }, supportingText = { Text("Spacing used when moving shapes") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(resizeText, { resizeText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Resize step (mm)") }, supportingText = { Text("Shape sizes snap to this amount while resizing") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val width = widthText.toFloatOrNull()
                val depth = depthText.toFloatOrNull()
                val snap = snapText.toFloatOrNull()
                val resize = resizeText.toFloatOrNull()
                if (width == null || depth == null || snap == null || resize == null ||
                    width !in 20f..10000f || depth !in 20f..10000f || snap !in .1f..100f || resize !in .1f..100f) {
                    error = "Enter workplane sizes from 20–10000 mm and snap amounts from 0.1–100 mm."
                } else onApply(width, depth, snap, resize)
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun Inspector(obj: ModelObject, onDelete: () -> Unit, onDuplicate: () -> Unit, onNudge: (Int, Float) -> Unit, compact: Boolean = false, onFocusShape: () -> Unit = {}, onToggleHollow: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
        .background(Color(0xFF1A2532), RoundedCornerShape(16.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SELECTED  •  ${obj.kind.name}", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onFocusShape, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text("Focus", fontSize = 11.sp)
            }
            TextButton(onClick = onToggleHollow, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text(if (obj.hollow) "Make solid" else "Make hole", fontSize = 10.sp)
            }
            IconButton(onClick = onDuplicate, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.ContentCopy, "Duplicate", tint = Color(0xFFB5C6DA)) }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF8B8B)) }
        }
        Text("MOVE  •  1 MM NUDGE", color = Color(0xFF8EA0B5), fontSize = 10.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("X" to 0, "Y" to 1, "Z" to 2).forEach { (axis, index) ->
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(axis, color = Color.White, fontSize = 11.sp)
                    OutlinedButton(onClick = { onNudge(index, -1f) },
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) { Text("−") }
                    OutlinedButton(onClick = { onNudge(index, 1f) },
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) { Text("+") }
                }
            }
        }
        Text("Position: X ${obj.x.toInt()}  •  Y ${obj.y.toInt()}  •  Z ${obj.z.toInt()} mm",
            color = Color(0xFF9BA9BB), fontSize = 10.sp)
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

internal object StlExporter {
    fun export(objects: List<ModelObject>): String = buildString {
        appendLine("solid lyd_model")
        val merged = objects.filter { it.mergeId != null }.groupBy { it.mergeId }
        val facets = mutableListOf<Array<FloatArray>>()
        objects.filter { it.visible && it.mergeId == null && !it.hollow }.forEach { facets.addAll(triangles(it)) }
        merged.values.forEach { group -> facets.addAll(CsgBoolean.combine(group)) }
        facets.forEach { t ->
                val n = normal(t[0],t[1],t[2])
                appendLine(" facet normal ${fmt(n[0])} ${fmt(n[1])} ${fmt(n[2])}")
                appendLine("  outer loop")
                t.forEach { v -> appendLine("   vertex ${fmt(v[0])} ${fmt(v[1])} ${fmt(v[2])}") }
                appendLine("  endloop"); appendLine(" endfacet")
        }
        appendLine("endsolid lyd_model")
    }
    internal fun triangles(obj: ModelObject): List<Array<FloatArray>> =
        when(obj.kind) { ShapeKind.CUBE -> cube(obj); ShapeKind.SPHERE -> sphere(obj); ShapeKind.CYLINDER -> cylinder(obj) }
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
