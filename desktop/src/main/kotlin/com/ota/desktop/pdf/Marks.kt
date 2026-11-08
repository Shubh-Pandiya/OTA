package com.ota.desktop.pdf

import java.awt.geom.Path2D

/**
 * The letterhead logo and the agency stamp as vector paths (viewport 3264 x 3264), read from the
 * same path data the Android app uses. Parsed once; drawn into PDFs, the UI and the app icon.
 */
object Marks {
    const val VIEWPORT = 3264f
    const val LOGO_RGB = 0xF8943A

    val logo: List<PathOp> by lazy { PathOp.parse(resource("marks/logo_path.txt")) }
    val stamp: List<PathOp> by lazy { PathOp.parse(resource("marks/stamp_path.txt")) }

    private fun resource(name: String): String =
        requireNotNull(Marks::class.java.classLoader.getResourceAsStream(name)) { "missing $name" }.bufferedReader().use { it.readText() }

    /** The path scaled into a [size] x [size] box at ([x], [y]), y pointing down. */
    fun awtPath(ops: List<PathOp>, x: Double, y: Double, size: Double): Path2D.Double {
        val k = size / VIEWPORT
        val p = Path2D.Double(Path2D.WIND_NON_ZERO)
        for (op in ops) {
            val c = op.coords
            when (op.cmd) {
                'M' -> p.moveTo(x + c[0] * k, y + c[1] * k)
                'L' -> p.lineTo(x + c[0] * k, y + c[1] * k)
                'C' -> p.curveTo(x + c[0] * k, y + c[1] * k, x + c[2] * k, y + c[3] * k, x + c[4] * k, y + c[5] * k)
                'Z' -> p.closePath()
            }
        }
        return p
    }
}

/** One absolute path command: M (x,y), L (x,y), C (x1,y1,x2,y2,x,y) or Z. */
class PathOp(val cmd: Char, val coords: FloatArray) {
    companion object {
        private val token = Regex("[MmLlHhVvCcZz]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")

        /** Parses SVG path data (M, L, H, V, C, Z; absolute or relative) into absolute M/L/C/Z. */
        fun parse(data: String): List<PathOp> {
            val out = ArrayList<PathOp>()
            val tokens = token.findAll(data).map { it.value }.toList()
            var i = 0
            var cmd = 'M'
            var x = 0f; var y = 0f; var sx = 0f; var sy = 0f
            fun num() = tokens[i++].toFloat()
            while (i < tokens.size) {
                if (tokens[i][0].isLetter()) cmd = tokens[i++][0]
                val rel = cmd.isLowerCase()
                val ox = if (rel) x else 0f
                val oy = if (rel) y else 0f
                when (cmd.uppercaseChar()) {
                    'M' -> {
                        x = ox + num(); y = oy + num(); sx = x; sy = y
                        out += PathOp('M', floatArrayOf(x, y))
                        cmd = if (rel) 'l' else 'L' // further pairs are line-tos
                    }
                    'L' -> { x = ox + num(); y = oy + num(); out += PathOp('L', floatArrayOf(x, y)) }
                    'H' -> { x = ox + num(); out += PathOp('L', floatArrayOf(x, y)) }
                    'V' -> { y = oy + num(); out += PathOp('L', floatArrayOf(x, y)) }
                    'C' -> {
                        val c = floatArrayOf(ox + num(), oy + num(), ox + num(), oy + num(), ox + num(), oy + num())
                        x = c[4]; y = c[5]
                        out += PathOp('C', c)
                    }
                    'Z' -> { out += PathOp('Z', FloatArray(0)); x = sx; y = sy }
                    else -> error("Unsupported path command $cmd")
                }
            }
            return out
        }
    }
}
