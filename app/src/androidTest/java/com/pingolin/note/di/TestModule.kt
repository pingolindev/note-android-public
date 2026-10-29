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

/**
 * Test module scaffold for instrumented tests.
 *
 * This file is intentionally minimal: the FCM + ReminderReceiver + DB-level
 * tests in this test suite bootstrap their own mocks inline (via MockK /
 * reflection) rather than swapping the production AppModule.
 *
 * To override repositories with Hilt in a test, add the bindings here using
 * the `@TestInstallIn(replaces = [AppModule::class])` pattern.
 *
 * Example sketch:
 *
 * ```
 * @Module
 * @TestInstallIn(
 *     components = [SingletonComponent::class],
 *     replaces = [AppModule::class],
 * )
 * object TestModule {
 *     @Provides @Singleton
 *     fun provideNoteApi(): NoteApi = mock()
 * }
 * ```
 *
 * Leaving the scaffold here (with no actual Hilt bindings) keeps @HiltAndroidTest
 * pointed at the real AppModule — good enough for the tests in this suite
 * which exercise real system services (notification shade, broadcast
 * receivers, WorkManager) and only need Hilt to spin up the graph.
 */
object TestModule
