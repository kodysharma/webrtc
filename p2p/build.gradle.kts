import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java-library")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
    `maven-publish`
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                artifactId = "p2p"
                groupId = "desidev.p2p"
                version = "1.0.0"
                from(components["java"])
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8

    publishing {
        withSourcesJar()
        withJavadocJar()
    }
}

tasks.withType(KotlinCompile::class.java) {
    kotlinOptions.jvmTarget = "1.8"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinutils)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.arrow.core)
    implementation(libs.kotlin.logging)
    implementation(libs.logback)

    implementation(project(":stub"))

    testImplementation(libs.kotlin.test)
}