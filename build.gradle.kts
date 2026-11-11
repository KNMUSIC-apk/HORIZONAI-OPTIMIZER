plugins {
    java
}

group = "com.horizon"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    // Server cung cấp Paper API lúc runtime
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    // SQLite JDBC được Paper tải lúc khởi động qua mục `libraries` trong plugin.yml
    // nên KHÔNG shade vào JAR. (Jackson đã bị loại bỏ cùng với phần AI.)
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName.set("HorizonAI-Optimizer-${project.version}.jar")
}
