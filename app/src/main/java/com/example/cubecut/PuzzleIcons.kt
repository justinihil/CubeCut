/*
 * CubeCut - cuts a speedcubing session video into one clip per solve.
 * Copyright (C) 2026 DadCuber
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, version 3 of the License,
 * with the additional terms in NOTICE.md (GPL section 7): the author credit must stay in the app's
 * About window, and changed versions must be marked as changed.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program (the
 * LICENSE file). If not, see <https://www.gnu.org/licenses/>.
 */
package com.example.cubecut

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// ---------------------------------------------------------------------------------------
// Small flat puzzle logos, drawn by CubeCut (no image files): one face of each puzzle as separate "stickers" with gaps between
// them, in one colour. Used next to a puzzle tag in the viewer, on the tag window's puzzle buttons, and on Gallery cards.
// The shapes are plain geometry in a 0..1 box (puzzleShapes, no Android needed), so a unit test can draw them to a picture.
// ---------------------------------------------------------------------------------------

/** One piece of a logo, in a 0..1 box (y down). */
sealed class LogoShape {
    /** A filled sticker. */
    class Poly(val points: List<Pair<Float, Float>>) : LogoShape()
    /** A filled dot. */
    class Dot(val x: Float, val y: Float, val r: Float) : LogoShape()
    /** An outlined circle ([width] is the line width). */
    class Ring(val x: Float, val y: Float, val r: Float, val width: Float) : LogoShape()
}

/** Half the gap between two stickers, in the 0..1 box. Sized for a logo of about 16 to 28 dp. */
private const val GAP = 0.028f

/** How much the drawn outline rounds each sticker's corners (it also grows the sticker by this much, so it is taken off the gap). */
const val LOGO_ROUNDING = 0.012f

/**
 * A sticker: the convex polygon [points] moved inward by [d] on every side (each edge slides in, the corners follow), so every gap
 * has the same width whatever the sticker's shape. A sharp corner moves further in, which is how real puzzles look at their centre.
 */
private fun shrink(points: List<Pair<Float, Float>>, d: Float = GAP): LogoShape.Poly {
    val n = points.size
    var area = 0f
    for (i in 0 until n) {
        val (x1, y1) = points[i]
        val (x2, y2) = points[(i + 1) % n]
        area += x1 * y2 - x2 * y1
    }
    val sign = if (area > 0) 1f else -1f          // which side is "inside" depends on the order of the points
    val dd = d + LOGO_ROUNDING
    // each edge moved inward: a point on it and its direction
    val moved = (0 until n).map { i ->
        val (x1, y1) = points[i]
        val (x2, y2) = points[(i + 1) % n]
        val dx = x2 - x1
        val dy = y2 - y1
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val nx = -dy / len * sign
        val ny = dx / len * sign
        floatArrayOf(x1 + nx * dd, y1 + ny * dd, dx, dy)
    }
    val out = (0 until n).map { i ->
        val a = moved[(i + n - 1) % n]
        val b = moved[i]
        val den = a[2] * b[3] - a[3] * b[2]
        if (abs(den) < 1e-9f) Pair(b[0], b[1]) else {
            val t = ((b[0] - a[0]) * b[3] - (b[1] - a[1]) * b[2]) / den
            Pair(a[0] + a[2] * t, a[1] + a[3] * t)
        }
    }
    return LogoShape.Poly(out)
}

private fun lerp(a: Pair<Float, Float>, b: Pair<Float, Float>, t: Float) = Pair(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t)

/** A point on a circle around the middle; angle 0 = up, clockwise, in degrees. */
private fun polar(r: Float, deg: Double): Pair<Float, Float> {
    val a = deg * PI / 180.0
    return Pair(0.5f + r * sin(a).toFloat(), 0.5f - r * cos(a).toFloat())
}

