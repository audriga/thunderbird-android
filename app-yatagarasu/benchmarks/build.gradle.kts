plugins {
    alias(libs.plugins.tb.baselineprofile.producer.app)
}

android {
    namespace = "com.audriga.yatagarasu.benchmarks"
    targetProjectPath = ":app-yatagarasu"

    buildTypes {
        create("beta") {
            matchingFallbacks += listOf("release")
        }
        create("daily") {
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    implementation(projects.benchmarks)
}
