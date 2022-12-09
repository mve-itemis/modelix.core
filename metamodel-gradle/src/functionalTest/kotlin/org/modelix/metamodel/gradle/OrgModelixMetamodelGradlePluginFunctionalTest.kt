/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package org.modelix.metamodel.gradle

import java.io.File
import java.nio.file.Files
import kotlin.test.assertTrue
import kotlin.test.Test
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.modelix.metamodel.generator.LanguageData

/**
 * A simple functional test for the 'org.modelix.metamodel.gradle.greeting' plugin.
 */
class OrgModelixMetamodelGradlePluginFunctionalTest {
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
    maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
    mavenCentral()
}

metamodel {
    mpsHome = file("${File("build/mps").absolutePath}")
    modulesFrom(file("${File("build/mpsDependencies").absolutePath}"))
}
""")

        // Run the build
        val runner = GradleRunner.create()
        runner.forwardOutput()
        runner.withPluginClasspath()
        runner.withArguments("exportMetaModelFromMps")
        runner.withProjectDir(getProjectDir())
        val result = runner.build();

        // Verify the result
        val exportDir = getProjectDir().resolve("build/metamodel/exported-languages")
        assertTrue(exportDir.exists())
        assertTrue(exportDir.resolve("json").exists())
        val jsonFiles = exportDir.resolve("json").listFiles()!!.toList()
        println("${jsonFiles.size} languages exported")
        val parsedFiles = jsonFiles.map { LanguageData.fromFile(it) }
        assertTrue(parsedFiles.size > 10)
    }
}