/*
 * Copyright (c) 2026 Pingolin LLC
 * This file is part of Pingolin Note.
 *
 * Pingolin Note is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Pingolin Note is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pingolin Note. If not, see <https://www.gnu.org/licenses/>.
 */

package com.pingolin.note.importer.util

/** Maps Google Keep color names to brand-aligned pastel hex colors. */
object ColorMapper {

    private val keepColorMap = mapOf(
        "DEFAULT" to null,
        "RED" to "#F38B8B",
        "ORANGE" to "#FAB387",
        "YELLOW" to "#F9E2AF",
        "GREEN" to "#7DD3A1",
        "TEAL" to "#6DD5C8",
        "BLUE" to "#8B9FFA",
        "CERULEAN" to "#74B8EC",
        "PURPLE" to "#A08AFF",
        "PINK" to "#E8A8D8",
        "BROWN" to "#D4B8B8",
        "GRAY" to "#6C6C86",
    )

    fun fromKeepColor(color: String?): String? {
        if (color == null) return null
        return keepColorMap[color.uppercase()]
    }
}
