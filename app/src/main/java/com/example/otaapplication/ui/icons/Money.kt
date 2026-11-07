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

public val Icons.Filled.Money: ImageVector
    get() {
        if (_money != null) {
            return _money!!
        }
        _money = materialIcon(name = "Filled.Money") {
            materialPath {
                moveTo(5.0f, 8.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(8.0f)
                lineTo(5.0f, 16.0f)
                close()
                moveTo(12.0f, 8.0f)
                lineTo(9.0f, 8.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(6.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(3.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                lineTo(13.0f, 9.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                close()
                moveTo(11.0f, 14.0f)
                horizontalLineToRelative(-1.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(18.0f, 8.0f)
                horizontalLineToRelative(-3.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(6.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(3.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                lineTo(19.0f, 9.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                close()
                moveTo(17.0f, 14.0f)
                horizontalLineToRelative(-1.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(4.0f)
                close()
            }
            materialPath {
                moveTo(2.0f, 4.0f)
                verticalLineToRelative(16.0f)
                horizontalLineToRelative(20.0f)
                lineTo(22.0f, 4.0f)
                lineTo(2.0f, 4.0f)
                close()
                moveTo(4.0f, 18.0f)
                lineTo(4.0f, 6.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(12.0f)
                lineTo(4.0f, 18.0f)
                close()
            }
        }
        return _money!!
    }

private var _money: ImageVector? = null
