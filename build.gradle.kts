plugins {
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.serialization") version "2.0.0"
    `maven-publish`
}

group = "felis.bundler.handler"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation(kotlin("reflect"))
}

val fatJar = tasks.create<Jar>("fatJar") {
    manifest {
        attributes("Main-Class" to "felis.bundler.handler.MainKt")
    }
    archiveBaseName.set(archiveBaseName.get() + "-fat")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(*configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }.toTypedArray())
    with(tasks.getByName<Jar>("jar"))
}

artifacts {
    archives(fatJar)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            pom {
                artifactId = "felis-server"
            }
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "Repsy"
            url = uri("https://repsy.io/mvn/0xjoemama/public/")
            credentials {
                username = System.getenv("REPSY_USERNAME")
                password = System.getenv("REPSY_PASSWORD")
            }
        }
    }
}

