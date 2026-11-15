plugins {
    java
}

group = "dev.creatorcore"

val paperApi: String by project

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApi")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.add("-Xlint:deprecation")
    }

    processResources {
        filteringCharset = "UTF-8"
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }

    jar {
        archiveBaseName.set("CreatorCore")
    }

    // Exporta o jar da versão atual para libs/ (um jar por etapa; nunca sobrescreve versões anteriores).
    val exportLib by registering(Copy::class) {
        group = "creatorcore"
        description = "Copia o jar da versão atual para libs/"
        from(jar)
        into(layout.projectDirectory.dir("libs"))
    }

    build {
        finalizedBy(exportLib)
    }
}
