plugins {
    java
}

group = "vn.maris"
version = "1.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
}

tasks.jar {
    archiveFileName.set("MarisSpawn.jar")
}

