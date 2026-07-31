package tech.ula.utils

import android.content.Context
import android.util.Log
import tech.ula.viewmodel.IllegalState

sealed class BreadcrumbType {
    // These types override toString with short return values so they are easily
    // identified in logcat.
    object ReceivedIntent : BreadcrumbType() {
        override fun toString(): String {
            return "Intent received"
        }
    }
    object SubmittedEvent : BreadcrumbType() {
        override fun toString(): String {
            return "Event submitted"
        }
    }
    object ReceivedEvent : BreadcrumbType() {
        override fun toString(): String {
            return "Event received"
        }
    }
    object ObservedState : BreadcrumbType() {
        override fun toString(): String {
            return "State observed"
        }
    }
    object RuntimeError : BreadcrumbType() {
        override fun toString(): String {
            return "Runtime error"
        }
    }
}

data class UlaBreadcrumb(
    val originatingClass: String,
    val type: BreadcrumbType,
    val details: String
)

interface Logger {
    fun initialize(context: Context? = null)

    fun addBreadcrumb(breadcrumb: UlaBreadcrumb)

    fun addExceptionBreadcrumb(err: Exception)

    fun sendIllegalStateLog(state: IllegalState)

    fun sendEvent(message: String)
}

/**
 * A fully local logger. This fork sends no telemetry off-device: every breadcrumb,
 * exception and event is written to logcat only. Nothing is transmitted anywhere.
 */
class SentryLogger : Logger {
    private val tag = "Terminal"

    override fun initialize(context: Context?) {
        // Intentionally a no-op. No crash reporting backend is initialized.
    }

    override fun addBreadcrumb(breadcrumb: UlaBreadcrumb) {
        Log.i("$tag/Breadcrumb", "${breadcrumb.type} ${breadcrumb.originatingClass}: ${breadcrumb.details}")
    }

    override fun addExceptionBreadcrumb(err: Exception) {
        val stackTrace = err.stackTrace.firstOrNull()
        Log.w("$tag/Exception", "${err.javaClass.simpleName} at " +
                "${stackTrace?.fileName}:${stackTrace?.lineNumber} - ${err.message}")
    }

    override fun sendIllegalStateLog(state: IllegalState) {
        Log.e("$tag/ILLEGAL_STATE", state.javaClass.simpleName)
    }

    override fun sendEvent(message: String) {
        Log.e("$tag/EVENT", message)
    }
}
