/*
 * SPDX-FileCopyrightText: 2026 haloUI
 * SPDX-License-Identifier: Apache-2.0
 */

package org.haloui.videowallpaper

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.android.settingslib.widget.SettingsThemeHelper
import com.android.settingslib.widget.SliderPreference
import java.io.File
import java.util.concurrent.Executors
import org.haloui.videowallpaper.R
import org.haloui.videowallpaper.service.VideoLiveWallpaperService

class MainActivity : CollapsingToolbarBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.app_name)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.content_frame, WallpaperSettingsFragment())
                .commit()
        }
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun getTheme(): Resources.Theme {
        val theme = super.getTheme()
        if (SettingsThemeHelper.isExpressiveTheme(this)) {
            theme.applyStyle(R.style.VideoWallpaper_Theme_Expressive, true)
        }
        return theme
    }

    class WallpaperSettingsFragment : SettingsBasePreferenceFragment() {

        private val executor = Executors.newSingleThreadExecutor()

        private val videoPicker = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? -> uri?.let(::setVideo) }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.videowallpaper_preferences, rootKey)

            val context = requireContext()
            val deContext = context.createDeviceProtectedStorageContext()
            val unmuteFile = File(deContext.filesDir, "unmute")
            val prefs = deContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

            findPreference<Preference>("choose_video")!!.setOnPreferenceClickListener {
                videoPicker.launch("video/*")
                true
            }

            val volumeSlider = findPreference<SliderPreference>("audio_volume")!!.apply {
                min = 0
                max = 100
                value = prefs.getInt("volume", 100)
                isEnabled = unmuteFile.exists()
                setUpdatesContinuously(true)
                setHapticFeedbackMode(SliderPreference.HAPTIC_FEEDBACK_MODE_ON_ENDS)
                setOnPreferenceChangeListener { _, newValue ->
                    val volume = newValue as Int
                    prefs.edit().putInt("volume", volume).apply()
                    VideoLiveWallpaperService.setVolume(context, volume / 100f)
                    true
                }
            }

            findPreference<SwitchPreferenceCompat>("play_with_sound")!!.apply {
                isChecked = unmuteFile.exists()
                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    volumeSlider.isEnabled = enabled
                    if (enabled) {
                        unmuteFile.writeBytes(byteArrayOf())
                        VideoLiveWallpaperService.unmuteMusic(context)
                    } else {
                        unmuteFile.delete()
                        VideoLiveWallpaperService.muteMusic(context)
                    }
                    true
                }
            }
        }

        override fun onDestroy() {
            executor.shutdownNow()
            super.onDestroy()
        }

        private fun setVideo(uri: Uri) {
            val activity = requireActivity()
            executor.execute {
                val deContext = activity.createDeviceProtectedStorageContext()
                val dst = File(deContext.filesDir, "wallpaper.mp4")
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                VideoLiveWallpaperService.resetTransform(activity)
                VideoLiveWallpaperService.reload(activity)
                activity.runOnUiThread {
                    Toast.makeText(activity, R.string.video_set, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
