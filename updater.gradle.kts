/*
* Licensed under the MIT License <http://opensource.org/licenses/MIT>.
* SPDX-License-Identifier: MIT
* Copyright (c) 2020 KJP12
*
* Permission is hereby  granted, free of charge, to any  person obtaining a copy
* of this software and associated  documentation files (the "Software"), to deal
* in the Software  without restriction, including without  limitation the rights
* to  use, copy,  modify, merge,  publish, distribute,  sublicense, and/or  sell
* copies  of  the Software,  and  to  permit persons  to  whom  the Software  is
* furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in all
* copies or substantial portions of the Software.
*
* THE SOFTWARE  IS PROVIDED "AS  IS", WITHOUT WARRANTY  OF ANY KIND,  EXPRESS OR
* IMPLIED,  INCLUDING BUT  NOT  LIMITED TO  THE  WARRANTIES OF  MERCHANTABILITY,
* FITNESS FOR  A PARTICULAR PURPOSE AND  NONINFRINGEMENT. IN NO EVENT  SHALL THE
* AUTHORS  OR COPYRIGHT  HOLDERS  BE  LIABLE FOR  ANY  CLAIM,  DAMAGES OR  OTHER
* LIABILITY, WHETHER IN AN ACTION OF  CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE  OR THE USE OR OTHER DEALINGS IN THE
* SOFTWARE.
* */

/**
 * Updater file dedicated to updating Maven, Fabric and Minecraft dependencies for Fabric mods and other projects.
 *
 * This attempts to update the mappings, and as such, will attempt to remap files over the source in the case of Minecraft.
 * Be sure to use version control software such as Git as a form of backup before updating.
 *
 * This may not be stable when Gradle is already operating on the repository you're wanting to update. Beware when doing multiple tasks.
 *
 * Best ran with either of the following commands.
 * <code>gradle --no-build-cache --no-daemon --stacktrace -a -b updater.gradle.kts updateDependencies --info</code>
 * <code>gradle -b updater.gradle.kts updateDependencies</code>
 *
 * The environment provided via command line and execv will take priority over updater.properties.
 * This means that any meta, denoted by starting with <code>$</code> in the name, provided by updater.properties can be overwritten by the environment.
 * However, this does <em>not</em> mean that you can override the artifact coordinates and repository directly to check for updates with.
 *
 * You specify the target Minecraft version via the command-line environments <code>mc_version=</code> and <code>minecraft.target=</code>.
 * You may also specify the Minecraft version via updater.properties with <code>$minecraft.target</code>.
 *
 * Do note, this will not apply updates to Fabric and Yarn if neither the environment or the properties contains the target Minecraft,
 * and neither the Fabric or Yarn metadata are present.
 *
 * @author KJP12
 * */

import Updater_gradle.N
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import java.io.FileFilter
import java.io.IOException
import java.net.URL
import java.util.*

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        val jackson = "2.11.0"
        classpath("com.fasterxml.jackson.core", "jackson-core", jackson)
        classpath("com.fasterxml.jackson.dataformat", "jackson-dataformat-xml", jackson)
    }
}

val systemEnvironment: Map<String, String> = System.getenv()

/**
 * Used for fetching the Fabric Loader and Yarn mapping versions from the Fabric installer and developer metadata.
 * @see N
 * */
val jsonMapper = ObjectMapper()

/**
 * Used for fetching the version from Maven repositories.
 * @see N.getDependency
 * */
val xmlMapper = XmlMapper()

val root = if (projectDir.resolve("updater.properties").exists()) projectDir else projectDir.resolve("..")

val updaterProperties: N by lazy { N(root.resolve("updater.properties")) }

// TODO: Java's Properties clobber comments. Use a class that doesn't clobber comments if at all possible.
// TODO: Java's Properties rearranges entries. Use a class that doesn't rearrange entries if at all possible.
fun Properties.load(from: File) {
    if (!from.exists()) return
    load(from.reader())
    for (k in keys) this[k] = updaterProperties.template[k] ?: continue
    store(from.writer(), null)
}

fun java.io.Closeable.close(appendTo: MutableList<Throwable>) {
    try {
        close()
    } catch (ioe: IOException) {
        appendTo.add(ioe)
    }
}

fun moveMappings(path: File) {
    val rem = path.resolve("remappedSrc")
    moveMappings(rem, src = path.resolve("src/main/java"), rem = rem)
}

