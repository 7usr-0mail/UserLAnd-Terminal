package tech.ula.utils

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.net.Uri
import androidx.core.content.ContextCompat
import tech.ula.R

/**
 * Permission handling that is aware of the very different storage models across
 * Android versions.
 *
 * The upstream implementation unconditionally demanded READ_EXTERNAL_STORAGE and
 * WRITE_EXTERNAL_STORAGE. From Android 10 those are scoped or ignored, and from
 * Android 13 they cannot be granted at all -- so the check never succeeded, the
 * startup state machine stalled waiting for permissions that would never arrive,
 * and the app surfaced an illegal-state error. It also indexed grantResults[1]
 * blindly, which throws when the user only answers part of a request.
 */
class PermissionHandler {
    companion object {
        const val permissionRequestCode = 1234

        /**
         * The permissions worth asking for on the running platform version.
         * Everything the app strictly needs lives in its own scoped directories,
         * so an empty list simply means "nothing to ask, proceed".
         */
        private fun requiredPermissions(): Array<String> {
            return when {
                // Android 13+: shared storage is granular media access only.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                        Manifest.permission.POST_NOTIFICATIONS
                )
                // Android 10-12: scoped storage; legacy read still meaningful.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE
                )
                // Android 6-9: classic runtime storage permissions.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                // Pre-Marshmallow: granted at install time.
                else -> arrayOf()
            }
        }

        fun permissionsAreGranted(context: Context): Boolean {
            val permissions = requiredPermissions()
            if (permissions.isEmpty()) return true
            return permissions.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) ==
                        PackageManager.PERMISSION_GRANTED
            }
        }

        /**
         * True when the request we issued came back fully granted. Notifications
         * are treated as optional: the app still works if they are refused.
         */
        fun permissionsWereGranted(requestCode: Int, grantResults: IntArray): Boolean {
            if (requestCode != permissionRequestCode) return false
            // An empty result means the request was cancelled.
            if (grantResults.isEmpty()) return false

            // On Tiramisu+ the only permission requested is notifications, which
            // must never block startup.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return true

            return grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        }

        /**
         * True when the app can read and write arbitrary shared storage. Used only
         * for optional features such as exporting a filesystem backup.
         */
        /** Prompt once for Android's special All files access capability. */
        fun offerFullStorageAccess(activity: Activity) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || hasFullStorageAccess(activity)) return
            val preferences = activity.getSharedPreferences("terminal_permissions", Context.MODE_PRIVATE)
            if (preferences.getBoolean("full_storage_prompted", false)) return
            AlertDialog.Builder(activity)
                    .setTitle("Full terminal storage access")
                    .setMessage("Allow All files access to make shared internal storage available in Linux at /storage/shared. This does not grant root or access to other apps' private data.")
                    .setPositiveButton("Open Android settings") { dialog, _ ->
                        preferences.edit().putBoolean("full_storage_prompted", true).apply()
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${activity.packageName}")))
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.alert_permissions_necessary_cancel_button) { dialog, _ ->
                        preferences.edit().putBoolean("full_storage_prompted", true).apply()
                        dialog.dismiss()
                    }
                    .show()
        }

        fun hasFullStorageAccess(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                permissionsAreGranted(context)
            }
        }

        @TargetApi(Build.VERSION_CODES.M)
        fun showPermissionsNecessaryDialog(activity: Activity) {
            val permissions = requiredPermissions()
            if (permissions.isEmpty()) return

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

            val builder = AlertDialog.Builder(activity)
            builder.setMessage(R.string.alert_permissions_necessary_message)
                    .setTitle(R.string.alert_permissions_necessary_title)
                    .setPositiveButton(R.string.button_ok) { dialog, _ ->
                        activity.requestPermissions(permissions, permissionRequestCode)
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.alert_permissions_necessary_cancel_button) { dialog, _ ->
                        dialog.dismiss()
                    }
            builder.create().show()
        }
    }
}
