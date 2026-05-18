import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
}

val rtspLibraryGroup = "com.skrdzavac.rtspnative"
val rtspLibraryVersion: String = libs.versions.library.get()

// Auto-apply maven-publish to every Android library module so `:rtsp`
// and its siblings can be consumed as AAR artifacts. `:sample` is the
// application module and is skipped.
subprojects {
    pluginManager.withPlugin("com.android.library") {
        pluginManager.apply("maven-publish")

        extensions.configure(LibraryExtension::class.java) {
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                }
            }
        }

        afterEvaluate {
            extensions.configure(PublishingExtension::class.java) {
                publications {
                    register("release", MavenPublication::class.java) {
                        from(components.findByName("release"))
                        groupId = rtspLibraryGroup
                        artifactId = project.name
                        version = rtspLibraryVersion
                    }
                }
            }
        }
    }
}
