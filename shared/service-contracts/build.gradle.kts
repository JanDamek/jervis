// Pod-to-pod contracts module — gRPC + Protobuf generated stubs for every
// `jervis/<domain>/*.proto`. Source of truth lives in `proto/` at repo root.
// `buf generate` (run via the `bufGenerate` task or the top-level Makefile)
// populates `build/generated/source/buf/{java,grpc-java,grpc-kotlin}`.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "com.jervis"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

val generatedSourceRoot = layout.buildDirectory.dir("generated/source/buf")

sourceSets {
    main {
        java.srcDir(generatedSourceRoot.map { it.dir("java") })
        java.srcDir(generatedSourceRoot.map { it.dir("grpc-java") })
        kotlin.srcDir(generatedSourceRoot.map { it.dir("grpc-kotlin") })
    }
}

val protoDir = rootDir.resolve("proto")
val pythonGeneratedDir = rootDir.resolve("libs/jervis_contracts/jervis_contracts/_generated")

val bufGenerate by tasks.registering(Exec::class) {
    group = "proto"
    description = "Runs `buf generate` against proto/ — writes Java/Kotlin stubs into build/generated and Python stubs into libs/jervis_contracts."
    workingDir = protoDir
    commandLine("buf", "generate")
    inputs.dir(protoDir)
    outputs.dir(generatedSourceRoot)
    outputs.dir(pythonGeneratedDir)
}

tasks.named("compileKotlin") { dependsOn(bufGenerate) }
tasks.named("compileJava") { dependsOn(bufGenerate) }

tasks.named<Delete>("clean") {
    delete(generatedSourceRoot)
}

dependencies {
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    api(libs.grpc.kotlin.stub)
    api(libs.grpc.netty.shaded)
    api(libs.grpc.services)
    api(libs.protobuf.java)
    api(libs.protobuf.kotlin)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
}
