import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Release builds of all apps are signed with the key described in keystore.properties,
// which is not in git (see README). Without it, release builds stay unsigned.
rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    val keystore = Properties().apply { file.inputStream().use { load(it) } }
    subprojects {
        pluginManager.withPlugin("com.android.application") {
            extensions.configure<ApplicationExtension> {
                val release = signingConfigs.create("release") {
                    storeFile = rootProject.file(keystore.getProperty("storeFile"))
                    storePassword = keystore.getProperty("storePassword")
                    keyAlias = keystore.getProperty("keyAlias")
                    keyPassword = keystore.getProperty("keyPassword")
                }
                buildTypes.getByName("release").signingConfig = release
            }
        }
    }
}
