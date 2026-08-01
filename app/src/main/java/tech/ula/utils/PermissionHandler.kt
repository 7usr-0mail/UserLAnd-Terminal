package tech.ula.utils

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import androidx.core.content.ContextCompat
import tech.ula.R
import tech.ula.TerminalDeviceAdminReceiver

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
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf()
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
        /** Offer each Android capability in sequence. None blocks basic proot use. */
        fun offerTerminalCapabilities(activity: Activity) {
            val preferences = activity.getSharedPreferences("terminal_permissions", Context.MODE_PRIVATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasFullStorageAccess(activity) &&
                    !preferences.getBoolean("all_files_seen_v2", false)) {
                showCapabilityDialog(activity, "Full terminal storage access",
                        "Allow All files access to expose shared phone storage at /storage/shared.",
                        "Open Android settings") {
                    preferences.edit().putBoolean("all_files_seen_v2", true).apply()
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${activity.packageName}")))
                }
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !canIgnoreBatteryOptimizations(activity) &&
                    !preferences.getBoolean("battery_seen_v2", false)) {
                showCapabilityDialog(activity, "Keep terminal sessions running",
                        "Allow the terminal to ignore battery optimization for long downloads and background sessions.",
                        "Open Android settings") {
                    preferences.edit().putBoolean("battery_seen_v2", true).apply()
                    activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${activity.packageName}")))
                }
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(activity) &&
                    !preferences.getBoolean("write_settings_seen_v2", false)) {
                showCapabilityDialog(activity, "Modify Android settings",
                        "Allow future terminal bridge commands to change settings Android permits apps to modify.",
                        "Open Android settings") {
                    preferences.edit().putBoolean("write_settings_seen_v2", true).apply()
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:${activity.packageName}")))
                }
                return
            }
            if (!isDeviceAdminActive(activity) && !preferences.getBoolean("admin_seen_v2", false)) {
                showCapabilityDialog(activity, "Enable Device Administrator",
                        "Enable the limited Device Administrator capability. It permits device lock only; it does not grant root or wipe/reset-password access.",
                        "Open Android settings") {
                    preferences.edit().putBoolean("admin_seen_v2", true).apply()
                    val component = ComponentName(activity, TerminalDeviceAdminReceiver::class.java)
                    activity.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
                            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    "Enables the terminal's optional device-lock command."))
                }
                return
            }
            val missing = optionalRuntimePermissions().filter {
                ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
            if (missing.isNotEmpty() && !preferences.getBoolean("runtime_seen_v2", false)) {
                showCapabilityDialog(activity, "Terminal notifications and media access",
                        "Allow notifications for background sessions and media access for gallery files.",
                        "Continue") {
                    preferences.edit().putBoolean("runtime_seen_v2", true).apply()
                    activity.requestPermissions(missing, permissionRequestCode)
                }
            }
        }

        private fun optionalRuntimePermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            else -> emptyArray()
        }

        private fun canIgnoreBatteryOptimizations(context: Context): Boolean {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return power.isIgnoringBatteryOptimizations(context.packageName)
        }

        private fun isDeviceAdminActive(context: Context): Boolean {
            val policy = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return policy.isAdminActive(ComponentName(context, TerminalDeviceAdminReceiver::class.java))
        }

        private fun showCapabilityDialog(activity: Activity, title: String, message: String,
                                         positive: String, onPositive: () -> Unit) {
            AlertDialog.Builder(activity).setTitle(title).setMessage(message)
                    .setPositiveButton(positive) { dialog, _ -> onPositive(); dialog.dismiss() }
                    .setNegativeButton(R.string.alert_permissions_necessary_cancel_button) { dialog, _ -> dialog.dismiss() }
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
