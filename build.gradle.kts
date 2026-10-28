plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.mavenPublish) apply false
}

subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()
            coordinates(
                groupId = "io.github.playme",
                artifactId = project.name,
                version = "1.0.0"
            )

            pom {
                name.set("PlayMe ${project.name}")
                description.set("High-performance media decoding and processing engine for Android Media3")
                url.set("https://github.com/playme/playme-media")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("playme")
                        name.set("PlayMe Development Team")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/playme/playme-media.git")
                    developerConnection.set("scm:git:ssh://github.com/playme/playme-media.git")
                    url.set("https://github.com/playme/playme-media")
                }
            }
        }
    }
}

