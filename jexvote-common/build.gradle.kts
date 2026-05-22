plugins {
    id("jexsuite.library-conventions")
    id("jexsuite.dependencies-yml")
}

group = "de.jexcellence.vote"
version = "3.0.0"
description = "JExVote Common - Shared library for JExVote"

dependenciesYml {
    usePaperDependencies()
    generatePaperVariant.set(true)
    generateSpigotVariant.set(true)
}

tasks.processResources {
    exclude("plugin.yml", "paper-plugin.yml")
}

dependencies {
    // ── Implementation ──
    implementation(project(":JExVote:jexvote-api"))

    // ── Compile-only: Project ──
    compileOnly(project(":JExEconomy:jexeconomy-api"))

    // ── Compile-only: Paper & Server API ──
    compileOnly(libs.paper.api)
    compileOnly(libs.bundles.adventure)
    compileOnly(libs.folialib)
    compileOnly(libs.placeholderapi)
    compileOnly(libs.vault.api) { isTransitive = false }
    compileOnly(libs.luckperms.api)

    // ── Compile-only: Logging ──
    compileOnly(libs.slf4j.api)
    compileOnly(libs.slf4j.jdk14)
    compileOnly(libs.jboss.logging)

    // ── Compile-only: Database ──
    compileOnly(platform(libs.hibernate.platform))
    compileOnly(libs.bundles.hibernate)
    compileOnly(libs.jehibernate)

    // ── Compile-only: Serialization ──
    compileOnly(libs.jackson.core)
    compileOnly(libs.jackson.databind)
    compileOnly(libs.jackson.annotations)
    compileOnly(libs.jackson.jsr310)

    // ── Compile-only: JExcellence Platform ──
    compileOnly(libs.bundles.jexcellence) {
        exclude(group = "de.jexcellence.hibernate")
        isTransitive = false
    }
    compileOnly(libs.bundles.jeconfig) { isTransitive = false }

    // ── Compile-only: Utilities ──
    compileOnly(libs.caffeine)
    compileOnly(libs.java.uuid)
    compileOnly(libs.xseries)
    compileOnly(libs.bundles.inventory)

    // ── Test ──
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.minimessage)
    testImplementation(libs.caffeine)
    testImplementation(platform(libs.hibernate.platform))
    testImplementation(libs.bundles.hibernate)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
