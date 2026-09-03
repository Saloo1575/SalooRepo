import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "Saloo1575/SalooRepo")
    }

    android {
        namespace = "com.example"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)

                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions",
                    "-Xjspecify-annotations=ignore"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        cloudstream("com.lagradost:cloudstream3:pre-release")
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.11")
        implementation("org.jsoup:jsoup:1.18.3")
        // jsoup 1.18.3 API'si jspecify anotasyonlarıyla işaretli; jspecify'ı classpath'e
        // eklemezsek K2 "Type annotation class 'org.jspecify.annotations.Nullable' of the
        // inferred type is inaccessible" derleme hatası verir (ör. AltiYuzAltmisAltiFilmIzle).
        implementation("org.jspecify:jspecify:1.0.0")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        // Orijinal Kekik-cloudstream root build.gradle.kts'inden aynen alındı:
        // implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
        // (W2MExtractor orijinal kodu withContext/delay/Dispatchers kullanıyor.)
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

// ---------------------------------------------------------------------------
// CI guard for the empty-repository state (0 provider modules)
// ---------------------------------------------------------------------------
// `make` and `makePluginsJson` are contributed by the CloudStream gradle plugin
// for every provider module. While the repository has no provider modules,
// `./gradlew make makePluginsJson` (used by build.yml / release.yml) would fail
// with "Task 'make' not found". Register no-op stand-ins ONLY when no module
// provides them, so that:
//   * 0 providers -> build succeeds and an empty build/plugins.json placeholder
//     is written (keeps the builds branch consistent with the registry)
//   * N providers -> the real CloudStream build tasks are used unchanged and
//     the guard is not registered at all
// The auto-include mechanism in settings.gradle.kts is not modified.
// ---------------------------------------------------------------------------
gradle.projectsEvaluated {
    // Kotlin DSL notu: projectsEvaluated extension'i receiver-stillidir
    // (this: Gradle); 'it' referansi DERLENMEZ. Onceki 'val root = it.rootProject'
    // satiri build'i kiriyoordu (Unresolved reference: it).
    val root = rootProject
    val anyModuleProvidesMake = root.subprojects.any { sub -> sub.tasks.findByName("make") != null }
    if (!anyModuleProvidesMake) {
        root.tasks.register("make") {
            group = "build"
            description = "No-op guard: no provider modules found, nothing to build."
            doLast {
                println("[saloo-guard] 0 provider modules; 'make' completed as no-op.")
            }
        }
        root.tasks.register("makePluginsJson") {
            group = "build"
            description = "No-op guard: writes an empty plugins.json placeholder."
            doLast {
                val out = root.layout.buildDirectory.file("plugins.json").get().asFile
                out.parentFile.mkdirs()
                out.writeText("[]\n")
                println("[saloo-guard] 0 provider modules; wrote empty plugins.json: ${out.absolutePath}")
            }
        }
    }
}
