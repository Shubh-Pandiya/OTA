package com.example.otaapplication.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser

/** Material icons built from their SVG path data (only the few the core icon set lacks). */
private fun pathIcon(name: String, vararg paths: String): ImageVector = materialIcon(name = name) {
    for (path in paths) addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
    this
}

val Icons.Filled.PhotoCamera: ImageVector by lazy {
    pathIcon(
        "Filled.PhotoCamera",
        "M12 8.8a3.2 3.2 0 1 0 0 6.4a3.2 3.2 0 1 0 0 -6.4z",
        "M9 2L7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2h-3.17L15 2H9zm3 15c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5z"
    )
}

val Icons.Filled.Image: ImageVector by lazy {
    pathIcon(
        "Filled.Image",
        "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z"
    )
}

val Icons.Filled.ReportProblem: ImageVector by lazy {
    pathIcon("Filled.ReportProblem", "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z")
}

val Icons.Filled.Dashboard: ImageVector by lazy {
    pathIcon("Filled.Dashboard", "M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z")
}
