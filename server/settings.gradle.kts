pluginManagement {
    val quarkusPluginVersion: String by settings
    val kotlinVersion: String by settings
    val jooqVersion: String by settings
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("io.quarkus") version quarkusPluginVersion
        kotlin("jvm") version kotlinVersion
        kotlin("plugin.allopen") version kotlinVersion
        kotlin("plugin.serialization") version kotlinVersion
        id("org.jooq.jooq-codegen-gradle") version jooqVersion
    }
}

rootProject.name = "zasstojime-server"
