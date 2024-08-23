package felis.bundler.handler

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileNotFoundException
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.io.path.*

@Serializable
data class ServerConfig(val serverVersion: String, val additionalLibraries: List<Library>) {
    fun downloadLibs(root: Path, client: HttpClient): List<Path> {
        val libs = root / "libraries"
        libs.createDirectories()

        return this.additionalLibraries
            .map { it.download(libs, client) }
            .map { it.join() }
    }
}

@Serializable
data class Library(val name: String, val url: String) {
    private val nameParts by lazy { this.name.split(":") }
    private val groupId: String
        get() = this.nameParts[0]
    private val library: String
        get() = this.nameParts[1]
    private val version: String
        get() = this.nameParts[2]
    private val fileName: String
        get() = "${this.library}-${this.version}.jar"

    private fun toUri(): URI {
        return URI.create(
            "${this.url}${
                this.groupId.replace(
                    ".",
                    "/"
                )
            }/${this.library}/${this.version}/${this.library}-${this.version}.jar"
        )
    }

    fun download(librariesPath: Path, client: HttpClient): CompletableFuture<Path> {
        val dst = librariesPath / this.groupId.replace(".", "/") / this.library / this.version / this.fileName
        return if (dst.exists()) CompletableFuture.completedFuture(dst) else {
            dst.createParentDirectories()
            client.sendAsync(
                HttpRequest.newBuilder().GET().uri(this.toUri()).build(),
                BodyHandlers.ofFile(dst, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            ).thenApply { it.body() }
        }
    }
}

@Serializable
data class VersionManifest(val versions: List<LauncherVersion>)

@Serializable
data class LauncherVersion(val id: String, val url: String)

@OptIn(ExperimentalStdlibApi::class, ExperimentalPathApi::class)
class ServerJar(private val versionId: String, private val path: Path, json: Json, client: HttpClient) : AutoCloseable {
    init {
        if (!this.path.exists()) {
            //downlaod the manifest
            val versionManifest = client.send(
                HttpRequest.newBuilder().GET()
                    .uri(URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")).build(),
                BodyHandlers.ofString()
            ).body().let { json.decodeFromString<VersionManifest>(it) }
            // find the target version
            val version = versionManifest.versions.find { it.id == this.versionId }
                ?: throw IllegalArgumentException("Unknown version ${this.versionId}")
            // extra version info
            val versionJson =
                client.send(HttpRequest.newBuilder(URI.create(version.url)).GET().build(), BodyHandlers.ofString())
                    .body()
                    .let { json.parseToJsonElement(it) }.jsonObject
            val serverMeta = versionJson["downloads"]?.jsonObject?.get("server")?.jsonObject ?: throw RuntimeException()
            val bundlerUrl = serverMeta.getValue("url").jsonPrimitive.content
            val sha1 = serverMeta.getValue("sha1").jsonPrimitive.content

            // download the bundler
            val serverJar = client.send(
                HttpRequest.newBuilder(URI.create(bundlerUrl)).GET().build(),
                BodyHandlers.ofFile(this.path)
            ).body()
            // verify the bundler
            val digest = MessageDigest.getInstance("SHA-1").digest(serverJar.readBytes())
            require(sha1.hexToByteArray().contentEquals(digest)) { "invalid" }
        }
    }

    private val fs = FileSystems.newFileSystem(this.path)

    val classpath: List<String>
        get() = this.fs.getPath("META-INF").resolve("classpath-joined").readText().split(";")

    override fun close() = this.fs.close()

    fun extract(source: String, dst: Path) {
        val root = this.fs.getPath(source)
        root.walk().forEach {
            val targetPath = dst.resolve(it.relativeTo(root).toString())
            targetPath.createParentDirectories()
            try {
                it.copyTo(targetPath)
            } catch (e: FileAlreadyExistsException) { /* ignored */
            }
        }
    }
}

fun downloadClasspath(path: Path): List<Path> {
    val json = Json {
        ignoreUnknownKeys = true
    }
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    val client = HttpClient.newBuilder()
        .executor(executor)
        .version(HttpClient.Version.HTTP_1_1)
        .build()
    val config = Thread.currentThread().contextClassLoader.getResourceAsStream("server.config.json")
        ?.use { String(it.readAllBytes()) }
        ?.let { json.decodeFromString<ServerConfig>(it) }
        ?: throw FileNotFoundException("could not locate fbh configuration file in the classpath")

    val extras = config.downloadLibs(path, client)
    return ServerJar(config.serverVersion, path.resolve("bundler.jar"), json, client).use { jar ->
        jar.extract("META-INF/libraries", path.resolve("libraries"))
        jar.extract("META-INF/versions", path.resolve("versions"))
        jar.classpath.map { path.resolve(it) } + extras
    }
}

fun main() {
    val cp = downloadClasspath(Paths.get("."))

    val cl = URLClassLoader(cp.map { it.toUri().toURL() }.toTypedArray(), ClassLoader.getSystemClassLoader())
    val mainclass = Class.forName("felis.MainKt", true, cl)
    val mainMethod = MethodHandles.publicLookup()
        .findStatic(mainclass, "main", MethodType.fromMethodDescriptorString("([Ljava/lang/String;)V", cl))
    System.setProperty("felis.minecraft.remap", "true")
    System.setProperty("felis.launcher", "felis.launcher.minecraft.MinecraftLauncher")
    System.setProperty("felis.side", "SERVER")
    System.setProperty("felis.mods", "felis-mods")
    System.setProperty("java.class.path", cp.joinToString(File.pathSeparator) { it.pathString })
    Thread.currentThread().contextClassLoader = cl
    mainMethod.invokeExact(arrayOf<String>())
}