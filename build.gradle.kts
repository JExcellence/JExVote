plugins {
    `maven-publish`
    id("jexsuite.shadow-conventions")
    id("jexsuite.dependencies-yml")
}

group = "de.jexcellence.vote"
version = "3.1.1"
description = "JExVote - All-in-one vote listener, reward & leaderboard system"

ext["vendor"] = "JExcellence"

subprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.register("buildAll") {
    group = "build"
    description = "Builds both Free and Premium editions"
    dependsOn(
        ":JExVote:jexvote-api:build",
        ":JExVote:jexvote-common:build",
        ":JExVote:jexvote-free:shadowJar",
        ":JExVote:jexvote-premium:shadowJar"
    )
}

tasks.register("publishLocal") {
    group = "publishing"
    description = "Publishes all modules to local Maven repository"
    dependsOn(
        ":JExVote:jexvote-api:publishMavenPublicationToMavenLocal",
        ":JExVote:jexvote-common:publishMavenPublicationToMavenLocal",
        ":JExVote:jexvote-free:publishMavenShadowPublicationToMavenLocal",
        ":JExVote:jexvote-premium:publishMavenShadowPublicationToMavenLocal",
    )
    doLast {
        println("Published ${project.group}:jexvote-*:${project.version} to local Maven")
    }
}

afterEvaluate {
    publishing {
        publications {
            named<MavenPublication>("maven") {
                artifactId = "jexvote"
            }
        }
    }
}
