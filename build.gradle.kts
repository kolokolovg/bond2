import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    application
    jacoco
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

// Верифицируемый код (JML) компилируется вместе с основным
sourceSets.main {
    java.srcDir("src/verified/java")
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(libs.jspecify)
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

tasks.test {
    useJUnitPlatform()
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

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = providers.gradleProperty("coverage.minLine").map { it.toBigDecimal() }.get()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

pitest {
    pitestVersion = "1.30.1"
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("ru.onlychill.bonds2.*")
    excludedClasses = setOf("ru.onlychill.bonds2.Main")
    mutationThreshold = providers.gradleProperty("mutation.threshold").map { it.toInt() }
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
    threads = 4
}

spotbugs {
    toolVersion = "4.10.5"
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
val openjmlVersion = providers.gradleProperty("openjml.version").get()
val openjmlHome = layout.projectDirectory.dir("tools/openjml-$openjmlVersion")

val downloadOpenjml =
    tasks.register("downloadOpenjml") {
        group = "verification"
        description = "Скачивает зафиксированную версию OpenJML в tools/."
        val version = openjmlVersion
        val home = openjmlHome
        val osName = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val isArm = arch == "aarch64" || arch == "arm64"
        val distribution =
            when {
                osName.contains("win") -> "windows"
                osName.contains("mac") -> if (isArm) "macos-arm64" else "macos-x86_64"
                else -> if (isArm) "ubuntu-arm64" else "ubuntu-24.04"
            }
        outputs.dir(home)
        onlyIf { !home.asFile.exists() }
        doLast {
            val v = version
            val target = home.asFile
            val zip =
                layout.buildDirectory
                    .file("openjml-$v.zip")
                    .get()
                    .asFile
            zip.parentFile.mkdirs()
            val url = "https://github.com/OpenJML/OpenJML/releases/download/$v/openjml-$distribution-$v.zip"
            logger.lifecycle("Скачивание $url")
            uri(url).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
            copy {
                from(zipTree(zip))
                into(target)
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
        val script = if (System.getProperty("os.name").lowercase().contains("win")) "openjml.bat" else "openjml"
        val home = openjmlHome
        doFirst {
            executable = home.file(script).asFile.absolutePath
            args(listOf("--esc") + verifiedSources.files.map { it.absolutePath })
        }
    }
