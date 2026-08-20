import org.gradle.api.tasks.Exec
import java.io.File

plugins {
    base
}

fun findExecutableOnPath(name: String): String? =
    System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.asSequence()
        ?.map { File(it, name) }
        ?.firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath

val detectedNpm = findExecutableOnPath("npm")
    ?: findExecutableOnPath("node")?.let { File(it).parentFile.resolve("npm") }
        ?.takeIf { it.isFile && it.canExecute() }
        ?.absolutePath
val npmExecutable = providers.gradleProperty("pyronaut.vscode.npm").orElse(detectedNpm ?: "npm")
val nodeExecutable = providers.gradleProperty("pyronaut.vscode.node")

fun Exec.npmCommand(vararg args: String) {
    if (nodeExecutable.isPresent) {
        commandLine(nodeExecutable.get(), "scripts/npm-shim.js", *args)
    } else {
        commandLine(npmExecutable.get(), *args)
    }
    workingDir = projectDir
}

tasks.register<Exec>("npmInstall") {
    group = "build"
    description = "Install VS Code extension npm dependencies."
    npmCommand("ci")
    inputs.files("package.json", "package-lock.json")
    outputs.dir("node_modules")
}

tasks.register<Exec>("npmCompile") {
    group = "build"
    description = "Compile the Pyronaut VS Code extension."
    dependsOn("npmInstall")
    npmCommand("run", "compile")
    inputs.dir("src")
    inputs.dir("scripts")
    inputs.file("package.json")
    outputs.dir("out")
}

tasks.register<Exec>("npmTest") {
    group = "verification"
    description = "Run Pyronaut VS Code extension unit tests."
    dependsOn("npmCompile")
    npmCommand("test")
    inputs.dir("src")
    inputs.dir("test")
    inputs.dir("scripts")
    outputs.upToDateWhen { false }
}

tasks.register<Exec>("packageVsix") {
    group = "distribution"
    description = "Package the Pyronaut VS Code extension as a VSIX."
    dependsOn("npmTest")
    npmCommand("run", "package:vsix")
    inputs.dir("out")
    inputs.file("package.json")
    inputs.file("README.md")
    outputs.dir(layout.buildDirectory.dir("vsix"))
}

tasks.named("check") {
    dependsOn("npmTest")
}

tasks.named("assemble") {
    dependsOn("packageVsix")
}
