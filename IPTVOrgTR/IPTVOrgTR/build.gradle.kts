plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    description = "IPTV-org Türkiye canlı TV kanalları"
    authors = listOf("SALOO")
    status = 1
    tvTypes = listOf("Live")
    language = "tr"
}

android {
    namespace = "com.saloo.iptvorgtr"
}
