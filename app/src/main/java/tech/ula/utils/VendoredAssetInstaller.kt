package tech.ula.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.ula.model.entities.Asset
import java.io.File
import java.io.IOException

/**
 * Installs the per-distribution support assets that are shipped inside the APK.
 *
 * Upstream UserLAnd downloaded these from a third-party GitHub release on every
 * first run. This fork vendors them under `assets/distro/<distro>/<arch>/`, so
 * the only thing ever fetched from the network is the distribution's own root
 * filesystem, straight from its official archive.
 */
class VendoredAssetInstaller(
    private val context: Context,
    private val ulaFiles: UlaFiles,
    private val logger: Logger = SentryLogger()
) {

    private fun assetPath(distributionType: String, arch: String): String {
        return "distro/$distributionType/$arch"
    }

    /**
     * Lists the support assets bundled for a distribution, without touching the network.
     */
    fun getBundledAssetList(distributionType: String): List<Asset> {
        val arch = ulaFiles.getArchType()
        return try {
            val names = context.assets.list(assetPath(distributionType, arch)) ?: emptyArray()
            names.map { Asset(it, distributionType) }
        } catch (err: Exception) {
            logger.addExceptionBreadcrumb(Exception("Could not list bundled assets for $distributionType"))
            emptyList()
        }
    }

    /**
     * Copies the bundled support assets into the distribution's shared support
     * directory and makes them executable.
     */
    @Throws(IOException::class)
    suspend fun installAssets(distributionType: String) = withContext(Dispatchers.IO) {
        val arch = ulaFiles.getArchType()
        val source = assetPath(distributionType, arch)
        val destination = File(ulaFiles.filesDir, distributionType)
        destination.mkdirs()

        val names = context.assets.list(source)
                ?: throw IOException("No bundled assets found for $distributionType/$arch")

        for (name in names) {
            val target = File(destination, name)
            context.assets.open("$source/$name").use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            ulaFiles.makePermissionsUsable(destination.absolutePath, name)
        }

        writeVersionStamp(distributionType)
    }

    /**
     * True when the installed assets match the ones bundled in this APK.
     *
     * Comparing filenames alone was not enough: after an app update the support
     * scripts on disk still have the same names, so the installer skipped and
     * the new scripts were never written. Every fix to startSSHServer.sh or the
     * bootstrap was silently discarded on any device that had already run once.
     *
     * A stamp file records the APK's version code, so an update always
     * reinstalls.
     */
    fun assetsAreInstalled(distributionType: String): Boolean {
        val destination = File(ulaFiles.filesDir, distributionType)
        if (!destination.isDirectory) return false
        val installed = destination.list()?.toSet() ?: return false
        if (!getBundledAssetList(distributionType).all { installed.contains(it.name) }) return false

        val stamp = File(destination, ASSET_VERSION_STAMP)
        return stamp.exists() && stamp.readText().trim() == currentAssetVersion()
    }

    private fun currentAssetVersion(): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            "${info.versionName}-${info.versionCode}"
        } catch (err: Exception) {
            "unknown"
        }
    }

    private fun writeVersionStamp(distributionType: String) {
        try {
            File(File(ulaFiles.filesDir, distributionType), ASSET_VERSION_STAMP)
                    .writeText(currentAssetVersion())
        } catch (err: Exception) {
            logger.addExceptionBreadcrumb(Exception("Could not write asset stamp"))
        }
    }

    companion object {
        private const val ASSET_VERSION_STAMP = ".asset_version"
    }
}
