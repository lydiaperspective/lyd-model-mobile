package com.lydmodel.mobile

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/**
 * Hardware-accelerated 3D viewport. Geometry is rendered as triangles with a depth buffer;
 * camera orbit and zoom are applied to a real perspective projection.
 */
internal class ModelGLSurfaceView(
    context: Context,
    private val onSelect: (Long?) -> Unit,
    private val onMove: (Long, Float, Float, Float) -> Unit,
    private val onResize: (Long, Int, Float, Float, Float) -> Unit,
    private val onZoom: (Float) -> Unit
) : GLSurfaceView(context) {
    private val sceneRenderer = ModelSceneRenderer()
    private var previousX = 0f
    private var previousY = 0f
    private var activePointers = 0
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var touchedObject: Long? = null
    private var touchedResizeCorner: Int? = null
    private var touchedMoveAxis: Int? = null
    @Volatile private var penMode = false
    private var activeInputIsStylus = false
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val id = sceneRenderer.pick(e.x, e.y, width, height)
            if (id != null) {
                sceneRenderer.focusOn(id)
                post { onSelect(id) }
            }
            return id != null
        }
    })
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                sceneRenderer.zoomBy(detector.scaleFactor)
                onZoom(detector.scaleFactor)
                return true
            }
        })

    init {
        setEGLContextClientVersion(2)
        setRenderer(sceneRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    fun updateScene(objects: List<ModelObject>, selectedId: Long?, preset: Int, zoom: Float, penMode: Boolean) {
        sceneRenderer.updateScene(objects, selectedId, preset, zoom)
        this.penMode = penMode
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeInputIsStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                    event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                activePointers = 1
                previousX = event.x; previousY = event.y
                downX = event.x; downY = event.y
                moved = false
                val canEdit = !penMode || activeInputIsStylus
                touchedObject = if (canEdit) sceneRenderer.pick(event.x, event.y, width, height) else null
                touchedResizeCorner = if (canEdit && activeInputIsStylus)
                    sceneRenderer.pickResizeHandle(event.x, event.y, width, height) else null
                touchedMoveAxis = if (canEdit && activeInputIsStylus && touchedResizeCorner == null)
                    sceneRenderer.pickMoveAxis(event.x, event.y, width, height) else null
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointers = event.pointerCount
                val focus = pointerFocus(event)
                previousX = focus.first
                previousY = focus.second
                moved = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val focus = pointerFocus(event)
                    val panDx = focus.first - previousX
                    val panDy = focus.second - previousY
                    if (abs(panDx) + abs(panDy) > 1f) sceneRenderer.panBy(panDx, panDy, height)
                    previousX = focus.first
                    previousY = focus.second
                } else if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - previousX
                    val dy = event.y - previousY
                    if (abs(event.x - downX) + abs(event.y - downY) > 12f) moved = true
                    val objectId = touchedObject
                    if (penMode && !activeInputIsStylus) {
                        sceneRenderer.orbitBy(dx * .45f, dy * .38f)
                    } else if (objectId != null && touchedResizeCorner != null) {
                        val handle = touchedResizeCorner!!
                        if (handle in 8..13) {
                            // Face-center extrusion is locked to that face's single world axis.
                            val amount = sceneRenderer.faceExtrudeDelta(handle, dx, dy, width, height)
                            val delta = when (handle) {
                                8, 9 -> floatArrayOf(amount, 0f, 0f)
                                10, 11 -> floatArrayOf(0f, amount, 0f)
                                else -> floatArrayOf(0f, 0f, amount)
                            }
                            onResize(objectId, handle, delta[0], delta[1], delta[2])
                        } else {
                            // Corner handles retain their three-connected-face resize behavior.
                            val scale = 0.12f / sceneRenderer.currentZoom()
                            val world = sceneRenderer.screenDragToWorld(dx, dy)
                            val dz = -dy * scale
                            onResize(objectId, handle, world.first * 0.55f, world.second * 0.55f, dz)
                        }
                    } else if (objectId != null && touchedMoveAxis != null) {
                        val delta = sceneRenderer.axisDragDelta(touchedMoveAxis!!, dx, dy, width, height)
                        onMove(objectId, delta[0], delta[1], delta[2])
                    } else if (objectId != null) {
                        // Body dragging remains restricted to the workplane (XY); it never changes height.
                        val world = sceneRenderer.screenDragToWorld(dx, dy)
                        onMove(objectId, world.first, world.second, 0f)
                    } else if (penMode && activeInputIsStylus) {
                        // In Pen Mode, stylus input edits objects only; it never orbits the camera.
                    } else {
                        sceneRenderer.orbitBy(dx * .45f, dy * .38f)
                    }
                }
                previousX = event.x; previousY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                activePointers = event.pointerCount - 1
                if (activePointers == 1) {
                    val index = if (event.actionIndex == 0) 1 else 0
                    previousX = event.getX(index); previousY = event.getY(index)
                } else if (activePointers >= 2) {
                    val focus = pointerFocus(event, event.actionIndex)
                    previousX = focus.first; previousY = focus.second
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && !scaleDetector.isInProgress && (!penMode || activeInputIsStylus)) {
                    val picked = sceneRenderer.pick(event.x, event.y, width, height)
                    post { onSelect(picked) }
                }
                activePointers = 0
                touchedObject = null
                touchedResizeCorner = null
                touchedMoveAxis = null
                activeInputIsStylus = false
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { activePointers = 0; touchedObject = null; touchedResizeCorner = null; touchedMoveAxis = null; activeInputIsStylus = false }
        }
        return true
    }

    private fun pointerFocus(event: MotionEvent, skipIndex: Int = -1): Pair<Float, Float> {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            sumX += event.getX(i)
            sumY += event.getY(i)
            count++
        }
        return if (count == 0) event.x to event.y else sumX / count to sumY / count
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

