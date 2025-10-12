rootProject.name = "jervis"

include(":common", ":core-server", ":desktop", ":android", ":ios")

project(":android").projectDir = file("jervis/android")
project(":desktop").projectDir = file("jervis/desktop")
project(":ios").projectDir = file("jervis/ios")
project(":core-server").projectDir = file("jervis/server")
project(":common").projectDir = file("jervis/common")
