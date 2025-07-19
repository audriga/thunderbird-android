plugins {
    id(ThunderbirdPlugins.App.androidCompose)
    alias(libs.plugins.dependency.guard)
    id("thunderbird.quality.badging")
}

val testCoverageEnabled: Boolean by extra
if (testCoverageEnabled) {
    apply(plugin = "jacoco")
}

android {
    namespace = "com.audriga.yatagarasu.android"

    defaultConfig {
        applicationId = "com.audriga.yatagarasu.android"
        testApplicationId = "com.audriga.yatagarasu.tests"

        versionCode = 3
        versionName = "0.3"

        // Keep in sync with the resource string array "supported_languages"
        resourceConfigurations.addAll(
            listOf(
                "ar",
                "be",
                "bg",
                "br",
                "ca",
                "co",
                "cs",
                "cy",
                "da",
                "de",
                "el",
                "en",
                "en_GB",
                "eo",
                "es",
                "et",
                "eu",
                "fa",
                "fi",
                "fr",
                "fy",
                "gd",
                "gl",
                "hr",
                "hu",
                "in",
                "is",
                "it",
                "iw",
                "ja",
                "ko",
                "lt",
                "lv",
                "ml",
                "nb",
                "nl",
                "pl",
                "pt_BR",
                "pt_PT",
                "ro",
                "ru",
                "sk",
                "sl",
                "sq",
                "sr",
                "sv",
                "tr",
                "uk",
                "vi",
                "zh_CN",
                "zh_TW",
            ),
        )

        buildConfigField("String", "CLIENT_INFO_APP_NAME", "\"Yatagarasu Mail\"")
    }

    signingConfigs {
//        createSigningConfig(project, SigningType.TB_RELEASE)
//        createSigningConfig(project, SigningType.TB_BETA)
//        createSigningConfig(project, SigningType.TB_DAILY)
        createSigningConfig(project, SigningType.YG_RELEASE)
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-SNAPSHOT"

            isShrinkResources = false
            isDebuggable = false

            buildConfigField("String", "RELEASE_CHANNEL", "null")
            signingConfig = signingConfigs.getByType(SigningType.YG_RELEASE)
            isMinifyEnabled = false
        }

        release {

            signingConfig = signingConfigs.getByType(SigningType.YG_RELEASE)
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false

            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro",
            )

            buildConfigField("String", "RELEASE_CHANNEL", "\"release\"")
        }

        create("beta") {

            signingConfig = signingConfigs.getByType(SigningType.YG_RELEASE)
            applicationIdSuffix = ".beta"
            versionNameSuffix = "b1"

            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false

            matchingFallbacks += listOf("release")

            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro",
            )

            buildConfigField("String", "RELEASE_CHANNEL", "\"beta\"")
        }

        create("daily") {
            signingConfig = signingConfigs.getByType(SigningType.YG_RELEASE)

            applicationIdSuffix = ".daily"
            versionNameSuffix = "a1"

            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false

            matchingFallbacks += listOf("release")

            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro",
            )

            buildConfigField("String", "RELEASE_CHANNEL", "\"daily\"")
        }
    }

    packaging {
        jniLibs {
            excludes += listOf("kotlin/**")
        }

        resources {
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                 "META-INF/*.md", // TODO
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }
}

dependencies {
    implementation(projects.appCommon)
    implementation(projects.core.ui.compose.theme2.yatagarasu)
    implementation(projects.core.ui.legacy.theme2.yatagarasu)
    implementation(projects.feature.launcher)

    implementation(projects.legacy.core)
    implementation(projects.legacy.ui.legacy)

    implementation(projects.core.featureflags)

    implementation(projects.feature.widget.messageList)
    implementation(projects.feature.widget.shortcut)
    implementation(projects.feature.widget.unread)
    implementation(libs.mustache)
    implementation(libs.jackson)

    // TODO
    //implementation(libs.ical4j)
    //implementation(libs.ical4jvcard)
    implementation(libs.ical4jserializer)

    implementation(files("../libs/h2lj.jar"))
    implementation(files("../libs/hetc.jar"))
    implementation(files("../libs/ld2h.jar"))

    //implementation files('libs/h2lj.jar', 'libs/hetc.jar', 'libs/ld2h.jar')

    debugImplementation(projects.feature.telemetry.noop)
    releaseImplementation(projects.feature.telemetry.glean)
    "betaImplementation"(projects.feature.telemetry.glean)
    "dailyImplementation"(projects.feature.telemetry.glean)

    implementation(libs.androidx.work.runtime)

    implementation(projects.feature.autodiscovery.api)
    debugImplementation(projects.backend.demo)
    debugImplementation(projects.feature.autodiscovery.demo)

    testImplementation(libs.robolectric)

    // Required for DependencyInjectionTest to be able to resolve OpenPgpApiManager
    testImplementation(projects.plugins.openpgpApiLib.openpgpApi)
    testImplementation(projects.feature.account.setup)
}

dependencyGuard {
    configuration("releaseRuntimeClasspath")
}
