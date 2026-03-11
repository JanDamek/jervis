# kotlin-logging logback adapter — not used on desktop (uses slf4j-simple)
-dontwarn ch.qos.logback.**
-dontwarn io.github.oshai.kotlinlogging.logback.**
-dontwarn com.oracle.svm.core.annotate.**

# Ktor internal classes
-dontwarn io.ktor.utils.io.jvm.javaio.**

# Android classes not present on desktop
-dontwarn android.**

# kotlinx-rpc reflection
-keep class kotlinx.rpc.** { *; }
-dontwarn kotlinx.rpc.**

# Ignore incomplete class hierarchy from transitive logback dependency
-ignorewarnings
