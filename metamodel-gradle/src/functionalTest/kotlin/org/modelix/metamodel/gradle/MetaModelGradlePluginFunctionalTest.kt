/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package org.modelix.metamodel.gradle

import java.io.File
import kotlin.test.assertTrue
import kotlin.test.Test
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.modelix.model.data.LanguageData

/**
 * A simple functional test for the 'org.modelix.metamodel.gradle.greeting' plugin.
 */
class MetaModelGradlePluginFunctionalTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private fun getProjectDir() = tempFolder.root
    private fun getBuildFile() = getProjectDir().resolve("build.gradle")
    private fun getSettingsFile() = getProjectDir().resolve("settings.gradle")

    @Test fun `can run task`() {
        // Setup the test build
        getSettingsFile().writeText("")
        getBuildFile().writeText("""
plugins {
    id('org.modelix.metamodel.gradle')
}

repositories {
    mavenLocal()
    maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
    mavenCentral()
}

metamodel {
    mpsHome = file("${File("build/mps").absolutePath}")
    mpsHeapSize = "2g"
    kotlinDir = file("" + buildDir + "/kotlin_gen")
    registrationHelperName = "org.modelix.metamodel.gradle.functionalTest.Languages"
    typescriptDir = file("" + buildDir + "/ts_gen")
    includeNamespace("jetbrains.mps.baseLanguage")
    exportModules("jetbrains.mps.runtime")
}
""")

        // Run the build
        val runner = GradleRunner.create()
        runner.forwardOutput()
        runner.withPluginClasspath()
        runner.withArguments("generateMetaModelSources")
        runner.withProjectDir(getProjectDir())
        val result = runner.build()

        // Verify the result
        val exportDir = getProjectDir().resolve("build/metamodel/exported-languages")
        assertTrue(exportDir.exists())
        val jsonFiles = exportDir.listFiles()!!.toList().filter { it.extension.lowercase() == "json" }
        println("${jsonFiles.size} languages exported")
        val parsedFiles = jsonFiles.map { LanguageData.fromJson(it.readText()) }
        assertTrue(parsedFiles.isNotEmpty())

        val kotlinGenDir = getProjectDir().resolve("build/kotlin_gen")
        val tsGenDir = getProjectDir().resolve("build/ts_gen")
        val numKotlinFiles = kotlinGenDir.walk().filter { it.extension.lowercase() == "kt" }.count()
        val numTsFiles = tsGenDir.walk().filter { it.extension.lowercase() == "ts" }.count()
        println("$numKotlinFiles kotlin files generated")
        println("$numTsFiles typescript files generated")
        assertTrue(numKotlinFiles > 0)
        assertTrue(numTsFiles > 0)
    }
}
