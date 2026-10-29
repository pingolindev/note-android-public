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

package com.pingolin.note.di

import com.pingolin.note.util.FcmTokenProvider
import com.pingolin.note.util.FirebaseFcmTokenProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * GMS half of the flavour-specific push binding. `src/libre/…/di/FcmModule.kt`
 * is the same module name binding [com.pingolin.note.util.NoFcmTokenProvider]
 * instead; exactly one of the two is ever on a variant's compile path, so
 * there is no duplicate-binding conflict.
 */
@Module
@InstallIn(SingletonComponent::class)
object FcmModule {

    @Provides
    @Singleton
    fun provideFcmTokenProvider(impl: FirebaseFcmTokenProvider): FcmTokenProvider = impl
}
