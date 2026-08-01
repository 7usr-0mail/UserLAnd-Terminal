package tech.ula.model.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tech.ula.utils.Logger
import tech.ula.utils.SentryLogger
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Resolves root filesystem tarballs directly from the *official* archives of each
 * distribution, rather than from any third-party mirror.
 *
 *   Ubuntu  -> cdimage.ubuntu.com / archive.ubuntu.com  (Canonical)
 *   Debian  -> deb.debian.org / debuerreotype           (Debian project)
 *   Alpine  -> dl-cdn.alpinelinux.org                   (Alpine project)
 *   Arch    -> os-archive / geo mirror                  (Arch Linux ARM / Arch)
 *
 * Each resolver discovers the newest published build by parsing the archive's
 * own directory index, so the app keeps working as new point releases land
 * without needing an app update.
 */
class OfficialArchiveResolver(
    private val logger: Logger = SentryLogger()
) {

    private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    companion object {
        // Canonical's official image archive.
        const val UBUNTU_CDIMAGE = "https://cdimage.ubuntu.com/ubuntu-base/releases"
        // Ubuntu package archive, used for apt sources inside the guest.
        const val UBUNTU_ARCHIVE = "http://archive.ubuntu.com/ubuntu"
        const val UBUNTU_PORTS = "http://ports.ubuntu.com/ubuntu-ports"

        // The Debian project's official CDN.
        // Plain HTTP: the pristine base images ship no ca-certificates, so apt
        // cannot complete a TLS handshake. Integrity still comes from the signed
        // Release files rather than the transport.
        const val DEBIAN_ARCHIVE = "http://deb.debian.org/debian"
        // Official debuerreotype-produced rootfs published by the Debian project.
        const val DEBIAN_ROOTFS_BASE = "https://github.com/debuerreotype/docker-debian-artifacts/raw"

        // The Alpine project's official CDN.
        const val ALPINE_CDN = "http://dl-cdn.alpinelinux.org/alpine"

        // Arch Linux ARM official downloads, and Arch's official bootstrap archive.
        const val ARCH_ARM = "http://os-archive.archlinuxarm.org/os"
        const val ARCH_X86_BOOTSTRAP = "https://geo.mirror.pkgbuild.com/iso/latest"

        // Fallback pinned versions, used when directory listing cannot be reached.
        const val UBUNTU_FALLBACK_RELEASE = "24.04"
        const val UBUNTU_FALLBACK_POINT = "24.04.4"
        const val ALPINE_FALLBACK_BRANCH = "v3.20"
        const val ALPINE_FALLBACK_VERSION = "3.20.9"
    }

    /**
     * The architecture naming used by each official archive differs from the
     * internal UserLAnd arch names, so translate per-distribution.
     */
    private fun ubuntuArch(arch: String): String = when (arch) {
        "arm64" -> "arm64"
        "arm" -> "armhf"
        "x86_64" -> "amd64"
        "x86" -> "i386"
        else -> throw IOException("Unsupported architecture for Ubuntu: $arch")
    }

    private fun debianArch(arch: String): String = when (arch) {
        "arm64" -> "arm64"
        "arm" -> "armhf"
        "x86_64" -> "amd64"
        "x86" -> "i386"
        else -> throw IOException("Unsupported architecture for Debian: $arch")
    }

    private fun alpineArch(arch: String): String = when (arch) {
        "arm64" -> "aarch64"
        "arm" -> "armv7"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> throw IOException("Unsupported architecture for Alpine: $arch")
    }

    @Throws(IOException::class)
    private suspend fun fetchIndex(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Could not read archive index: $url")
            return@withContext response.body?.string() ?: ""
        }
    }

    /**
     * Returns the download URL of the official rootfs tarball for a distribution.
     */
    @Throws(IOException::class)
    suspend fun getRootFsUrl(distributionType: String, arch: String): String {
        return when (distributionType.toLowerCase()) {
            "ubuntu" -> ubuntuRootFsUrl(arch)
            "debian" -> debianRootFsUrl(arch)
            "alpine" -> alpineRootFsUrl(arch)
            "arch" -> archRootFsUrl(arch)
            else -> throw IOException("No official archive is known for: $distributionType")
        }
    }

    /**
     * Ubuntu publishes `ubuntu-base-<version>-base-<arch>.tar.gz` under
     * cdimage.ubuntu.com. Discover the newest point release of the LTS series.
     */
    private suspend fun ubuntuRootFsUrl(arch: String): String {
        val a = ubuntuArch(arch)
        val release = UBUNTU_FALLBACK_RELEASE
        val point = try {
            val index = fetchIndex("$UBUNTU_CDIMAGE/$release/release/")
            val regex = Regex("""ubuntu-base-([0-9.]+)-base-$a\.tar\.gz""")
            regex.findAll(index)
                    .map { it.groupValues[1] }
                    .distinct()
                    .toList()
                    .maxWithOrNull(VersionComparator) ?: UBUNTU_FALLBACK_POINT
        } catch (err: Exception) {
            logger.addExceptionBreadcrumb(Exception("Ubuntu index unreachable, using pinned release"))
            UBUNTU_FALLBACK_POINT
        }
        return "$UBUNTU_CDIMAGE/$release/release/ubuntu-base-$point-base-$a.tar.gz"
    }

    /**
     * The Debian project publishes official debuerreotype-built rootfs tarballs.
     */
    private fun debianRootFsUrl(arch: String): String {
        val a = debianArch(arch)
        return "$DEBIAN_ROOTFS_BASE/dist-$a/$a/stable/slim/rootfs.tar.xz"
    }

    /**
     * Alpine publishes `alpine-minirootfs-<version>-<arch>.tar.gz` on its CDN.
     */
    private suspend fun alpineRootFsUrl(arch: String): String {
        val a = alpineArch(arch)
        val branch = ALPINE_FALLBACK_BRANCH
        val version = try {
            val index = fetchIndex("$ALPINE_CDN/$branch/releases/$a/")
            val regex = Regex("""alpine-minirootfs-([0-9.]+)-$a\.tar\.gz""")
            regex.findAll(index)
                    .map { it.groupValues[1] }
                    .distinct()
                    .toList()
                    .maxWithOrNull(VersionComparator) ?: ALPINE_FALLBACK_VERSION
        } catch (err: Exception) {
            logger.addExceptionBreadcrumb(Exception("Alpine index unreachable, using pinned release"))
            ALPINE_FALLBACK_VERSION
        }
        return "$ALPINE_CDN/$branch/releases/$a/alpine-minirootfs-$version-$a.tar.gz"
    }

    /**
     * Arch Linux ARM publishes per-architecture rootfs tarballs; x86_64 uses the
     * official bootstrap archive from Arch's own mirror network.
     */
    private fun archRootFsUrl(arch: String): String {
        return when (arch) {
            "arm64" -> "$ARCH_ARM/ArchLinuxARM-aarch64-latest.tar.gz"
            "arm" -> "$ARCH_ARM/ArchLinuxARM-armv7-latest.tar.gz"
            "x86_64" -> "$ARCH_X86_BOOTSTRAP/archlinux-bootstrap-x86_64.tar.zst"
            else -> throw IOException("Arch Linux does not publish an official image for: $arch")
        }
    }

    /**
     * The apt/apk mirror that should be configured inside the guest filesystem,
     * always pointing at the distribution's official archive.
     */
    fun getPackageMirror(distributionType: String, arch: String): String {
        return when (distributionType.toLowerCase()) {
            // Canonical serves x86 from archive.ubuntu.com and everything else from ports.
            "ubuntu" -> if (arch == "x86_64" || arch == "x86") UBUNTU_ARCHIVE else UBUNTU_PORTS
            "debian" -> DEBIAN_ARCHIVE
            "alpine" -> "$ALPINE_CDN/$ALPINE_FALLBACK_BRANCH/main"
            "arch" -> if (arch.startsWith("arm")) "http://mirror.archlinuxarm.org" else "https://geo.mirror.pkgbuild.com"
            else -> ""
        }
    }

    /** Compares dotted version strings numerically (so 24.04.10 > 24.04.9). */
    object VersionComparator : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            val x = a.split(".").map { it.toIntOrNull() ?: 0 }
            val y = b.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val diff = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                if (diff != 0) return diff
            }
            return 0
        }
    }
}