fun moveMappings(path: File, src: File, rem: File) {
    for (it in path.listFiles()) {
        if (it.isDirectory) {
            moveMappings(it, src, rem)
        } else if (it.isFile) {
            val r = it.relativeTo(rem)
            val p = src.resolve(r)
            try {
                // TODO: Once the import var; bug is fixed, revert to Files.move
                // Files.move(it, p, StandardCopyOption.REPLACE_EXISTING)
                val i = it.bufferedReader()
                val o = p.bufferedWriter()
                val suppressed = ArrayList<Throwable>()
                try {
                    var s: String?; s = i.readLine(); while (s != null) {
                        if (s != "import var;") {
                            o.appendln(s)
                        } else println("Detected `import var;` at $it -> $p (derived by $rem & $r)!\nThis is a problem with Mercury.")
                        s = i.readLine()
                    }
                } catch (e: Exception) {
                    suppressed.add(e)
                } finally {
                    i.close(suppressed)
                    o.close(suppressed)
                }
                if (suppressed.isNotEmpty()) {
                    val exception = IOException("Failed to copy")
                    for (e in suppressed) exception.addSuppressed(e)
                    throw exception
                }
                it.delete()
            } catch (ioe: IOException) {
                // TODO: Make this error less cryptic.
                throw IOException("$it -> $p (derived by $rem & $r)", ioe)
            }
        }
    }
}

tasks {
    val isMinecraft = updaterProperties.minecraftTarget != null
    val projects: Array<File> by lazy { root.listFiles(FileFilter { it.isDirectory && it.resolve(updaterProperties.properties).exists() })!! }

    /**
     * Deletes the remappedSrc folder, necessary for Mercury.
     * @since 0.0.0
     * */
    val deleteOldMappings = register<Delete>("deleteMappings") {
        delete(root.resolve("remappedSrc"))
        if (updaterProperties.recursive) for (f in projects) delete(f.resolve("remappedSrc"))
    }

    /**
     * Executes migration of mappings, then immediately cleans loom.
     * @since 0.0.0
     * */
    val migrateMappings = register<Exec>("migrateMappings") {
        dependsOn(deleteOldMappings)
        // maybe find a way to go ahead and make this optionally recursive.
        if (isMinecraft) {
            val lastArg = when (updaterProperties.moddingAPI) {
                "fabric" -> "cleanLoom"
                else -> ""
            }
            workingDir = root
            if (System.getProperty("os.name").contains("windows", ignoreCase = true) && root.resolve("gradlew.bat").exists()) {
                commandLine("./gradlew.bat", "migrateMappings", "--mappings", updaterProperties.mappings!!, "clean", lastArg)
            } else if (!System.getProperty("os.name").contains("windows", ignoreCase = true) && root.resolve("gradlew").exists()) {
                commandLine("sh", "./gradlew", "migrateMappings", "--mappings", updaterProperties.mappings!!, "clean", lastArg)
            } else {
                commandLine("gradle", "migrateMappings", "--mappings", updaterProperties.mappings!!, "clean", lastArg)
            }
        }
    }

    /**
     * After migration of mappings, move them over src.
     * @since 0.0.0
     * */
    val moveMappings = register<Task>("moveMappings") {
        dependsOn(migrateMappings)
        doLast { moveMappings(root) }
        if (updaterProperties.recursive) for (path in projects) doLast { moveMappings(path) }
    }

    /**
     * Updates the dependencies after remapping if applicable.
     * @since 0.0.0
     * */
    @Suppress("UNUSED_VARIABLE") val updateDependencies = register<Task>("updateDependencies") {
        // Only applicable if Minecraft is defined.
        if (isMinecraft) dependsOn(moveMappings)
        doLast {
            val tree = Properties()
            tree.load(root.resolve(updaterProperties.properties))
            if (updaterProperties.recursive) for (f in projects) {
                tree.clear()
                tree.load(f.resolve(updaterProperties.properties))
            }
        }
    }
}

class N(file: File) {
    private val metadata: Map<String, String>
    private val cache = HashMap<String, String>()
    private val repositories: Map<String, String>
    val template: Map<String, String>

    val minecraftTarget: String?
    private val minecraftSnapshot: String?
    val moddingAPI: String?
    val properties: String
    val recursive: Boolean
    private val pullFirst: Boolean

    private val loader: String?
    val mappings: String?

