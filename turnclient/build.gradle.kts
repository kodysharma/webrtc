import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
    `maven-publish`
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                artifactId = "turnclient"
                groupId = "desidev.turnclient"
                version = "2.1.0"
                from(components["java"])
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
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

    testImplementation(libs.kotlin.test)
}