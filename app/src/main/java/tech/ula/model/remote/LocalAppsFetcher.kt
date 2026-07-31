package tech.ula.model.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.ula.model.entities.App
import tech.ula.utils.Logger
import tech.ula.utils.SentryLogger
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Supplies the app catalog from assets bundled inside the APK.
 *
 * Upstream UserLAnd pulled this list, plus every icon, description and setup
 * script, from a third-party GitHub repository on each refresh. This fork ships
 * them in the APK so the catalog is available offline and immediately, and the
 * app never contacts a third party.
 */
class LocalAppsFetcher(
    private val filesDirPath: String,
    private val context: Context,
    private val logger: Logger = SentryLogger()
) {

    // Allows destructuring of the list of application elements
    private operator fun <T> List<T>.component6() = get(5)
    private operator fun <T> List<T>.component7() = get(6)

    private val assetRoot = "apps"

    @Throws(IOException::class)
    suspend fun fetchAppsList(): List<App> = withContext(Dispatchers.IO) {
        return@withContext try {
            val numLinesToSkip = 1 // Skip first line which defines schema
            val contents = context.assets.open("$assetRoot/apps.txt")
                    .bufferedReader()
                    .readLines()
            contents.drop(numLinesToSkip)
                    .filter { it.isNotBlank() }
                    .map { line ->
                        val (
                                name,
                                category,
                                filesystemRequired,
                                supportsCli,
                                supportsGui,
                                isPaidApp,
                                version
                        ) = line.toLowerCase(Locale.ENGLISH).split(", ")
                        App(
                                name,
                                category,
                                filesystemRequired,
                                supportsCli.toBoolean(),
                                supportsGui.toBoolean(),
                                // Every app is unlocked in this fork.
                                false,
                                version.toLong()
                        )
                    }
        } catch (err: Exception) {
            val exception = IOException("Error reading bundled apps list")
            logger.addExceptionBreadcrumb(exception)
            throw exception
        }
    }

    private suspend fun copyAsset(app: App, extension: String) = withContext(Dispatchers.IO) {
        val relative = "${app.name}/${app.name}.$extension"
        val target = File("$filesDirPath/apps/$relative")
        target.parentFile?.mkdirs()
        try {
            context.assets.open("$assetRoot/$relative").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (err: Exception) {
            // Not every app ships every file; absence is not fatal.
            logger.addExceptionBreadcrumb(Exception("Missing bundled asset: $relative"))
        }
    }

    suspend fun fetchAppIcon(app: App) = copyAsset(app, "png")

    suspend fun fetchAppDescription(app: App) = copyAsset(app, "txt")

    suspend fun fetchAppScript(app: App) = copyAsset(app, "sh")
}
