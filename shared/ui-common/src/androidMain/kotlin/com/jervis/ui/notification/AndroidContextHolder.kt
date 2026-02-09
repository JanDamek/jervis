package com.jervis.ui.notification

import android.content.Context

/**
 * Holds reference to application context for notification services.
 *
 * Must be initialized from MainActivity.onCreate() before any
 * notification operations are performed.
 */
object AndroidContextHolder {
    lateinit var applicationContext: Context
        private set

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }
}
