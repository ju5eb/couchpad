import java.util.Properties

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// Optional `buildRoot` in local.properties moves build outputs elsewhere,
// e.g. out of a synced folder (OneDrive locks files mid-build).
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
localProperties.getProperty("buildRoot")?.let { buildRoot ->
    allprojects { layout.buildDirectory.set(file("$buildRoot/${project.name}")) }
}
