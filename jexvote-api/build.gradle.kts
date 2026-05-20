plugins {
    id("jexsuite.library-conventions")
}

group = "de.jexcellence.vote"
version = "3.0.0"
description = "JExVote API - Public API for third-party plugin integration"

dependencies {
    compileOnly(libs.paper.api)
}

afterEvaluate {
    publishing {
        publications {
            named<MavenPublication>("maven") {
                groupId = "de.jexcellence.vote"
                artifactId = "jexvote-api"
                version = project.version.toString()
                pom {
                    name.set("JExVote API")
                    description.set("Public API for JExVote voting system")
                }
            }
        }
    }
}
