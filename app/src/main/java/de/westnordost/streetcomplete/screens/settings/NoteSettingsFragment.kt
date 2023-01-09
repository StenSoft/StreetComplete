package de.westnordost.streetcomplete.screens.settings

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import de.westnordost.streetcomplete.Prefs
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.screens.HasTitle
import de.westnordost.streetcomplete.util.ktx.toast
import org.koin.android.ext.android.inject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NoteSettingsFragment : PreferenceFragmentCompat(), HasTitle {

    private val prefs: SharedPreferences by inject()

    override val title: String get() = getString(R.string.pref_screen_notes)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        PreferenceManager.setDefaultValues(requireContext(), R.xml.preferences_ee_notes, false)
        addPreferencesFromResource(R.xml.preferences_ee_notes)

        findPreference<Preference>("hide_notes_by")?.setOnPreferenceClickListener {
            val text = EditText(context)
            text.setText(prefs.getStringSet(Prefs.HIDE_NOTES_BY_USERS, emptySet())?.joinToString(","))
            text.setHint(R.string.pref_hide_notes_hint)
            text.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

            val layout = LinearLayout(context)
            layout.setPadding(30,10,30,10)
            layout.addView(text)

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_hide_notes_message)
                .setView(layout)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val content = text.text.split(",").map { it.trim().lowercase() }.toSet()
                    prefs.edit().putStringSet(Prefs.HIDE_NOTES_BY_USERS, content).apply()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

            true
        }

        findPreference<Preference>("get_gpx_notes")?.setOnPreferenceClickListener {
            if (File(requireContext().getExternalFilesDir(null), "notes.gpx").exists()) {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_TITLE, "notes.zip")
                    type = "application/zip"
                }
                startActivityForResult(intent, GPX_REQUEST_CODE)
            } else {
                context?.toast(getString(R.string.pref_files_not_found), Toast.LENGTH_LONG)
            }
            true
        }

        findPreference<Preference>("get_photos")?.setOnPreferenceClickListener {
            val dir = File(requireContext().getExternalFilesDir(null), "full_photos")
            if (dir.exists() && dir.isDirectory && dir.list()?.isNotEmpty() == true) {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_TITLE, "full_photos.zip")
                    type = "application/zip"
                }
                startActivityForResult(intent, PHOTO_REQUEST_CODE)
            } else {
                context?.toast(getString(R.string.pref_files_not_found), Toast.LENGTH_LONG)
            }
            true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null)
            return
        val uri = data.data ?: return
        when (requestCode) {
            GPX_REQUEST_CODE -> saveGpx(uri)
            PHOTO_REQUEST_CODE -> savePhotos(uri)
        }
    }

    private fun saveGpx(uri: Uri) {
        val output = activity?.contentResolver?.openOutputStream(uri) ?: return
        val os = output.buffered()
        try {
            // read gpx and extract images
            val filesDir = requireContext().getExternalFilesDir(null)
            val gpxFile = File(filesDir, "notes.gpx")
            val files = mutableListOf(gpxFile)
            val gpxText = gpxFile.readText(Charsets.UTF_8)
            val picturesDir = File(filesDir, "Pictures")
            // get all files in pictures dir and check whether they occur in gpxText
            if (picturesDir.isDirectory) {
                picturesDir.walk().forEach {
                    if (!it.isDirectory && gpxText.contains(it.name))
                        files.add(it)
                }
            }
            filesDir?.walk()?.forEach {
                if (it.name.startsWith("track_") && it.name.endsWith(".gpx") && gpxText.contains(it.name))
                    files.add(it)
            }

            // write files to zip
            val zipStream = ZipOutputStream(os)
            files.forEach {
                val fileStream = FileInputStream(it).buffered()
                zipStream.putNextEntry(ZipEntry(it.name))
                fileStream.copyTo(zipStream, 1024)
                fileStream.close()
                zipStream.closeEntry()
            }
            zipStream.close()
            files.forEach { it.delete() }
        } catch (e: IOException) {
            context?.toast(getString(R.string.pref_save_file_error), Toast.LENGTH_LONG)
        }
        os.close()
        output.close()
    }

    private fun savePhotos(uri: Uri) {
        val output = activity?.contentResolver?.openOutputStream(uri) ?: return
        val os = output.buffered()
        try {
            val filesDir = requireContext().getExternalFilesDir(null)
            val files = mutableListOf<File>()
            val picturesDir = File(filesDir, "full_photos")
            // get all files in pictures dir
            if (picturesDir.isDirectory) {
                picturesDir.walk().forEach {
                    if (!it.isDirectory) files.add(it)
                }
            }
            else { // we checked for this, but better be sure
                context?.toast(getString(R.string.pref_files_not_found), Toast.LENGTH_LONG)
                return
            }

            // write files to zip
            val zipStream = ZipOutputStream(os)
            files.forEach {
                val fileStream = FileInputStream(it).buffered()
                zipStream.putNextEntry(ZipEntry(it.name))
                fileStream.copyTo(zipStream, 1024)
                fileStream.close()
                zipStream.closeEntry()
            }
            zipStream.close()
            files.forEach { it.delete() }
        } catch (e: IOException) {
            context?.toast(getString(R.string.pref_save_file_error), Toast.LENGTH_LONG)
        }
        os.close()
        output.close()
    }

}

private const val GPX_REQUEST_CODE = 387532
private const val PHOTO_REQUEST_CODE = 7658329