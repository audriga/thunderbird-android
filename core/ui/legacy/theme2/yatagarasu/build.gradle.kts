plugins {
    id(ThunderbirdPlugins.Library.android)
}

android {
    namespace = "app.k9mail.core.ui.legacy.theme2.audriga"
}

dependencies {
    implementation(projects.core.ui.legacy.theme2.common)
}
