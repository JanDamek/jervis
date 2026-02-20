package com.jervis.di

/**
 * Thrown when an RPC operation is attempted while the app is offline.
 * Callers should catch this to gracefully degrade to cached/offline data.
 */
class OfflineException(message: String = "App is offline") : Exception(message)
