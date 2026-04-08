package com.jbselfcompany.tyr.utils

import android.util.Log

/**
 * Application-wide logger with runtime enable/disable support.
 *
 * Logging can be toggled without restarting the service via [setEnabled].
 * Errors (Log.e) are always written regardless of the enabled flag — they
 * signal real failures that must be visible for crash investigation.
 *
 * Usage:
 *   TyrLogger.d(TAG, "message")
 *   TyrLogger.i(TAG, "message")
 *   TyrLogger.w(TAG, "message")
 *   TyrLogger.e(TAG, "message", exception)  // always logs
 */
object TyrLogger {

    @Volatile
    private var enabled: Boolean = false

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    fun d(tag: String, msg: String) {
        if (enabled) Log.d(tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (enabled) Log.i(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (enabled) {
            if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        }
    }

    /** Errors are always logged regardless of the enabled flag. */
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
    }
}
