package com.jervis.common

import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant

/** Convert kotlinx.datetime.Instant to java.time.Instant */
fun Instant.toJavaInstant(): java.time.Instant = this.toJavaInstant()

/** Convert java.time.Instant to kotlinx.datetime.Instant */
fun java.time.Instant.toKotlinInstant(): Instant = this.toKotlinInstant()
