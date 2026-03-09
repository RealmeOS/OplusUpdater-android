import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow") version "9.2.2"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}
dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("updater")
    manifest {
        attributes(
            "Main-Class" to "updater.MainKt",
            "Implementation-Version" to "1.0"
        )
    }
}

tasks.register<Jar>("fatJar") {
    archiveBaseName.set("updater-all")
    archiveClassifier.set("")
    
    // 包含主类输出和所有依赖
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    
    // 排除签名文件
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/*.MF")
    
    // 处理重复文件
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    
    // 设置主类
    manifest {
        attributes(
            "Main-Class" to "updater.MainKt",
            "Implementation-Version" to "1.0"
        )
    }
    
    // 确保依赖正确的任务
    dependsOn("compileKotlin", "processResources")
}

// 将fatJar任务添加到build任务依赖中
tasks.named("build") {
    dependsOn("fatJar")
}