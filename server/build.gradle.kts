plugins {
    kotlin("jvm")
    kotlin("plugin.allopen")
    kotlin("plugin.serialization")
    id("io.quarkus")
    id("org.jooq.jooq-codegen-gradle")
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project
val jooqVersion: String by project
val commonsCsvVersion: String by project

repositories {
    mavenCentral()
}

dependencies {
    implementation(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-kotlin-serialization")
    implementation("io.quarkus:quarkus-scheduler")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-agroal")
    implementation("io.quarkus:quarkus-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.quarkus:quarkus-smallrye-openapi")
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jooq:jooq:$jooqVersion")
    implementation("org.apache.commons:commons-csv:$commonsCsvVersion")

    jooqCodegen("org.jooq:jooq-meta-extensions:$jooqVersion")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("org.tukaani:xz:1.10")
}

group = "cz.zasstojime"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        javaParameters = true
    }
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.enterprise.context.RequestScoped")
    annotation("jakarta.inject.Singleton")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

// jOOQ třídy se generují přímo z Flyway migrací (ADR-0020), bez běžící databáze.
val jooqOutputDir = layout.buildDirectory.dir("generated-src/jooq/main")

jooq {
    configuration {
        generator {
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties {
                    property {
                        key = "scripts"
                        value = "src/main/resources/db/migration/*.sql"
                    }
                    property {
                        key = "sort"
                        value = "flyway"
                    }
                    property {
                        key = "defaultNameCase"
                        value = "lower"
                    }
                    property {
                        key = "parseIgnoreComments"
                        value = "true"
                    }
                }
            }
            generate {
                isDeprecated = false
                isRecords = true
                isPojos = false
                isJavaTimeTypes = true
            }
            target {
                packageName = "cz.zasstojime.db"
                directory = jooqOutputDir.get().asFile.path
            }
        }
    }
}

sourceSets {
    main {
        java.srcDir(jooqOutputDir)
    }
}

tasks.named("jooqCodegen") {
    inputs.dir("src/main/resources/db/migration")
    outputs.dir(jooqOutputDir)
}
tasks.named("compileJava") { dependsOn("jooqCodegen") }
tasks.named("compileKotlin") { dependsOn("jooqCodegen") }

tasks.withType<Test> {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    jvmArgs("-Xmx1g")
}
