import xtdb.DataReaderTransformer

plugins {
    java
    application
    id("com.github.johnrengelman.shadow")
}

dependencies {
    implementation(project(":xtdb-core"))
    implementation(project(":xtdb-http-server"))
    implementation("ch.qos.logback", "logback-classic", "1.4.5")
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

application {
    mainClass.set("clojure.main")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Version" to project.version,
        )
    }
}

tasks.shadowJar {
    archiveBaseName.set("xtdb")
    archiveVersion.set("")
    archiveClassifier.set("standalone")
    mergeServiceFiles()
    transform(DataReaderTransformer())
}
