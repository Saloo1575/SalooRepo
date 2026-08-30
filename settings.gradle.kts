rootProject.name = "SalooRepo"

// Gradle derlemesine dahil edilmeyecek modüller:
//  - __Temel         : tamamlanmamış taslak (loadLinks TODO, version=0, SetFilmIzle'nin kopyası)
//  - ExampleProvider : CloudStream şablon demo provider'ı (gerçek içerik sağlamaz)
// NOT: InternetArchiveProvider ve IPTVOrgTR gerçek providerlardır, disabled listesine EKLENMEMELİ.
// (AltiYuzAltmisAltiFilmIzle geçici olarak buradaydı; jspecify bağımlılığı eklenince geri açıldı.)
val disabled = listOf("__Temel", "ExampleProvider")

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()
        ?.filter { it.isDirectory }
        ?.forEach { block(it) }
}
