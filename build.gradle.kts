import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    application
    jacoco
    alias(libs.plugins.javafx)
    alias(libs.plugins.pitest)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.spotbugs)
    alias(libs.plugins.spotless)
}

group = "ru.onlychill"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls")
}

// Верифицируемый код (JML) компилируется вместе с основным
sourceSets.main {
    java.srcDir("src/verified/java")
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(libs.jspecify)
    implementation(libs.sqlite.jdbc)
    implementation(libs.flyway.core)
    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "ru.onlychill.bonds2.Main"
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
    options.errorprone {
        disableWarningsInGeneratedCode = true
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:AnnotatedPackages", "ru.onlychill.bonds2")
    }
    if (name == "compileTestJava") {
        options.errorprone.enabled = false
    }
}

// Без дисплея (CI): JavaFX работает в headless-режиме Monocle, который есть в JDK с JavaFX (Liberica Full)
val headlessJavaFxArgs =
    if (providers.gradleProperty("headless").map { it.toBoolean() }.getOrElse(false)) {
        listOf("-Dglass.platform=Monocle", "-Dmonocle.platform=Headless", "-Dprism.order=sw")
    } else {
        emptyList()
    }

tasks.test {
    useJUnitPlatform()
    jvmArgs(headlessJavaFxArgs)
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required = true
        html.required = true
    }
}

// Политика гейта покрытия (JaCoCo): покрытие измеряется юнит- и headless-тестами,
// контроллер UI при этом тестируем, поэтому из гейта исключается только не покрываемый
// автотестами клей запуска — класс Main и окно приложения.
val jacocoExcludedGlue =
    listOf("ru/onlychill/bonds2/Main.class", "ru/onlychill/bonds2/ui/MainWindowApp.class")

tasks.jacocoTestCoverageVerification {
    // GUI-обвязка (запуск JavaFX, показ окна) не покрывается автоматическими тестами
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) { exclude(jacocoExcludedGlue) }
            },
        ),
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = providers.gradleProperty("coverage.minLine").getOrElse("0.80").toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// Политика гейта мутаций (pitest): UI-слой не мутируем — его тесты тянут JavaFX-ожидания
// по 5–20 с, мутационная фаза становится wait-bound (CPU простаивает); логика сервисов
// покрыта юнит-тестами. Расхождение с перечнем гейта покрытия (JaCoCo, см. константу
// jacocoExcludedGlue) — осознанное: контроллер UI остаётся в гейте покрытия.
val uiPackage = setOf("ru.onlychill.bonds2.ui.*")
val pitestExcludedGlue = setOf("ru.onlychill.bonds2.Main") + uiPackage

pitest {
    pitestVersion =
        libs.versions.pitest.tool
            .get()
    junit5PluginVersion =
        libs.versions.pitest.junit5.plugin
            .get()
    targetClasses = setOf("ru.onlychill.bonds2.*")
    excludedClasses = pitestExcludedGlue
    excludedTestClasses = uiPackage
    mutationThreshold = providers.gradleProperty("mutation.threshold").getOrElse("70").toInt()
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
    threads = 8
    jvmArgs = headlessJavaFxArgs
}

