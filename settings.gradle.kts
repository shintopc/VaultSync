plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "VaultSync"
include(":app")
include(":shared")
include(":shared-lib")
include(":plain-common")
include(":plain-ui")
include(":ui-check")
