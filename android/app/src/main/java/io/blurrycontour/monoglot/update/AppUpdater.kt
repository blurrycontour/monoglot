package io.blurrycontour.monoglot.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import io.blurrycontour.monoglot.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class AppVersion(
    @SerialName("version_code") val versionCode: Int = 0,
    @SerialName("version_name") val versionName: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("built_at") val builtAt: String = "",
    @SerialName("download_url") val downloadUrl: String = "/download/monoglot.apk",
    val available: Boolean = false,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: AppVersion) : UpdateState
    /** [progress] is 0..1, or null while the total size is unknown. */
    data class Downloading(val version: AppVersion, val progress: Float, val bytes: Long) : UpdateState
    data class ReadyToInstall(val version: AppVersion) : UpdateState
    data class Installing(val version: AppVersion) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-update against the same server the content comes from.
 *
 * The APK is downloaded inside the app with visible progress, then handed to
 * Android's PackageInstaller. Downloading in-app rather than sending the user
 * to a browser is the whole point: the download is the part worth watching.
 */
class AppUpdater(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    fun reset() { _state.value = UpdateState.Idle }

    /**
     * @param silent when true, an up-to-date result leaves the state Idle so a
     *   launch check never interrupts with "you are up to date".
     */
    suspend fun check(serverUrl: String, silent: Boolean = false): UpdateState {
        if (serverUrl.isBlank()) return UpdateState.Idle
        _state.value = if (silent) UpdateState.Idle else UpdateState.Checking

        val result = runCatching {
            withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url(serverUrl.trimEnd('/') + "/api/app/version")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    json.decodeFromString<AppVersion>(resp.body?.string().orEmpty())
                }
            }
        }

        val next = result.fold(
            onSuccess = { remote ->
                when {
                    !remote.available -> if (silent) UpdateState.Idle else UpdateState.UpToDate
                    remote.versionCode > currentVersionCode -> UpdateState.Available(remote)
                    else -> if (silent) UpdateState.Idle else UpdateState.UpToDate
                }
            },
            onFailure = {
                if (silent) UpdateState.Idle
                else UpdateState.Failed(it.message ?: "Could not reach server")
            },
        )
        _state.value = next
        return next
    }

    /** Downloads the APK, reporting progress, then installs it. */
    suspend fun downloadAndInstall(serverUrl: String, version: AppVersion) {
        _state.value = UpdateState.Downloading(version, 0f, 0)

        val apk = runCatching {
            withContext(Dispatchers.IO) {
                val dest = File(context.cacheDir, "update.apk")
                dest.delete()

                val req = Request.Builder()
                    .url(serverUrl.trimEnd('/') + version.downloadUrl)
                    .build()

                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("empty response")
                    val total = if (body.contentLength() > 0) body.contentLength()
                                else version.sizeBytes

                    body.byteStream().use { input ->
                        dest.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                output.write(buffer, 0, n)
                                written += n
                                val progress = if (total > 0) (written.toFloat() / total) else 0f
                                _state.value = UpdateState.Downloading(
                                    version, progress.coerceIn(0f, 1f), written,
                                )
                            }
                        }
                    }
                }
                dest
            }
        }.getOrElse {
            _state.value = UpdateState.Failed(it.message ?: "Download failed")
            return
        }

        _state.value = UpdateState.ReadyToInstall(version)
        install(apk, version)
    }

    /**
     * Hands the APK to PackageInstaller. Android still shows its own
     * confirmation, and requires the user to have granted "install unknown
     * apps" to this app once.
     */
    private suspend fun install(apk: File, version: AppVersion) {
        _state.value = UpdateState.Installing(version)
        runCatching {
            withContext(Dispatchers.IO) {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                ).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED)
                    }
                }
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("monoglot", 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val intent = Intent(context, InstallReceiver::class.java)
                    val pending = android.app.PendingIntent.getBroadcast(
                        context, sessionId, intent,
                        android.app.PendingIntent.FLAG_MUTABLE or
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    session.commit(pending.intentSender)
                }
            }
        }.onFailure {
            _state.value = UpdateState.Failed(it.message ?: "Install failed")
        }
    }

    /** Whether the OS will let this app install packages at all. */
    fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true

    fun installPermissionIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
}
