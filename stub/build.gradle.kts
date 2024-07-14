import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.remove

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}


dependencies {
//    protobuf(project(":protos"))

    api(libs.kotlinx.coroutines.core)

//    api(libs.grpc.stub)
//    api(libs.grpc.protobuf)
//    api(libs.protobuf.java.util)
//    api(libs.grpc.kotlin.stub)
    api(libs.protobuf.kotlin)
}

kotlin {
    jvmToolchain(8)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().all {
    kotlinOptions {
        freeCompilerArgs = listOf("-opt-in=kotlin.RequiresOptIn")
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach {
            it.builtins {
                remove("java")
                id("java")
                create("kotlin")
            }
        }
    }
}