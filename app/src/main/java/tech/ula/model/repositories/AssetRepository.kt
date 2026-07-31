package tech.ula.model.repositories

import tech.ula.model.entities.Asset
import tech.ula.model.entities.Filesystem
import tech.ula.model.remote.OfficialArchiveResolver
import tech.ula.utils.* // ktlint-disable no-wildcard-imports
import tech.ula.utils.preferences.AssetPreferences
import java.io.File
import java.net.UnknownHostException

data class DownloadMetadata(
    val filename: String,
    val assetType: String,
    val versionCode: String,
    val url: String,
    val downloadTitle: String = "$assetType-$filename-$versionCode"
)

/**
 * Decides what still needs to be fetched before a filesystem can start.
 *
 * In this fork there are only ever two kinds of asset:
 *
 *  1. Support assets (proot helper scripts, busybox, selinux shim). These are
 *     bundled inside the APK and installed locally -- never downloaded.
 *  2. The root filesystem, which is fetched from the distribution's own
 *     official archive via [OfficialArchiveResolver].
 */
class AssetRepository(
    private val applicationFilesDirPath: String,
    private val assetPreferences: AssetPreferences,
    private val vendoredAssetInstaller: VendoredAssetInstaller,
    private val archiveResolver: OfficialArchiveResolver = OfficialArchiveResolver(),
    private val ulaFiles: UlaFiles,
    private val logger: Logger = SentryLogger()
) {

    @Throws(IllegalStateException::class, UnknownHostException::class)
    suspend fun generateDownloadRequirements(
        filesystem: Filesystem,
        assetList: List<Asset>,
        filesystemNeedsExtraction: Boolean
    ): List<DownloadMetadata> {
        val downloadRequirements = mutableListOf<DownloadMetadata>()

        // Support assets are installed from the APK, so an empty list here is no
        // longer a fatal condition -- install them and carry on.
        val repo = filesystem.distributionType
        if (!vendoredAssetInstaller.assetsAreInstalled(repo)) {
            vendoredAssetInstaller.installAssets(repo)
        }

        if (filesystemNeedsExtraction) {
            downloadRequirements.addAll(getRootFsDownloadRequirements(repo))
        }

        return downloadRequirements
    }

    fun getDistributionAssetsForExistingFilesystem(filesystem: Filesystem): List<Asset> {
        val assets = vendoredAssetInstaller.getBundledAssetList(filesystem.distributionType)
        return assets.filter { !it.name.contains("rootfs") }
    }

    fun getLatestDistributionVersion(distributionType: String): String {
        return assetPreferences.getLatestDownloadVersion(distributionType)
    }

    fun assetsArePresentInSupportDirectories(assets: List<Asset>): Boolean {
        for (asset in assets) {
            if (asset.name.contains("rootfs.tar.gz")) continue
            val assetFile = File("$applicationFilesDirPath/${asset.pathName}")
            if (!assetFile.exists()) return false
        }
        return true
    }

    /**
     * The support asset list now comes from the APK bundle rather than a remote
     * manifest, so this never touches the network.
     */
    @Throws(UnknownHostException::class)
    suspend fun getAssetList(distributionType: String): List<Asset> {
        val list = vendoredAssetInstaller.getBundledAssetList(distributionType)
        if (list.isNotEmpty()) {
            assetPreferences.setAssetList(distributionType, list)
            return list
        }
        return assetPreferences.getCachedAssetList(distributionType)
    }

    /**
     * Resolve the rootfs straight from the distribution's official archive.
     */
    private suspend fun getRootFsDownloadRequirements(repo: String): List<DownloadMetadata> {
        val filename = "rootfs.tar.gz"
        val rootFsFile = File("$applicationFilesDirPath/$repo/$filename")
        if (rootFsFile.exists()) return listOf()

        val arch = ulaFiles.getArchType()
        val url = archiveResolver.getRootFsUrl(repo, arch)
        // Official archives are versioned by their filename; use it as the code.
        val versionCode = url.substringAfterLast('/')
                .replace(Regex("[^0-9.]"), "")
                .trim('.')
                .ifEmpty { "latest" }

        val downloadMetadata = DownloadMetadata(filename, repo, versionCode, url)
        return listOf(downloadMetadata)
    }

    /** The official package mirror to configure inside the guest. */
    fun getPackageMirror(distributionType: String): String {
        return archiveResolver.getPackageMirror(distributionType, ulaFiles.getArchType())
    }
}
