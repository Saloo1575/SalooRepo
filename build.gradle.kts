plugins {
    id("com.android.library") version "8.5.2" apply false
    kotlin("android") version "2.0.21" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
