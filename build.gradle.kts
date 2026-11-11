plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "mc.jonomore"
version = "0.1.0"

repositories {
    mavenCentral()
    maven(url = "https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:26.3.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filesMatching("paper-plugin.yml") { expand(props) }
    }
    test {
        useJUnitPlatform()
    }
    runServer {
        minecraftVersion("26.3")
    }
}