spotbugs {
    toolVersion =
        libs.versions.spotbugs.tool
            .get()
    effort = com.github.spotbugs.snom.Effort.MAX
    reportLevel = com.github.spotbugs.snom.Confidence.MEDIUM
    ignoreFailures = false
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat()
        removeUnusedImports()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

// --- Формальная верификация (JML + OpenJML) ---
// OpenJML поставляется со своим JDK 21; проект целиком использует тот же JDK 21,
// поэтому ограничений на синтаксис верифицируемого кода сверх Java 21 нет.
// Версия — в каталоге версий (libs.versions.toml); -Popenjml.version переопределяет.
val openjmlVersion = providers.gradleProperty("openjml.version").orElse(libs.versions.openjml).get()
val openjmlHome = layout.projectDirectory.dir("tools/openjml-$openjmlVersion")
val openjmlScript = if (System.getProperty("os.name").lowercase().contains("win")) "openjml.bat" else "openjml"

val downloadOpenjml =
    tasks.register("downloadOpenjml") {
        group = "verification"
        description = "Скачивает зафиксированную версию OpenJML в tools/."
        val version = openjmlVersion
        val home = openjmlHome
        val script = openjmlScript
        val osName = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val isArm = arch == "aarch64" || arch == "arm64"
        val distribution =
            when {
                osName.contains("win") -> "windows"
                osName.contains("mac") -> if (isArm) "macos-arm64" else "macos-x86_64"
                else -> if (isArm) "ubuntu-arm64" else "ubuntu-24.04"
            }
        // Готовность — наличие исполняемого скрипта OpenJML в целевом пути, а не существование
        // каталога: пустой или битый каталог не должен навсегда отключать задачу.
        // outputs.dir сознательно не объявлен: Gradle создал бы пустой целевой каталог ещё до
        // выполнения, а отвергнутая загрузка не должна касаться tools/ вовсе.
        onlyIf { !home.file(script).asFile.isFile }
        doLast {
            val target = home.asFile
            val staging =
                layout.buildDirectory
                    .dir("openjml-staging")
                    .get()
                    .asFile
            val zip =
                layout.buildDirectory
                    .file("openjml-$version.zip")
                    .get()
                    .asFile
            zip.parentFile.mkdirs()
            val defaultUrl = "https://github.com/OpenJML/OpenJML/releases/download/$version/openjml-$distribution-$version.zip"
            val url = providers.gradleProperty("openjml.url").getOrElse(defaultUrl)
            logger.lifecycle("Скачивание $url")
            uri(url).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
            // Необязательная контрольная сумма (openjml.sha256): проверяется до распаковки;
            // несовпадение не трогает ни tools/, ни staging прошлой попытки.
            providers.gradleProperty("openjml.sha256").orNull?.let { expected ->
                val md = MessageDigest.getInstance("SHA-256")
                zip.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        md.update(buffer, 0, read)
                    }
                }
                val actual = md.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw GradleException(
                        "Контрольная сумма архива OpenJML не совпала: свойство $expected, файл $actual. " +
                            "Распаковка не выполнялась, tools/ не изменён.",
                    )
                }
            }
            try {
                staging.deleteRecursively() // мусор распаковки прерванной прошлой попытки
                copy {
                    from(zipTree(zip))
                    into(staging)
                }
            } catch (e: Exception) {
                staging.deleteRecursively()
                throw GradleException(
                    "Архив OpenJML не распаковывается — вероятно, повреждён (${e.message}). tools/ не изменён.",
                    e,
                )
            }
            if (!File(staging, script).isFile) {
                staging.deleteRecursively()
                throw GradleException(
                    "В архиве OpenJML нет исполняемого файла \"$script\": распределение не соответствует ожиданиям. tools/ не изменён.",
                )
            }
            // Битые остатки прошлой попытки заменяются целиком; установка на место —
            // атомарным перемещением в пределах файловой системы проекта.
            if (target.exists()) target.deleteRecursively()
            target.parentFile.mkdirs() // свежий клон: каталога tools/ может ещё не быть
            try {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                throw GradleException(
                    "Не удалось атомарно установить OpenJML в $target (staging остался в $staging): ${e.message}",
                    e,
                )
            }
            zip.delete()
        }
    }

val openjmlProve =
    tasks.register<Exec>("openjmlProve") {
        group = "verification"
        description = "Статически доказывает JML-контракты кода из src/verified/java (OpenJML ESC)."
        dependsOn(downloadOpenjml)
        val verifiedSources = fileTree("src/verified/java") { include("**/*.java") }
        inputs.files(verifiedSources)
        inputs.property("openjmlVersion", openjmlVersion)
        val home = openjmlHome
        doFirst {
            executable = home.file(openjmlScript).asFile.absolutePath
            args(listOf("--esc") + verifiedSources.files.map { it.absolutePath })
        }
    }
