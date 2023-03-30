import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.PropertiesFileTransformer

plugins {
    application
    id("com.diffplug.spotless")
    `maven-publish`
    id("com.adarshr.test-logger") version "2.1.0"
    id("org.jetbrains.kotlin.jvm")
    id("com.github.johnrengelman.shadow") version "7.1.2"
    kotlin("plugin.serialization")
}

description = "Model Server offering access to model storage"

defaultTasks.add("build")

/*compileJava {
    sourceCompatibility = '11'
    targetCompatibility = '11'
}*/

val mpsExtensionsVersion: String by project
val modelixCoreVersion: String by rootProject
val ktorVersion: String by rootProject
val kotlinCoroutinesVersion: String by rootProject
val kotlinVersion: String by rootProject
val logbackVersion: String by rootProject
val kotlinxSerializationVersion: String by rootProject

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")

    implementation(project(":model-api"))
    implementation(project(":model-server-api"))
    implementation(project(":model-client", configuration = "jvmRuntimeElements"))
    implementation(project(":authorization"))
    implementation("org.apache.commons:commons-lang3:3.10")

    implementation("org.json:json:20200518")
    implementation("org.java-websocket:Java-WebSocket:1.5.0")
    implementation("org.apache.commons:commons-collections4:4.4")
    implementation("io.lettuce:lettuce-core:5.1.8.RELEASE")
    implementation("ch.qos.logback:logback-classic:1.4.6")

    implementation("io.ktor", "ktor-server-core", ktorVersion)
    implementation("io.ktor", "ktor-server-cors", ktorVersion)
    implementation("io.ktor", "ktor-server-netty", ktorVersion)
    implementation("io.ktor", "ktor-server-html-builder", ktorVersion)
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-forwarded-header:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-thymeleaf:$ktorVersion")

    val igniteVersion = "2.13.0"
    implementation("org.apache.ignite:ignite-core:$igniteVersion")
    implementation("org.apache.ignite:ignite-spring:$igniteVersion")
    implementation("org.apache.ignite:ignite-indexing:$igniteVersion")

    implementation("org.postgresql:postgresql:42.3.3")

    implementation("commons-io:commons-io:2.7")
    implementation("com.google.guava:guava:30.0-jre")
    implementation("com.beust:jcommander:1.7")
    implementation("org.apache.cxf:cxf-rt-rs-sse:3.3.7")
    implementation("org.apache.cxf:cxf-rt-rs-client:3.3.7")

    testImplementation("junit:junit:4.13.1")
    testImplementation("io.cucumber:cucumber-java:6.2.2")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")
    implementation(kotlin("test-junit"))
}

val cucumberRuntime by configurations.creating {
    extendsFrom(configurations["testImplementation"])
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("model-server")
    archiveClassifier.set("fatJar")
    archiveVersion.set("latest")
    manifest {
        attributes["Main-Class"] = "org.modelix.model.server.Main"
    }

    mergeServiceFiles()
    append("META-INF/spring.schemas")
    append("META-INF/spring.handlers")
    append("META-INF/spring.tooling")

    transform(PropertiesFileTransformer::class.java) {
        paths = listOf("META-INF/spring.factories")
        mergeStrategy = "append"
    }
}

val fatJarFile = file("$buildDir/libs/model-server-latest-fatJar.jar")
val fatJarArtifact = artifacts.add("archives", fatJarFile) {
    type = "jar"
    builtBy("shadowJar")
}

val cucumber = task("cucumber") {
    dependsOn("shadowJar", "compileTestJava")
    doLast {
        javaexec {
            mainClass.set("io.cucumber.core.cli.Main")
            classpath = cucumberRuntime + sourceSets.main.get().output + sourceSets.test.get().output
            // Change glue for your project package where the step definitions are.
            // And where the feature files are.
            args = listOf("--plugin", "pretty", "--glue", "org.modelix.model.server.functionaltests", "src/test/resources/functionaltests")

        }
    }
}

tasks.named("test") {
    dependsOn("cucumber")
}

task("copyLibs", Sync::class) {
    into("$buildDir/dependency-libs")
    from(configurations.runtimeClasspath)
}

tasks.named("assemble") {
    finalizedBy("copyLibs")
}

application {
    mainClass.set("org.modelix.model.server.Main")
}

publishing {
    publications {
        create<MavenPublication>("modelServer") {
            groupId = project.group as String
            artifactId = "model-server"
            version = project.version as String

            from(components["java"])
        }

        create<MavenPublication>("modelServerFatJar") {
            groupId = project.group as String
            artifactId = "model-server-fatjar"
            artifact(fatJarArtifact)
        }
    }
}

spotless {
    java {
        googleJavaFormat("1.8").aosp()
        licenseHeader("/*\n" +
                """ * Licensed under the Apache License, Version 2.0 (the "License");""" + "\n" +
                """ * you may not use this file except in compliance with the License.""" + "\n" +
                """ * You may obtain a copy of the License at""" + "\n" +
                """ *""" + "\n" +
                """ *  http://www.apache.org/licenses/LICENSE-2.0"""+ "\n" +
                """ *""" + "\n" +
                """ * Unless required by applicable law or agreed to in writing,""" + "\n" +
                """ * software distributed under the License is distributed on an""" + "\n" +
                """ * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY""" + "\n" +
                """ * KIND, either express or implied.  See the License for the""" + "\n" +
                """ * specific language governing permissions and limitations""" + "\n" +
                """ * under the License. """ + "\n" +
                " */\n"+
                "\n")
        /*licenseHeader '/*\n' +
                ' * Licensed under the Apache License, Version 2.0 (the "License");\n' +
                ' * you may not use this file except in compliance with the License.\n' +
                ' * You may obtain a copy of the License at\n' +
                ' *\n' +
                ' *  http://www.apache.org/licenses/LICENSE-2.0\n' +
                ' *\n' +
                ' * Unless required by applicable law or agreed to in writing,\n' +
                ' * software distributed under the License is distributed on an\n' +
                ' * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY\n' +
                ' * KIND, either express or implied.  See the License for the\n' +
                ' * specific language governing permissions and limitations\n' +
                ' * under the License. \n' +
                ' */\n' +
                '\n'*/
    }
}

