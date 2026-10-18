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

// The typing simulator (docs/keyboard/ENGINE.md): a pure-JVM source set with its own
// data under src/eval/resources, run by :keyboard-core:typingEval and reused by the
// smoke test. No new dependencies — the eval hand-rolls its JSON.
sourceSets {
    create("eval")
}

configurations {
    // Share the module's implementation deps (the Kotlin stdlib) with the eval source set.
    named("evalImplementation") { extendsFrom(configurations["implementation"]) }
}

dependencies {
    "evalImplementation"(files(sourceSets["main"].output))
    // The smoke test drives the simulator directly.
    testImplementation(files(sourceSets["eval"].output))
}

val evalClasspath = files(sourceSets["eval"].output, sourceSets["main"].output, configurations["runtimeClasspath"])

tasks.register<JavaExec>("typingEval") {
    group = "verification"
    description = "Runs the deterministic typing simulator over the typing engine."
    classpath = evalClasspath
    mainClass.set("com.wisperlow.mobile.keyboard.eval.TypingEvalKt")
    dependsOn(sourceSets["eval"].output)
    jvmArgs("-Xmx1g")
    // Pass program flags through Gradle with -PevalArgs="--quick --seeds=3".
    args = (findProperty("evalArgs") as String?)?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
}
