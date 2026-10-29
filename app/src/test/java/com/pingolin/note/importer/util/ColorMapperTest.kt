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

import org.junit.Assert.*
import org.junit.Test

class ColorMapperTest {

    // =============================================================================
    // STANDARD COLOR MAPPING TESTS
    // =============================================================================

    @Test
    fun `fromKeepColor_red_returnsBrandRed`() {
        assertEquals("#F38B8B", ColorMapper.fromKeepColor("RED"))
    }

    @Test
    fun `fromKeepColor_orange_returnsBrandOrange`() {
        assertEquals("#FAB387", ColorMapper.fromKeepColor("ORANGE"))
    }

    @Test
    fun `fromKeepColor_yellow_returnsBrandYellow`() {
        assertEquals("#F9E2AF", ColorMapper.fromKeepColor("YELLOW"))
    }

    @Test
    fun `fromKeepColor_green_returnsBrandGreen`() {
        assertEquals("#7DD3A1", ColorMapper.fromKeepColor("GREEN"))
    }

    @Test
    fun `fromKeepColor_teal_returnsBrandTeal`() {
        assertEquals("#6DD5C8", ColorMapper.fromKeepColor("TEAL"))
    }

    @Test
    fun `fromKeepColor_blue_returnsBrandBlue`() {
        assertEquals("#8B9FFA", ColorMapper.fromKeepColor("BLUE"))
    }

    @Test
    fun `fromKeepColor_cerulean_returnsBrandCerulean`() {
        assertEquals("#74B8EC", ColorMapper.fromKeepColor("CERULEAN"))
    }

    @Test
    fun `fromKeepColor_purple_returnsBrandPurple`() {
        assertEquals("#A08AFF", ColorMapper.fromKeepColor("PURPLE"))
    }

    @Test
    fun `fromKeepColor_pink_returnsBrandPink`() {
        assertEquals("#E8A8D8", ColorMapper.fromKeepColor("PINK"))
    }

    @Test
    fun `fromKeepColor_brown_returnsBrandBrown`() {
        assertEquals("#D4B8B8", ColorMapper.fromKeepColor("BROWN"))
    }

    @Test
    fun `fromKeepColor_gray_returnsBrandGray`() {
        assertEquals("#6C6C86", ColorMapper.fromKeepColor("GRAY"))
    }

    // =============================================================================
    // DEFAULT / NULL HANDLING TESTS
    // =============================================================================

    @Test
    fun `fromKeepColor_default_returnsNull`() {
        assertNull(ColorMapper.fromKeepColor("DEFAULT"))
    }

    @Test
    fun `fromKeepColor_null_returnsNull`() {
        assertNull(ColorMapper.fromKeepColor(null))
    }

    @Test
    fun `fromKeepColor_unknownColor_returnsNull`() {
        assertNull(ColorMapper.fromKeepColor("MAGENTA"))
    }

    @Test
    fun `fromKeepColor_emptyString_returnsNull`() {
        assertNull(ColorMapper.fromKeepColor(""))
    }

    // =============================================================================
    // CASE INSENSITIVITY TESTS
    // =============================================================================

    @Test
    fun `fromKeepColor_lowercaseInput_matchesByUppercase`() {
        assertEquals("#F38B8B", ColorMapper.fromKeepColor("red"))
    }

    @Test
    fun `fromKeepColor_mixedCaseInput_matchesByUppercase`() {
        assertEquals("#8B9FFA", ColorMapper.fromKeepColor("Blue"))
    }

    @Test
    fun `fromKeepColor_camelCaseInput_matchesByUppercase`() {
        assertEquals("#7DD3A1", ColorMapper.fromKeepColor("Green"))
    }
}
