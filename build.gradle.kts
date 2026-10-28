plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.mavenPublish) apply false
}

subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()
            coordinates(
                groupId = property("GROUP") as String,
                artifactId = project.name,
                version = property("VERSION_NAME") as String,
            )

            pom {
                name.set("PlayMe ${project.name}")
                description.set("FFmpeg decoding, container reading, media info and thumbnails for Android Media3")
                url.set("https://github.com/baboDev123/play_media_library")

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
                    connection.set("scm:git:git://github.com/baboDev123/play_media_library.git")
                    developerConnection.set("scm:git:ssh://github.com/baboDev123/play_media_library.git")
                    url.set("https://github.com/baboDev123/play_media_library")
                }
            }
        }
    }
}