/** A triangle cut into n² small triangles (rows of 1, 3, 5...), each as its own sticker. */
private fun splitTriangle(a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>, n: Int, gap: Float = GAP * 0.8f): List<LogoShape> {
    // point (i, j): i steps from a toward b, j steps from a toward c
    fun p(i: Int, j: Int): Pair<Float, Float> {
        val u = i.toFloat() / n
        val v = j.toFloat() / n
        return Pair(a.first + (b.first - a.first) * u + (c.first - a.first) * v, a.second + (b.second - a.second) * u + (c.second - a.second) * v)
    }
    val out = ArrayList<LogoShape>()
    for (i in 0 until n) for (j in 0 until n - i) {
        out.add(shrink(listOf(p(i, j), p(i + 1, j), p(i, j + 1)), gap))
        if (i + j < n - 1) out.add(shrink(listOf(p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)), gap))
    }
    return out
}

/** The logo of [puzzle] (a name from PUZZLES); anything else, including a typed "Other" name, gets a plain cube. */
fun puzzleShapes(puzzle: String): List<LogoShape> {
    val m = 0.06f                              // margin inside the box
    val nxn = Regex("^([2-7])x\\1$").find(puzzle)?.groupValues?.get(1)?.toInt()
    if (nxn != null) {
        val cell = (1f - 2 * m) / nxn
        val out = ArrayList<LogoShape>()
        for (r in 0 until nxn) for (c in 0 until nxn) {
            val x = m + c * cell
            val y = m + r * cell
            out.add(shrink(listOf(Pair(x, y), Pair(x + cell, y), Pair(x + cell, y + cell), Pair(x, y + cell)), minOf(GAP, cell * 0.10f)))
        }
        return out
    }
    return when (puzzle) {
        "Megaminx" -> {
            // a pentagon face: the cuts run parallel to each outer edge, which leaves a centre, five edges and five corners
            val o = (0 until 5).map { polar(0.48f, it * 72.0 + 0.0).let { p -> Pair(p.first, p.second + 0.03f) } }
            val k = 0.50f                                              // the centre's size against the whole face
            val c = Pair(0.5f, 0.53f)
            val ko = o.map { lerp(c, it, k) }                          // the centre's corners (line i runs through ko[i] and ko[i+1])
            fun line(i: Int) = Pair(ko[(i + 5) % 5], ko[(i + 6) % 5])
            fun edge(i: Int) = Pair(o[(i + 5) % 5], o[(i + 6) % 5])
            fun meet(l1: Pair<Pair<Float, Float>, Pair<Float, Float>>, l2: Pair<Pair<Float, Float>, Pair<Float, Float>>): Pair<Float, Float> {
                val (p1, p2) = l1
                val (p3, p4) = l2
                val d = (p1.first - p2.first) * (p3.second - p4.second) - (p1.second - p2.second) * (p3.first - p4.first)
                val a = p1.first * p2.second - p1.second * p2.first
                val b = p3.first * p4.second - p3.second * p4.first
                return Pair(
                    (a * (p3.first - p4.first) - (p1.first - p2.first) * b) / d,
                    (a * (p3.second - p4.second) - (p1.second - p2.second) * b) / d
                )
            }
            val out = ArrayList<LogoShape>()
            out.add(shrink(ko))
            for (i in 0 until 5) {
                val j = (i + 1) % 5
                // the corner at o[j], between edge i and edge j
                out.add(shrink(listOf(o[j], meet(line(i + 1), edge(i)), ko[j], meet(line(i), edge(i + 1)))))
                // the edge piece along edge i
                out.add(shrink(listOf(meet(line(i - 1), edge(i)), meet(line(i + 1), edge(i)), ko[j], ko[i])))
            }
            out
        }
        "Pyraminx" -> splitTriangle(Pair(0.5f, 0.08f), Pair(0.04f, 0.88f), Pair(0.96f, 0.88f), 3)
        "Skewb" -> {
            // a square face: a diamond in the middle, a triangle in each corner
            val a = m
            val b = 1f - m
            val mid = listOf(Pair(0.5f, a), Pair(b, 0.5f), Pair(0.5f, b), Pair(a, 0.5f))
            listOf(
                shrink(mid),
                shrink(listOf(Pair(a, a), Pair(0.5f, a), Pair(a, 0.5f))),
                shrink(listOf(Pair(b, a), Pair(b, 0.5f), Pair(0.5f, a))),
                shrink(listOf(Pair(b, b), Pair(0.5f, b), Pair(b, 0.5f))),
                shrink(listOf(Pair(a, b), Pair(a, 0.5f), Pair(0.5f, b)))
            )
        }
        "Square-1" -> {
            // the top layer from above: four kite corners (60°) and four wedge edges (30°) around the centre
            val h = 0.5f - m
            fun edgeAt(deg: Double): Pair<Float, Float> {
                val a = deg * PI / 180.0
                val dx = sin(a).toFloat()
                val dy = -cos(a).toFloat()
                val k = h / max(abs(dx), abs(dy))
                return Pair(0.5f + dx * k, 0.5f + dy * k)
            }
            val c = Pair(0.5f, 0.5f)
            val out = ArrayList<LogoShape>()
            for (q in 0 until 4) {
                val base = q * 90.0
                out.add(shrink(listOf(c, edgeAt(base + 15.0), edgeAt(base + 45.0), edgeAt(base + 75.0))))   // corner
                out.add(shrink(listOf(c, edgeAt(base + 75.0), edgeAt(base + 105.0))))                       // edge
            }
            out
        }
        "Clock" -> {
            // a round face with nine dials (the middle one a little bigger)
            val out = ArrayList<LogoShape>()
            out.add(LogoShape.Ring(0.5f, 0.5f, 0.44f, 0.07f))
            for (r in -1..1) for (c in -1..1) out.add(LogoShape.Dot(0.5f + c * 0.22f, 0.5f + r * 0.22f, if (r == 0 && c == 0) 0.085f else 0.065f))
            out
        }
        "FTO" -> {
            // an octahedron seen from a corner: four triangle faces (cut further, it blurs at icon size)
            val top = Pair(0.5f, m)
            val right = Pair(1f - m, 0.5f)
            val bottom = Pair(0.5f, 1f - m)
            val left = Pair(m, 0.5f)
            val c = Pair(0.5f, 0.5f)
            listOf(shrink(listOf(c, top, right)), shrink(listOf(c, right, bottom)), shrink(listOf(c, bottom, left)), shrink(listOf(c, left, top)))
        }
        else -> {
            // a plain cube: top, left and right faces of a cube seen from a corner
            val c = Pair(0.5f, 0.5f)
            val pts = (0 until 6).map { polar(0.47f, it * 60.0) }       // 0 top, 1 upper right, 2 lower right, 3 bottom, 4 lower left, 5 upper left
            listOf(
                shrink(listOf(pts[0], pts[1], c, pts[5])),
                shrink(listOf(pts[1], pts[2], pts[3], c)),
                shrink(listOf(pts[5], c, pts[3], pts[4]))
            )
        }
    }
}