private class ModelSceneRenderer : GLSurfaceView.Renderer {
    @Volatile private var objects: List<ModelObject> = emptyList()
    @Volatile private var selectedId: Long? = null
    @Volatile private var preset: Int = 6
    @Volatile private var zoom: Float = 1f
    @Volatile private var yaw = 45f
    @Volatile private var pitch = 32f
    private var program = 0
    private var positionHandle = 0
    private var normalHandle = 0
    private var mvpHandle = 0
    private var colorHandle = 0
    private var lightHandle = 0
    private var pointSizeHandle = 0
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private var width = 1
    private var height = 1
    private var cameraDistance = 340f
    @Volatile private var targetX = 0f
    @Volatile private var targetY = 0f
    @Volatile private var targetZ = 18f
    private val meshes = mutableMapOf<ShapeKind, GpuMesh>()
    private lateinit var grid: GpuMesh

    fun updateScene(items: List<ModelObject>, selected: Long?, viewPreset: Int, currentZoom: Float) {
        objects = items.toList()
        selectedId = selected
        if (preset != viewPreset) {
            preset = viewPreset
            when (viewPreset) {
                0 -> { yaw = 0f; pitch = 89f }
                1 -> { yaw = 0f; pitch = 0f }
                2 -> { yaw = 180f; pitch = 0f }
                3 -> { yaw = 90f; pitch = 0f }
                4 -> { yaw = 270f; pitch = 0f }
                5 -> { yaw = 0f; pitch = -89f }
                else -> { yaw = 45f; pitch = 32f }
            }
        }
        zoom = currentZoom.coerceIn(.35f, 3.5f)
    }