    init {
        val properties = Properties()
        val metatmp = HashMap<String, String>()
        val repotmp = HashMap<String, String>()
        val temptmp = HashMap<String, String>()
        var mctatmp: String? = null
        var mcsntmp: String? = null
        var proptmp: String? = null
        var mapitmp: String? = null
        var mapptmp: String? = null
        var loadtmp: String? = null
        var recutmp = true
        var pulltmp = false
        properties.load(file.reader())
        for (k in properties.keys) {
            if (k !is String) throw IllegalStateException("updaterProperties(k -> v): $k -> ${properties[k]}")
            when (k[0]) {
                '@' -> repotmp[k] = properties.getProperty(k)
                '$' -> {
                    when (k) {
                        "\$minecraft.target" -> mctatmp = properties.getProperty(k)
                        "\$minecraft.snapshot" -> mcsntmp = properties.getProperty(k)
                        "\$modding.api" -> mapitmp = properties.getProperty(k)
                        "\$properties" -> proptmp = properties.getProperty(k)
                        "\$recursive" -> recutmp = properties.getProperty(k) == "true"
                        "\$pull_first" -> pulltmp = properties.getProperty(k) == "true"
                        else -> metatmp[k.substring(1)] = properties.getProperty(k)
                    }
                }
                else -> temptmp[k] = properties.getProperty(k)
            }
        }
        metadata = metatmp
        minecraftTarget = systemEnvironment.getOrElse("minecraft.target") { systemEnvironment.getOrDefault("mc_version", mctatmp) }
        minecraftSnapshot = mcsntmp
        this.properties = proptmp ?: "gradle.properties"
        moddingAPI = mapitmp
        if (moddingAPI != null && minecraftTarget != null) {
            when (moddingAPI) {
                "fabric" -> {
                    val tree = jsonMapper.readTree(URL("https://meta.fabricmc.net/v1/versions/loader/$minecraftTarget"))
                    if (tree.isEmpty)
                        throw IllegalArgumentException("Bad Minecraft version, no info was returned.")
                    loadtmp = tree[0]["loader"]["version"].asText() ?: throw NullPointerException("Something is not right, no loader was returned.")
                    mapptmp = tree[0]["mappings"]["version"].asText() ?: throw NullPointerException("Something is not right, no mappings was returned.")
                }
            }
        }
        loader = loadtmp
        mappings = mapptmp
        recursive = recutmp
        pullFirst = pulltmp
        repo@ for ((k, v) in repotmp) {
            when (v[0]) {
                '@' -> repotmp[k] = repotmp[v] ?: continue@repo
                '$' -> repotmp[k] = metatmp[v.substring(1)] ?: continue@repo
            }
        }
        repositories = repotmp
        temp@ for ((k, v) in temptmp) {
            when (v[0]) {
                '@' -> temptmp[k] = getDependency(v.split(','))
                '$' -> when (v) {
                    "\$minecraft.required" -> temptmp[k] = minecraftTarget?.replace("(\\d{2,})w(\\d{2})([a-zA-Z]+)".toRegex(), "${minecraftSnapshot}-alpha.$1.$2.$3") ?: continue@temp
                    "\$minecraft.target" -> temptmp[k] = minecraftTarget ?: continue@temp
                    "\$minecraft.snapshot" -> temptmp[k] = minecraftSnapshot ?: continue@temp
                    "\$mappings" -> temptmp[k] = mappings ?: continue@temp
                    "\$loader" -> temptmp[k] = loader ?: continue@temp
                    "\$properties" -> temptmp[k] = this.properties
                    else -> temptmp[k] = metatmp[v.substring(1)] ?: continue@temp
                }
            }
        }
        template = temptmp
    }

    fun getMetadata(env: String): String? = metadata[env]

    fun getMetadata(env: String, def: String): String = metadata[env] ?: def

    fun getDependency(path: List<String>): String {
        val repo = if (path[0][0] == '@') {
            repositories.getOrElse(path[0]) {
                println("Failed to find ${path[0]} in $repositories, falling back to default.")
                repositories.getOrDefault("@default", "https://repo.maven.apache.org/maven2/")
            }
        } else path[0]
        return cache.computeIfAbsent("${repo.replace("/$".toRegex(), "")}/${path[1].replace('.', '/')}/${path[2]}/maven-metadata.xml") {
            xmlMapper.readTree(URL(it))["versioning"]["release"].asText()
        }
    }
}