/** The logo of [puzzle] in [color], [size] square. Nothing is drawn for "" (not tagged). */
@Composable
fun PuzzleIcon(puzzle: String, color: Color, size: Dp, modifier: Modifier = Modifier) {
    if (puzzle.isEmpty()) return
    val shapes = puzzleShapes(puzzle)
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val round = Stroke(width = s * LOGO_ROUNDING * 2, join = StrokeJoin.Round)    // rounds the stickers' corners a little
        for (shape in shapes) {
            when (shape) {
                is LogoShape.Poly -> {
                    val path = Path().apply {
                        shape.points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * s, y * s) else lineTo(x * s, y * s) }
                        close()
                    }
                    drawPath(path, color, style = Fill)
                    drawPath(path, color, style = round)
                }
                is LogoShape.Dot -> drawCircle(color, shape.r * s, Offset(shape.x * s, shape.y * s))
                is LogoShape.Ring -> drawCircle(color, shape.r * s, Offset(shape.x * s, shape.y * s), style = Stroke(width = shape.width * s))
            }
        }
    }
}

/** The logo in white on a dark round patch, for the corner of a picture (Gallery cards). Nothing for "" (not tagged). */
@Composable
fun PuzzleBadge(puzzle: String, size: Dp, modifier: Modifier = Modifier) {
    if (puzzle.isEmpty()) return
    Box(modifier.size(size).background(Color(0xAA000000), CircleShape), contentAlignment = Alignment.Center) {
        PuzzleIcon(puzzle, Color.White, size * 0.62f)
    }
}
