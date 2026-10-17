import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The typing engine: pure Kotlin with no Android dependencies, so it builds and
// tests in seconds and can be driven by the typing simulator on the desktop.
plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Word lists ship as app assets; tests read them straight from there.
    systemProperty("wisperlow.assets", rootProject.file("app/src/main/assets").absolutePath)
}
