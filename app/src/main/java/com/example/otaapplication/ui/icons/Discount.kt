/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.otaapplication.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Filled.Discount: ImageVector
    get() {
        if (_discount != null) {
            return _discount!!
        }
        _discount = materialIcon(name = "Filled.Discount") {
            materialPath {
                moveTo(12.79f, 21.0f)
                lineTo(3.0f, 11.21f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 0.53f, 0.21f, 1.04f, 0.59f, 1.41f)
                lineToRelative(7.79f, 7.79f)
                curveToRelative(0.78f, 0.78f, 2.05f, 0.78f, 2.83f, 0.0f)
                lineToRelative(6.21f, -6.21f)
                curveToRelative(0.78f, -0.78f, 0.78f, -2.05f, 0.0f, -2.83f)
                lineTo(12.79f, 21.0f)
                close()
            }
            materialPath {
                moveTo(11.38f, 17.41f)
                curveToRelative(0.78f, 0.78f, 2.05f, 0.78f, 2.83f, 0.0f)
                lineToRelative(6.21f, -6.21f)
                curveToRelative(0.78f, -0.78f, 0.78f, -2.05f, 0.0f, -2.83f)
                lineToRelative(-7.79f, -7.79f)
                curveTo(12.25f, 0.21f, 11.74f, 0.0f, 11.21f, 0.0f)
                horizontalLineTo(5.0f)
                curveTo(3.9f, 0.0f, 3.0f, 0.9f, 3.0f, 2.0f)
                verticalLineToRelative(6.21f)
                curveToRelative(0.0f, 0.53f, 0.21f, 1.04f, 0.59f, 1.41f)
                lineTo(11.38f, 17.41f)
                close()
                moveTo(7.25f, 3.0f)
                curveTo(7.94f, 3.0f, 8.5f, 3.56f, 8.5f, 4.25f)
                reflectiveCurveTo(7.94f, 5.5f, 7.25f, 5.5f)
                reflectiveCurveTo(6.0f, 4.94f, 6.0f, 4.25f)
                reflectiveCurveTo(6.56f, 3.0f, 7.25f, 3.0f)
                close()
            }
        }
        return _discount!!
    }

private var _discount: ImageVector? = null
