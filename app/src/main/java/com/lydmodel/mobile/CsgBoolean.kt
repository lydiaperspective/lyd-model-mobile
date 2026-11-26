package com.lydmodel.mobile

import kotlin.math.abs
import kotlin.math.sqrt

/** Small BSP solid modeller used by merged shapes and STL export. */
internal object CsgBoolean {
    private const val EPS = 0.0001f
    private data class V(val x: Float, val y: Float, val z: Float) {
        operator fun plus(b: V) = V(x + b.x, y + b.y, z + b.z)
        operator fun minus(b: V) = V(x - b.x, y - b.y, z - b.z)
        operator fun times(s: Float) = V(x * s, y * s, z * s)
        fun dot(b: V) = x*b.x + y*b.y + z*b.z
        fun cross(b: V) = V(y*b.z-z*b.y, z*b.x-x*b.z, x*b.y-y*b.x)
        fun unit(): V { val n = sqrt(x*x+y*y+z*z).coerceAtLeast(1e-8f); return this * (1f/n) }
        fun array() = floatArrayOf(x,y,z)
    }
    private data class Poly(val points: List<V>) {
        val normal: V = (points[1]-points[0]).cross(points[2]-points[0]).unit()
        val w: Float = normal.dot(points[0])
        fun flipped() = Poly(points.reversed())
        fun triangles(): List<Array<FloatArray>> = (1 until points.lastIndex).map { i ->
            arrayOf(points[0].array(), points[i].array(), points[i+1].array())
        }
    }
    private class Plane(val normal: V, val w: Float) {
        fun flip() = Plane(normal * -1f, -w)
        fun split(poly: Poly, coplanarFront: MutableList<Poly>, coplanarBack: MutableList<Poly>,
                  front: MutableList<Poly>, back: MutableList<Poly>) {
            val types = poly.points.map { p -> val t = normal.dot(p)-w; when { t < -EPS -> 2; t > EPS -> 1; else -> 0 } }
            val type = types.fold(0) { a, b -> a or b }
            when (type) {
                0 -> if (normal.dot(poly.normal) > 0f) coplanarFront.add(poly) else coplanarBack.add(poly)
                1 -> front.add(poly)
                2 -> back.add(poly)
                else -> {
                    val f = mutableListOf<V>(); val b = mutableListOf<V>()
                    for (i in poly.points.indices) {
                        val j = (i+1) % poly.points.size
                        val a = poly.points[i]; val q = poly.points[j]
                        val ti = types[i]; val tj = types[j]
                        if (ti != 2) f.add(a)
                        if (ti != 1) b.add(a)
                        if ((ti or tj) == 3) {
                            val d = q-a
                            val t = (w-normal.dot(a))/normal.dot(d)
                            val v = a+d*t
                            f.add(v); b.add(v)
                        }
                    }
                    if (f.size >= 3) front.add(Poly(f))
                    if (b.size >= 3) back.add(Poly(b))
                }
            }
        }
    }
    private class Node(polygons: List<Poly> = emptyList()) {
        var plane: Plane? = null
        var front: Node? = null
        var back: Node? = null
        var polygons: MutableList<Poly> = mutableListOf()
        init { build(polygons) }
        fun all(): List<Poly> = polygons + (front?.all() ?: emptyList()) + (back?.all() ?: emptyList())
        fun invert() { polygons = polygons.map { it.flipped() }.toMutableList(); plane = plane?.flip(); front?.invert(); back?.invert(); val n=front; front=back; back=n }
        fun clip(input: List<Poly>): List<Poly> {
            val p = plane ?: return input
            val f=mutableListOf<Poly>(); val b=mutableListOf<Poly>()
            input.forEach { p.split(it, f, b, f, b) }
            val fc = front?.clip(f) ?: f
            val bc = back?.clip(b) ?: emptyList()
            return fc + bc
        }
        fun clipTo(other: Node) { polygons = other.clip(polygons).toMutableList(); front?.clipTo(other); back?.clipTo(other) }
        fun build(input: List<Poly>) {
            if (input.isEmpty()) return
            if (plane == null) plane = Plane(input.first().normal, input.first().w)
            val f=mutableListOf<Poly>(); val b=mutableListOf<Poly>()
            input.forEach { plane!!.split(it, polygons, polygons, f, b) }
            if (f.isNotEmpty()) { if (front == null) front=Node(); front!!.build(f) }
            if (b.isNotEmpty()) { if (back == null) back=Node(); back!!.build(b) }
        }
    }
    private fun polygons(objects: List<ModelObject>): List<Poly> = objects.flatMap { obj ->
        StlExporter.triangles(obj).map { t -> Poly(t.map { V(it[0],it[1],it[2]) }) }
    }
    private fun union(aPolys: List<Poly>, bPolys: List<Poly>): List<Poly> {
        if (aPolys.isEmpty()) return bPolys
        if (bPolys.isEmpty()) return aPolys
        val a=Node(aPolys); val b=Node(bPolys)
        a.clipTo(b); b.clipTo(a); b.invert(); b.clipTo(a); b.invert(); a.build(b.all())
        return a.all()
    }
    private fun subtract(aPolys: List<Poly>, bPolys: List<Poly>): List<Poly> {
        if (aPolys.isEmpty() || bPolys.isEmpty()) return aPolys
        val a=Node(aPolys); val b=Node(bPolys)
        a.invert(); a.clipTo(b); b.clipTo(a); b.invert(); b.clipTo(a); b.invert(); a.build(b.all()); a.invert()
        return a.all()
    }
    fun combine(objects: List<ModelObject>): List<Array<FloatArray>> {
        val solids = objects.filter { it.visible && !it.hollow }
        val holes = objects.filter { it.visible && it.hollow }
        if (solids.isEmpty()) return emptyList()
        var result = polygons(solids.first())
        solids.drop(1).forEach { result = union(result, polygons(listOf(it))) }
        holes.forEach { result = subtract(result, polygons(listOf(it))) }
        return result.flatMap { it.triangles() }.filter { tri ->
            val a=tri[0]; val b=tri[1]; val c=tri[2]
            val ux=b[0]-a[0]; val uy=b[1]-a[1]; val uz=b[2]-a[2]
            val vx=c[0]-a[0]; val vy=c[1]-a[1]; val vz=c[2]-a[2]
            abs((uy*vz-uz*vy)+(uz*vx-ux*vz)+(ux*vy-uy*vx)) > EPS
        }
    }
}
