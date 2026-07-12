import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.37.0"
    // Turns the non-modular SnuggleTeX jar into a proper JPMS module, matching JabRef's own build
    id("org.gradlex.extra-java-module-info") version "1.14.2"
}

group = "org.jabref"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    // 24 keeps the library usable one release below JabRef's 25, matching html-to-node
    options.release = 24
    options.encoding = "UTF-8"
}

dependencies {
    api("org.jspecify:jspecify:1.0.0")
    implementation("de.rototor.snuggletex:snuggletex-core:1.3.0")

    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// snuggletex-core ships no module descriptor, so Gradle would leave it on the classpath and
// `requires snuggletex.core` would fail to resolve. Promote it to a proper module reading the JDK
// modules it uses (java.xml for its DOM output types, java.logging). The module name must stay
// `snuggletex.core` — JabRef's build-logic assigns the same name, and both builds have to agree.
extraJavaModuleInfo {
    failOnMissingModuleInfo = false
    module("de.rototor.snuggletex:snuggletex-core", "snuggletex.core") {
        requires("java.xml")
        requires("java.logging")
        exportAllPackages()
    }
}

tasks.javadoc {
    // Document the exported API only; org.jabref.latexconv.internal stays out of the docs.
    // The compiled classes are patched in so references to internal types still resolve.
    exclude("org/jabref/latexconv/internal/**")
    val classesDirs = sourceSets.main.get().output.classesDirs
    options {
        this as StandardJavadocDocletOptions
        encoding = "UTF-8"
        addStringOption("-patch-module", "org.jabref.latexconv=${classesDirs.asPath}")
        addBooleanOption("Xdoclint:all,-missing", true)
        addBooleanOption("Werror", true)
    }
}

// Same publishing setup as JabRef's jablib and html-to-node: snapshots land on
// https://central.sonatype.com/repository/maven-snapshots/, which JabRef's build already resolves
mavenPublishing {
    configure(JavaLibrary(
        javadocJar = JavadocJar.Javadoc(),
        sourcesJar = SourcesJar.Sources(),
    ))

    publishToMavenCentral()
    signAllPublications()

    coordinates("org.jabref", "latex-conv", version.toString())

    pom {
        name = "latex-conv"
        description = "Converts LaTeX to Unicode/HTML and back"
        inceptionYear = "2026"
        url = "https://github.com/JabRef/latex-conv/"
        licenses {
            license {
                name = "MIT"
                url = "https://github.com/JabRef/latex-conv/blob/main/LICENSE"
            }
        }
        developers {
            developer {
                id = "jabref"
                name = "JabRef Developers"
                url = "https://github.com/JabRef/"
            }
        }
        scm {
            url = "https://github.com/JabRef/latex-conv"
            connection = "scm:git:https://github.com/JabRef/latex-conv"
            developerConnection = "scm:git:git@github.com:JabRef/latex-conv.git"
        }
    }
}

// Tests run on the classpath: no module patching needed
tasks.compileTestJava {
    modularity.inferModulePath = false
}

tasks.test {
    useJUnitPlatform()
    modularity.inferModulePath = false
    testLogging {
        events("failed", "skipped")
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
