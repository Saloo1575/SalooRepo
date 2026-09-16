rootProject.name = "SalooRepo"

// Gradle derlemesine dahil edilmeyecek modüller:
//  - ExampleProvider : CloudStream şablon demo provider'ı (gerçek içerik sağlamaz;
//    yeni provider oluştururken şablon olarak kullanılır)
// NOT: Daha önce eklenen tüm provider/site modülleri temizlendi; otomasyon katmanı
// (settings auto-include, workflows, registry JSON'ları) aynen korundu. Yeni bir
// provider modülü build.gradle.kts içeren klasör olarak eklendiğinde otomatik
// olarak derlemeye dahil olur.
val disabled = listOf("ExampleProvider")

// §74A: Dizilla modülü. Aşağıdaki auto-include döngüsü de build.gradle.kts
// içeren tüm kök klasörlerini kapsar; Gradle aynı project path için include'u
// idempotent işlediğinden açık kayıt çift include sorununa yol açmaz.
include("Dizilla")

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
