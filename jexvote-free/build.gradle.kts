import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("jexsuite.shadow-conventions")
    id("jexsuite.dependencies-yml")
}

group = "de.jexcellence.vote"
version = "3.1.1"

dependenciesYml {
    usePaperDependencies()
    generatePaperVariant.set(true)
    generateSpigotVariant.set(true)
}

dependencies {
    // implementation
    implementation(project(":JExVote:jexvote-common"))
    implementation(libs.jehibernate) { isTransitive = false }
    implementation(libs.bundles.jexcellence) {
        isTransitive = false
        exclude(group = "de.jexcellence.hibernate")
    }
    implementation(libs.bundles.jeconfig) { isTransitive = false }

    // compileOnly
    compileOnly(libs.paper.api)
    compileOnly(libs.slf4j.api)
    compileOnly(libs.slf4j.jdk14)
    compileOnly(libs.jboss.logging)
    compileOnly(platform(libs.hibernate.platform))
    compileOnly(libs.bundles.hibernate)
    compileOnly(libs.adventure.platform.bukkit)
    compileOnly(libs.bundles.inventory)

    // test
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.mockbukkit)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("JExVote")
    archiveClassifier.set("Free")
    archiveVersion.set(project.version.toString())

    relocate("tools.jackson", "de.jexcellence.remapped.tools.jackson")
    relocate("me.devnatan.inventoryframework", "de.jexcellence.remapped.me.devnatan.inventoryframework")
    relocate("com.tcoded", "de.jexcellence.remapped.com.tcoded")
    relocate("com.cryptomorin.xseries", "de.jexcellence.remapped.com.cryptomorin.xseries")

    configurations = listOf(project.configurations.getByName("runtimeClasspath"))
    mergeServiceFiles()
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named<Jar>("jar") {
    enabled = false
}

afterEvaluate {
    publishing {
        publications {
            named<MavenPublication>("maven") {
                groupId = "de.jexcellence.vote"
                artifactId = "jexvote-free-shadow"
                version = project.version.toString()
                artifact(tasks.named("shadowJar"))
            }
            create<MavenPublication>("mavenShadow") {
                from(components["shadow"])
                groupId = "de.jexcellence.vote"
                artifactId = "jexvote-free"
                version = project.version.toString()
                pom {
                    name.set("JExVote Free")
                    description.set("JExVote Free Edition")
                }
            }
        }
    }
}
