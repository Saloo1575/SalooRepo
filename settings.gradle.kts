rootProject.name = "SalooRepo"

// Gradle derlemesine dahil edilmeyecek modüller:
//  - ExampleProvider : CloudStream şablon demo provider'ı (gerçek içerik sağlamaz;
//    yeni provider oluştururken şablon olarak kullanılır)
// NOT: Daha önce eklenen tüm provider/site modülleri temizlendi; otomasyon katmanı
// (settings auto-include, workflows, registry JSON'ları) aynen korundu. Yeni bir
// provider modülü build.gradle.kts içeren klasör olarak eklendiğinde otomatik
// olarak derlemeye dahil olur.
val disabled = listOf("ExampleProvider")

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
