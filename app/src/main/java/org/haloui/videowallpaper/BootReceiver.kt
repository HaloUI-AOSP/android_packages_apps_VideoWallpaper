/*
 * SPDX-FileCopyrightText: 2026 haloUI
 * SPDX-License-Identifier: Apache-2.0
 */

package org.haloui.videowallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.haloui.videowallpaper.service.VideoLiveWallpaperService

internal class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = Intent(context, VideoLiveWallpaperService::class.java)
        context.startService(service)
    }
}
