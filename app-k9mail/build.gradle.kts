plugins {
    id(ThunderbirdPlugins.App.androidCompose)
    alias(libs.plugins.dependency.guard)
    id("thunderbird.quality.badging")
}

val testCoverageEnabled: Boolean by extra
if (testCoverageEnabled) {
    apply(plugin = "jacoco")
}

dependencies {
    implementation(projects.appCommon)
    implementation(projects.core.ui.compose.theme2.k9mail)
    implementation(projects.core.ui.legacy.theme2.k9mail)
    implementation(projects.feature.launcher)

    implementation(projects.legacy.core)
    implementation(projects.legacy.ui.legacy)

    implementation(projects.core.featureflags)

    implementation(projects.feature.widget.messageList)
    implementation(projects.feature.widget.shortcut)
    implementation(projects.feature.widget.unread)
    implementation(projects.feature.telemetry.noop)

    implementation(libs.androidx.work.runtime)

    implementation(projects.feature.autodiscovery.api)
    implementation(libs.mustache)
    implementation(libs.jackson)

    // ./gradlew app-k9mail:dependencies > dependencies.log
    // https://stackoverflow.com/questions/21645071/using-gradle-to-find-dependency-tree

    // Exclude: https://stackoverflow.com/questions/56027250/how-to-resolve-duplicate-class-error-in-gradle-build

    // https://stackoverflow.com/questions/56695106/duplicated-classes-found-in-modules-classes-jar

    // TODO
    //implementation(libs.ical4j)
    //implementation(libs.ical4jvcard)
    implementation(libs.ical4jserializer)

    implementation(files("../libs/h2lj.jar"))
    implementation(files("../libs/hetc.jar"))
    implementation(files("../libs/ld2h.jar"))

    //implementation files('libs/h2lj.jar', 'libs/hetc.jar', 'libs/ld2h.jar')

    debugImplementation(projects.backend.demo)
    debugImplementation(projects.feature.autodiscovery.demo)

    testImplementation(libs.robolectric)

    // Required for DependencyInjectionTest to be able to resolve OpenPgpApiManager
    testImplementation(projects.plugins.openpgpApiLib.openpgpApi)
    testImplementation(projects.feature.account.setup)
}

android {
    namespace = "com.fsck.k9"

    defaultConfig {
        applicationId = "com.fsck.k9"
        testApplicationId = "com.fsck.k9.tests"

        versionCode = 39004
        versionName = "6.905-SNAPSHOT"

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

        buildConfigField("String", "CLIENT_INFO_APP_NAME", "\"K-9 Mail\"")
    }

    signingConfigs {
        createSigningConfig(project, SigningType.K9_RELEASE, isUpload = false)
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByType(SigningType.K9_RELEASE)

            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro",
            )
        }

        debug {
            applicationIdSuffix = ".debug"
            enableUnitTestCoverage = testCoverageEnabled
            enableAndroidTestCoverage = testCoverageEnabled

            isMinifyEnabled = false
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

dependencyGuard {
    configuration("releaseRuntimeClasspath")
}