    fun panBy(screenDx: Float, screenDy: Float, viewHeight: Int) {
        if (viewHeight <= 0) return
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())
        val distance = cameraDistance / zoom
        val worldPerPixel = (2.0 * distance * tan(Math.toRadians(22.5)) / viewHeight).toFloat()
        val rightX = cos(yawRad).toFloat()
        val rightY = sin(yawRad).toFloat()
        val upX = (-sin(pitchRad) * sin(yawRad)).toFloat()
        val upY = (sin(pitchRad) * cos(yawRad)).toFloat()
        val upZ = cos(pitchRad).toFloat()
        targetX -= (rightX * screenDx + upX * -screenDy) * worldPerPixel
        targetY -= (rightY * screenDx + upY * -screenDy) * worldPerPixel
        targetZ -= upZ * -screenDy * worldPerPixel
    }

    fun focusOn(id: Long?): Boolean {
        val item = objects.firstOrNull { it.id == id } ?: return false
        targetX = item.x
        targetY = item.y
        targetZ = item.z + item.sz / 2f
        val extent = max(item.sx, max(item.sy, item.sz))
        cameraDistance = (extent * 3.4f).coerceIn(65f, 1200f)
        return true
    }

    fun orbitBy(dx: Float, dy: Float) {
        yaw = (yaw + dx) % 360f
        pitch = (pitch - dy).coerceIn(-89f, 89f)
    }

    fun screenDragToWorld(dx: Float, dy: Float): Pair<Float, Float> {
        val a = Math.toRadians(yaw.toDouble())
        val scale = 1.7f / zoom
        val wx = (dx * cos(a) - dy * sin(a)) * scale
        val wy = (-dx * sin(a) - dy * cos(a)) * scale
        return wx.toFloat() to wy.toFloat()
    }


    private fun projectPoint(point: FloatArray, viewWidth: Int, viewHeight: Int): FloatArray? {
        val eye = cameraEye()
        val look = FloatArray(16); val proj = FloatArray(16); val vp = FloatArray(16)
        val up = if (abs(pitch) > 85f) floatArrayOf(0f,1f,0f) else floatArrayOf(0f,0f,1f)
        Matrix.setLookAtM(look,0,eye[0],eye[1],eye[2],targetX,targetY,targetZ,up[0],up[1],up[2])
        Matrix.perspectiveM(proj,0,45f,viewWidth.toFloat()/viewHeight,.1f,5000f)
        Matrix.multiplyMM(vp,0,proj,0,look,0)
        val clip=FloatArray(4); Matrix.multiplyMV(clip,0,vp,0,floatArrayOf(point[0],point[1],point[2],1f),0)
        if (clip[3] <= 0f) return null
        return floatArrayOf((clip[0]/clip[3]+1f)*.5f*viewWidth,(1f-clip[1]/clip[3])*.5f*viewHeight)
    }

    fun pickMoveAxis(x: Float, y: Float, viewWidth: Int, viewHeight: Int): Int? {
        val item=objects.firstOrNull { it.id==selectedId } ?: return null
        if(viewWidth<=0 || viewHeight<=0) return null
        val cx=item.x; val cy=item.y; val cz=item.z+item.sz/2f
        val reach=max(12f,max(item.sx,max(item.sy,item.sz))*.9f)
        val origin=projectPoint(floatArrayOf(cx,cy,cz),viewWidth,viewHeight) ?: return null
        val ends=arrayOf(floatArrayOf(cx+reach,cy,cz),floatArrayOf(cx,cy+reach,cz),floatArrayOf(cx,cy,cz+reach))
        return ends.mapIndexedNotNull { axis,p ->
            val end=projectPoint(p,viewWidth,viewHeight) ?: return@mapIndexedNotNull null
            val vx=end[0]-origin[0]; val vy=end[1]-origin[1]
            val len2=vx*vx+vy*vy
            if(len2<100f) return@mapIndexedNotNull null
            val t=(((x-origin[0])*vx+(y-origin[1])*vy)/len2).coerceIn(0f,1f)
            val dist=hypot(x-(origin[0]+t*vx),y-(origin[1]+t*vy))
            if(t>.12f && dist<22f) axis to dist else null
        }.minByOrNull { it.second }?.first
    }

    fun axisDragDelta(axis: Int, dx: Float, dy: Float, viewWidth: Int, viewHeight: Int): FloatArray {
        val item=objects.firstOrNull { it.id==selectedId } ?: return floatArrayOf(0f,0f,0f)
        if(viewWidth<=0 || viewHeight<=0) return floatArrayOf(0f,0f,0f)
        val cx=item.x; val cy=item.y; val cz=item.z+item.sz/2f
        val reach=max(12f,max(item.sx,max(item.sy,item.sz))*.9f)
        val origin=projectPoint(floatArrayOf(cx,cy,cz),viewWidth,viewHeight) ?: return floatArrayOf(0f,0f,0f)
        val endPoint=when(axis) { 0->floatArrayOf(cx+reach,cy,cz); 1->floatArrayOf(cx,cy+reach,cz); else->floatArrayOf(cx,cy,cz+reach) }
        val end=projectPoint(endPoint,viewWidth,viewHeight) ?: return floatArrayOf(0f,0f,0f)
        val vx=end[0]-origin[0]; val vy=end[1]-origin[1]; val len2=vx*vx+vy*vy
        if(len2<100f) return floatArrayOf(0f,0f,0f)
        val amount=((dx*vx+dy*vy)/len2)*reach
        return when(axis) { 0->floatArrayOf(amount,0f,0f); 1->floatArrayOf(0f,amount,0f); else->floatArrayOf(0f,0f,amount) }
    }
    fun currentZoom(): Float = zoom

    /**
     * Converts pointer movement into signed movement along a face-center handle's
     * single world axis. Perpendicular finger/stylus drift is discarded.
     */
    fun faceExtrudeDelta(handle: Int, dx: Float, dy: Float, viewWidth: Int, viewHeight: Int): Float {
        val item = objects.firstOrNull { it.id == selectedId } ?: return 0f
        if (handle !in 8..13 || viewWidth <= 0 || viewHeight <= 0) return 0f

        val centerX = item.x
        val centerY = item.y
        val centerZ = item.z + item.sz / 2f
        val (origin, axis, direction) = when (handle) {
            8 -> Triple(floatArrayOf(item.x - item.sx / 2f, centerY, centerZ), 0, -1f)
            9 -> Triple(floatArrayOf(item.x + item.sx / 2f, centerY, centerZ), 0, 1f)
            10 -> Triple(floatArrayOf(centerX, item.y - item.sy / 2f, centerZ), 1, -1f)
            11 -> Triple(floatArrayOf(centerX, item.y + item.sy / 2f, centerZ), 1, 1f)
            12 -> Triple(floatArrayOf(centerX, centerY, item.z), 2, -1f)
            else -> Triple(floatArrayOf(centerX, centerY, item.z + item.sz), 2, 1f)
        }
        val worldStep = max(1f, max(item.sx, max(item.sy, item.sz)) * 0.25f)
        val end = origin.copyOf()
        end[axis] += direction * worldStep
        val p0 = projectPoint(origin, viewWidth, viewHeight) ?: return 0f
        val p1 = projectPoint(end, viewWidth, viewHeight) ?: return 0f
        val vx = p1[0] - p0[0]
        val vy = p1[1] - p0[1]
        val screenLengthSquared = vx * vx + vy * vy
        if (screenLengthSquared < 4f) return 0f
        // Dot product is the least-squares projection onto the permitted screen direction.
        val projectedPixels = (dx * vx + dy * vy) / sqrt(screenLengthSquared)
        return projectedPixels * worldStep / sqrt(screenLengthSquared) * direction
    }

    fun resizeSigns(handle: Int): FloatArray = when (handle) {
        in 0..7 -> floatArrayOf(if (handle and 1 == 0) -1f else 1f, if (handle and 2 == 0) -1f else 1f, if (handle and 4 == 0) -1f else 1f)
        8 -> floatArrayOf(-1f,0f,0f); 9 -> floatArrayOf(1f,0f,0f)
        10 -> floatArrayOf(0f,-1f,0f); 11 -> floatArrayOf(0f,1f,0f)
        12 -> floatArrayOf(0f,0f,-1f); else -> floatArrayOf(0f,0f,1f)
    }

    fun pickResizeHandle(x: Float, y: Float, viewWidth: Int, viewHeight: Int): Int? {
        val item = objects.firstOrNull { it.id == selectedId } ?: return null
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val x0=item.x-item.sx/2; val x1=item.x+item.sx/2
        val y0=item.y-item.sy/2; val y1=item.y+item.sy/2
        val z0=item.z; val z1=item.z+item.sz
        val handles = arrayOf(
            floatArrayOf(x0,y0,z0), floatArrayOf(x1,y0,z0), floatArrayOf(x0,y1,z0), floatArrayOf(x1,y1,z0),
            floatArrayOf(x0,y0,z1), floatArrayOf(x1,y0,z1), floatArrayOf(x0,y1,z1), floatArrayOf(x1,y1,z1),
            floatArrayOf(x0,item.y,item.z+item.sz/2), floatArrayOf(x1,item.y,item.z+item.sz/2),
            floatArrayOf(item.x,y0,item.z+item.sz/2), floatArrayOf(item.x,y1,item.z+item.sz/2),
            floatArrayOf(item.x,item.y,z0), floatArrayOf(item.x,item.y,z1)
        )
        val eye = cameraEye()
        val look = FloatArray(16); val proj = FloatArray(16); val vp = FloatArray(16)
        val up = if (abs(pitch) > 85f) floatArrayOf(0f,1f,0f) else floatArrayOf(0f,0f,1f)
        Matrix.setLookAtM(look,0,eye[0],eye[1],eye[2],targetX,targetY,targetZ,up[0],up[1],up[2])
        Matrix.perspectiveM(proj,0,45f,viewWidth.toFloat()/viewHeight,.1f,5000f)
        Matrix.multiplyMM(vp,0,proj,0,look,0)
        return handles.mapIndexedNotNull { index, p ->
            val clip=FloatArray(4); Matrix.multiplyMV(clip,0,vp,0,floatArrayOf(p[0],p[1],p[2],1f),0)
            if (clip[3] <= 0f) null else {
                val sx=(clip[0]/clip[3]+1f)*.5f*viewWidth
                val sy=(1f-clip[1]/clip[3])*.5f*viewHeight
                val distance=hypot(sx-x,sy-y)
                if (distance <= 29f) index to distance else null
            }
        }.minByOrNull { it.second }?.first
    }

    fun zoomBy(factor: Float) {
        zoom = (zoom * factor).coerceIn(.25f, 5f)
    }

    fun pick(x: Float, y: Float, viewWidth: Int, viewHeight: Int): Long? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val target = floatArrayOf(targetX, targetY, targetZ)
        val eye = cameraEye()
        val look = FloatArray(16)
        val proj = FloatArray(16)
        val vp = FloatArray(16)
        val up = if (abs(pitch) > 85f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(0f, 0f, 1f)
        Matrix.setLookAtM(look, 0, eye[0], eye[1], eye[2], target[0], target[1], target[2], up[0], up[1], up[2])
        Matrix.perspectiveM(proj, 0, 45f, viewWidth.toFloat() / viewHeight, .1f, 5000f)
        Matrix.multiplyMM(vp, 0, proj, 0, look, 0)
        return objects.asReversed().firstOrNull { item ->
            val point = floatArrayOf(item.x, item.y, item.z + item.sz / 2f, 1f)
            val clip = FloatArray(4)
            Matrix.multiplyMV(clip, 0, vp, 0, point, 0)
            if (clip[3] <= 0f) false else {
                val sx = (clip[0] / clip[3] + 1f) * .5f * viewWidth
                val sy = (1f - clip[1] / clip[3]) * .5f * viewHeight
                val radius = max(item.sx, max(item.sy, item.sz)) * 1.2f * zoom
                hypot(sx - x, sy - y) < max(28f, radius)
            }
        }?.id
    }

    private fun cameraEye(): FloatArray {
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())
        val distance = cameraDistance / zoom
        return floatArrayOf(
            targetX + (distance * cos(pitchRad) * sin(yawRad)).toFloat(),
            targetY + (-distance * cos(pitchRad) * cos(yawRad)).toFloat(),
            targetZ + (distance * sin(pitchRad)).toFloat()
        )
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(.075f, .095f, .125f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        normalHandle = GLES20.glGetAttribLocation(program, "aNormal")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
        colorHandle = GLES20.glGetUniformLocation(program, "uColor")
        lightHandle = GLES20.glGetUniformLocation(program, "uLight")
        pointSizeHandle = GLES20.glGetUniformLocation(program, "uPointSize")
        meshes[ShapeKind.CUBE] = createMesh(cubeVertices())
        meshes[ShapeKind.SPHERE] = createMesh(sphereVertices())
        meshes[ShapeKind.CYLINDER] = createMesh(cylinderVertices())
        grid = createMesh(gridVertices(), GLES20.GL_LINES)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
        Matrix.perspectiveM(projection, 0, 45f, width.toFloat() / height, .1f, 5000f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val eye = cameraEye()
        val up = if (abs(pitch) > 85f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(0f, 0f, 1f)
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], targetX, targetY, targetZ, up[0], up[1], up[2])
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
        GLES20.glUniform3f(lightHandle, -.4f, -.5f, 1f)

        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
        drawMesh(grid, floatArrayOf(.15f, .20f, .26f, 1f), false)

        objects.forEach { item ->
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, item.x, item.y, item.z + item.sz / 2f)
            Matrix.scaleM(model, 0, item.sx, item.sy, item.sz)
            Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
            val base = when (item.kind) {
                ShapeKind.CUBE -> floatArrayOf(.28f, .48f, .91f, 1f)
                ShapeKind.SPHERE -> floatArrayOf(.68f, .43f, .90f, 1f)
                ShapeKind.CYLINDER -> floatArrayOf(.24f, .72f, .63f, 1f)
            }
            if (item.id == selectedId) {
                base[0] = (base[0] * 1.18f).coerceAtMost(1f)
                base[1] = (base[1] * 1.18f).coerceAtMost(1f)
                base[2] = (base[2] * 1.18f).coerceAtMost(1f)
            }
            drawMesh(meshes[item.kind] ?: return@forEach, base, true)
            if (item.id == selectedId) drawSelectionHandles(item)
        }
    }

    private fun drawSelectionHandles(item: ModelObject) {
        val x0 = item.x - item.sx / 2f; val x1 = item.x + item.sx / 2f
        val y0 = item.y - item.sy / 2f; val y1 = item.y + item.sy / 2f
        val z0 = item.z; val z1 = item.z + item.sz
        val corners = listOf(
            floatArrayOf(x0,y0,z0), floatArrayOf(x1,y0,z0), floatArrayOf(x1,y1,z0), floatArrayOf(x0,y1,z0),
            floatArrayOf(x0,y0,z1), floatArrayOf(x1,y0,z1), floatArrayOf(x1,y1,z1), floatArrayOf(x0,y1,z1)
        )
        val edges = intArrayOf(0,1,1,2,2,3,3,0,4,5,5,6,6,7,7,4,0,4,1,5,2,6,3,7)
        val outline = mutableListOf<Float>()
        edges.toList().chunked(2).forEach { pair ->
            addVertex(outline, corners[pair[0]], floatArrayOf(0f,0f,1f))
            addVertex(outline, corners[pair[1]], floatArrayOf(0f,0f,1f))
        }
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glLineWidth(2.5f)
        drawMesh(createMesh(outline.toFloatArray(), GLES20.GL_LINES), floatArrayOf(.25f,.92f,.82f,1f), false)

        val dots = mutableListOf<Float>()
        corners.forEach { addVertex(dots, it, floatArrayOf(0f,0f,1f)) }
        val faceCenters = listOf(
            floatArrayOf(x0,item.y,z0+item.sz/2), floatArrayOf(x1,item.y,z0+item.sz/2),
            floatArrayOf(item.x,y0,z0+item.sz/2), floatArrayOf(item.x,y1,z0+item.sz/2),
            floatArrayOf(item.x,item.y,z0), floatArrayOf(item.x,item.y,z1)
        )
        faceCenters.forEach { addVertex(dots, it, floatArrayOf(0f,0f,1f)) }
        drawMesh(createMesh(dots.toFloatArray(), GLES20.GL_POINTS), floatArrayOf(.95f,.98f,1f,1f), false)

        val cx = item.x; val cy = item.y; val cz = item.z + item.sz / 2f
        val reach = max(12f, max(item.sx, max(item.sy, item.sz)) * .9f)
        fun axisLine(end: FloatArray, color: FloatArray) {
            val line = mutableListOf<Float>()
            addVertex(line, floatArrayOf(cx,cy,cz), floatArrayOf(0f,0f,1f))
            addVertex(line, end, floatArrayOf(0f,0f,1f))
            drawMesh(createMesh(line.toFloatArray(), GLES20.GL_LINES), color, false)
            val point = mutableListOf<Float>()
            addVertex(point, end, floatArrayOf(0f,0f,1f))
            drawMesh(createMesh(point.toFloatArray(), GLES20.GL_POINTS), color, false)
        }
        axisLine(floatArrayOf(cx + reach,cy,cz), floatArrayOf(1f,.28f,.28f,1f))
        axisLine(floatArrayOf(cx,cy + reach,cz), floatArrayOf(.30f,.90f,.42f,1f))
        axisLine(floatArrayOf(cx,cy,cz + reach), floatArrayOf(.30f,.58f,1f,1f))
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawMesh(mesh: GpuMesh, color: FloatArray, lit: Boolean) {
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glUniform1f(pointSizeHandle, if (mesh.mode == GLES20.GL_POINTS) 14f else 1f)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(normalHandle)
        mesh.vertices.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 24, mesh.vertices)
        mesh.vertices.position(3)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 24, mesh.vertices)
        if (!lit) GLES20.glDisable(GLES20.GL_CULL_FACE) else GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDrawArrays(mesh.mode, 0, mesh.count)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
    }

    private data class GpuMesh(val vertices: FloatBuffer, val count: Int, val mode: Int)

    private fun createMesh(values: FloatArray, mode: Int = GLES20.GL_TRIANGLES): GpuMesh {
        val buffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(values).position(0)
        return GpuMesh(buffer, values.size / 6, mode)
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source)
            GLES20.glCompileShader(id)
            val status = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(id) }
            return id
        }
        val vs = shader(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vs); GLES20.glAttachShader(id, fs)
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(id) }
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        return id
    }

    private fun cubeVertices(): FloatArray {
        val faces = listOf(
            floatArrayOf(0f,0f,-1f) to listOf(floatArrayOf(-.5f,-.5f,-.5f),floatArrayOf(-.5f,.5f,-.5f),floatArrayOf(.5f,.5f,-.5f),floatArrayOf(.5f,-.5f,-.5f)),
            floatArrayOf(0f,0f,1f) to listOf(floatArrayOf(-.5f,-.5f,.5f),floatArrayOf(.5f,-.5f,.5f),floatArrayOf(.5f,.5f,.5f),floatArrayOf(-.5f,.5f,.5f)),
            floatArrayOf(0f,-1f,0f) to listOf(floatArrayOf(-.5f,-.5f,-.5f),floatArrayOf(.5f,-.5f,-.5f),floatArrayOf(.5f,-.5f,.5f),floatArrayOf(-.5f,-.5f,.5f)),
            floatArrayOf(1f,0f,0f) to listOf(floatArrayOf(.5f,-.5f,-.5f),floatArrayOf(.5f,.5f,-.5f),floatArrayOf(.5f,.5f,.5f),floatArrayOf(.5f,-.5f,.5f)),
            floatArrayOf(0f,1f,0f) to listOf(floatArrayOf(.5f,.5f,-.5f),floatArrayOf(-.5f,.5f,-.5f),floatArrayOf(-.5f,.5f,.5f),floatArrayOf(.5f,.5f,.5f)),
            floatArrayOf(-1f,0f,0f) to listOf(floatArrayOf(-.5f,.5f,-.5f),floatArrayOf(-.5f,-.5f,-.5f),floatArrayOf(-.5f,-.5f,.5f),floatArrayOf(-.5f,.5f,.5f))
        )
        val out = mutableListOf<Float>()
        faces.forEach { (n, q) ->
            listOf(0,1,2,0,2,3).forEach { i ->
                out.addAll(q[i].toList()); out.addAll(n.toList())
            }
        }
        return out.toFloatArray()
    }

    private fun sphereVertices(): FloatArray {
        val out = mutableListOf<Float>()
        val lat = 20; val lon = 32
        fun point(i: Int, j: Int): FloatArray {
            val p = PI * i / lat; val t = 2.0 * PI * j / lon
            val x = (cos(t) * sin(p)).toFloat()
            val y = (sin(t) * sin(p)).toFloat()
            val z = cos(p).toFloat()
            return floatArrayOf(x,y,z)
        }
        for (i in 0 until lat) for (j in 0 until lon) {
            val a = point(i,j); val b = point(i+1,j); val c = point(i+1,j+1); val d = point(i,j+1)
            addVertex(out,a,a); addVertex(out,b,b); addVertex(out,c,c)
            addVertex(out,a,a); addVertex(out,c,c); addVertex(out,d,d)
        }
        return out.toFloatArray()
    }

    private fun cylinderVertices(): FloatArray {
        val out = mutableListOf<Float>()
        val steps = 40
        for (i in 0 until steps) {
            val a = 2.0 * PI * i / steps; val b = 2.0 * PI * (i+1) / steps
            val p0 = floatArrayOf(cos(a).toFloat(), sin(a).toFloat(), -.5f)
            val p1 = floatArrayOf(cos(b).toFloat(), sin(b).toFloat(), -.5f)
            val p2 = floatArrayOf(cos(b).toFloat(), sin(b).toFloat(), .5f)
            val p3 = floatArrayOf(cos(a).toFloat(), sin(a).toFloat(), .5f)
            val n0 = floatArrayOf(p0[0],p0[1],0f); val n1 = floatArrayOf(p1[0],p1[1],0f)
            addVertex(out,p0,n0); addVertex(out,p1,n1); addVertex(out,p2,n1)
            addVertex(out,p0,n0); addVertex(out,p2,n1); addVertex(out,p3,n0)
            val bottom = floatArrayOf(0f,0f,-1f); val top = floatArrayOf(0f,0f,1f)
            addVertex(out,floatArrayOf(0f,0f,-.5f),bottom); addVertex(out,p1,bottom); addVertex(out,p0,bottom)
            addVertex(out,floatArrayOf(0f,0f,.5f),top); addVertex(out,p3,top); addVertex(out,p2,top)
        }
        return out.toFloatArray()
    }

    private fun gridVertices(): FloatArray {
        val out = mutableListOf<Float>()
        for (i in -20..20) {
            val v = i * 10f
            addVertex(out,floatArrayOf(v,-200f,0f),floatArrayOf(0f,0f,1f))
            addVertex(out,floatArrayOf(v,200f,0f),floatArrayOf(0f,0f,1f))
            addVertex(out,floatArrayOf(-200f,v,0f),floatArrayOf(0f,0f,1f))
            addVertex(out,floatArrayOf(200f,v,0f),floatArrayOf(0f,0f,1f))
        }
        return out.toFloatArray()
    }

    private fun addVertex(out: MutableList<Float>, p: FloatArray, n: FloatArray) {
        out.add(p[0]); out.add(p[1]); out.add(p[2])
        out.add(n[0]); out.add(n[1]); out.add(n[2])
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform float uPointSize;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            varying vec3 vNormal;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                gl_PointSize = uPointSize;
                vNormal = aNormal;
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            uniform vec3 uLight;
            varying vec3 vNormal;
            void main() {
                float diffuse = max(dot(normalize(vNormal), normalize(uLight)), 0.0);
                float lighting = 0.38 + diffuse * 0.62;
                gl_FragColor = vec4(uColor.rgb * lighting, uColor.a);
            }
        """
    }
}
