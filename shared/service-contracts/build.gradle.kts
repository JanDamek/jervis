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

// Generated gRPC/Protobuf stubs for `proto/jervis/*` live under
// `build/generated/source/buf/` and are **committed** (gitignore un-ignore
// at the repo root). Regeneration is driven by the top-level Makefile
// (`make proto-generate`) which calls `buf generate` + local grpc_tools.
//
// The Gradle module does NOT invoke `buf generate` itself because buf
// remote plugins (`buf.build/grpc/*`) aggressively rate-limit; chaining
// buf into every incremental Kotlin build produces spurious build
// failures. If the committed generated tree drifts from the proto
// sources, CI catches it via `make proto-verify`.
val bufGenerate by tasks.registering(Exec::class) {
    group = "proto"
    description = "Manually run `make proto-generate` from the repo root; this task is a thin wrapper kept for discoverability."
    workingDir = rootDir
    commandLine("make", "proto-generate")
    inputs.dir(protoDir)
    outputs.dir(generatedSourceRoot)
    outputs.dir(pythonGeneratedDir)
    outputs.upToDateWhen { generatedSourceRoot.get().asFile.exists() }
}

// Do NOT delete committed generated sources on `clean` — they're part of
// the source tree (see docs/inter-service-contracts.md §10).

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
