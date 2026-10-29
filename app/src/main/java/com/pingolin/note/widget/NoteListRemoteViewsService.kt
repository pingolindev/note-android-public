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

package com.pingolin.note.widget

import android.content.Intent
import android.widget.RemoteViewsService

/**
 * Service that provides the RemoteViewsFactory for the note list widget.
 * Required by Android's widget framework for collection-based widgets.
 */
@dagger.hilt.android.AndroidEntryPoint
class NoteListRemoteViewsService : RemoteViewsService() {

    // A Service IS an @AndroidEntryPoint target, so the factory gets the
    // singleton DAO instead of opening its own database — see the
    // constructor comment there for what that would cost.
    @javax.inject.Inject
    lateinit var itemDao: com.pingolin.note.data.local.dao.ItemDao

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return NoteListRemoteViewsFactory(applicationContext, itemDao)
    }
}
