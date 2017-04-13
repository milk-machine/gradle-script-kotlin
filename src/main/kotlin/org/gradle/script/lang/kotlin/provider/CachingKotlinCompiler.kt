/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.script.lang.kotlin.provider

import org.gradle.cache.CacheRepository
import org.gradle.cache.PersistentCache
import org.gradle.cache.internal.CacheKeyBuilder
import org.gradle.cache.internal.CacheKeyBuilder.CacheKeySpec

import org.gradle.internal.classpath.ClassPath
import org.gradle.internal.logging.progress.ProgressLoggerFactory

import org.gradle.script.lang.kotlin.support.loggerFor
import org.gradle.script.lang.kotlin.support.ImplicitImports
import org.gradle.script.lang.kotlin.support.compileKotlinScriptToDirectory
import org.gradle.script.lang.kotlin.support.compileToDirectory
import org.gradle.script.lang.kotlin.support.messageCollectorFor

import org.jetbrains.kotlin.com.intellij.openapi.project.Project

import org.jetbrains.kotlin.script.KotlinScriptDefinition
import org.jetbrains.kotlin.script.KotlinScriptExternalDependencies

import java.io.File
import java.nio.file.Path

import kotlin.reflect.KClass

sealed class ScriptResource(val path: Path, val lineNumber: Int)
data class ScriptFile(val aPath: Path, val file: File, val aLineNumber: Int = 0) : ScriptResource(aPath, aLineNumber)
data class ScriptString(val aPath: Path, val body: String, val aLineNumber: Int = 0) : ScriptResource(aPath, aLineNumber)

class CachingKotlinCompiler(
    val cacheKeyBuilder: CacheKeyBuilder,
    val cacheRepository: CacheRepository,
    val progressLoggerFactory: ProgressLoggerFactory,
    val recompileScripts: Boolean) {

    private val logger = loggerFor<KotlinScriptPluginFactory>()

    private val cacheKeyPrefix = CacheKeySpec.withPrefix("gradle-script-kotlin")

    data class ScriptCompilationRequest(val scriptTemplate: KClass<out Any>,
                                        val scriptResource: ScriptResource,
                                        val classPath: ClassPath, val parentClassLoader: ClassLoader,
                                        val description: String = "",
                                        val additionalSourceFiles: List<File> = emptyList())

    private data class ScriptCompilationSpec(val compilationRequest: ScriptCompilationRequest, val scriptFile: File)

    data class CompiledScript(val location: File, val className: String, val request: ScriptCompilationRequest)

    fun compileScript(compilationRequest: ScriptCompilationRequest): CompiledScript =
        cachedCompileScript(compilationRequest) { cacheDir ->
            ScriptCompilationSpec(compilationRequest, fileFor(cacheDir, compilationRequest))
        }

    private
    fun fileFor(cacheDir: File, compilationRequest: ScriptCompilationRequest) =
        compilationRequest.run {
            when (scriptResource) {
                is ScriptString -> cacheFileFor(cacheDir, scriptResource)
                is ScriptFile   -> scriptResource.file
            }
        }

    fun compileLib(sourceFiles: List<File>, classPath: ClassPath): File =
        withCacheFor(cacheKeySpecOf(sourceFiles, classPath)) {
            withProgressLoggingFor("Compiling Kotlin build script library") {
                compileToDirectory(baseDir, sourceFiles, logger, classPath.asFiles)
            }
        }

    private
    fun cacheKeySpecOf(compilationRequest: ScriptCompilationRequest): CacheKeySpec =
        compilationRequest.run {
            var spec = cacheKeyPrefix + scriptTemplate.qualifiedName +
                       scriptResource.path.fileName.toString() + parentClassLoader
            when (scriptResource) {
                is ScriptString -> spec += scriptResource.body
                is ScriptFile   -> spec += scriptResource.file
            }
            return spec
        }

    private
    fun cacheKeySpecOf(sourceFiles: List<File>, classPath: ClassPath): CacheKeySpec {
        require(sourceFiles.isNotEmpty()) { "Expecting at least one Kotlin source file, got none." }
        return sourceFiles.fold(cacheKeyPrefix + classPath, CacheKeySpec::plus)
    }

    private
    fun cachedCompileScript(
        compilationRequest: ScriptCompilationRequest,
        compilationSpecFor: (File) -> ScriptCompilationSpec): CompiledScript {

        val cacheDir = withCacheFor(cacheKeySpecOf(compilationRequest)) {
            val scriptClass = compileScriptTo(classesDirOf(baseDir), compilationSpecFor(baseDir))
            writeClassNameTo(baseDir, scriptClass.name)
        }
        return CompiledScript(classesDirOf(cacheDir), readClassNameFrom(cacheDir), compilationRequest)
    }

    private
    fun withCacheFor(cacheKeySpec: CacheKeySpec, initializer: PersistentCache.() -> Unit): File =
        cacheRepository
            .cache(cacheKeyFor(cacheKeySpec))
            .withProperties(mapOf("version" to "3"))
            .let { if (recompileScripts) it.withValidator { false } else it }
            .withInitializer(initializer)
            .open().run {
                close()
                baseDir
            }

    private
    fun compileScriptTo(outputDir: File, spec: ScriptCompilationSpec): Class<*> =

        spec.run {
            withProgressLoggingFor(compilationRequest.description) {
                logger.debug("Kotlin compilation classpath for {}: {}",
                             compilationRequest.description, compilationRequest.classPath)
                compileKotlinScriptToDirectory(
                    outputDir,
                    scriptFile,
                    scriptDefinitionFromTemplate(compilationRequest.scriptTemplate),
                    compilationRequest.additionalSourceFiles,
                    compilationRequest.classPath.asFiles,
                    compilationRequest.parentClassLoader,
                    messageCollectorFor(logger) { path ->
                        if (path == scriptFile.path) compilationRequest.scriptResource.path.toString()
                        else path
                    })
            }
        }


    private fun cacheKeyFor(spec: CacheKeySpec) = cacheKeyBuilder.build(spec)

    private fun writeClassNameTo(cacheDir: File, className: String) =
        scriptClassNameFile(cacheDir).writeText(className)

    private fun readClassNameFrom(cacheDir: File) =
        scriptClassNameFile(cacheDir).readText()

    private fun scriptClassNameFile(cacheDir: File) = File(cacheDir, "script-class-name")

    private fun classesDirOf(cacheDir: File) = File(cacheDir, "classes")

    private fun cacheFileFor(cacheDir: File, scriptResource: ScriptString) =
        File(cacheDir, scriptResource.path.fileName.toString()).apply {
            writeText(scriptResource.body)
        }

    private fun scriptDefinitionFromTemplate(template: KClass<out Any>) =

        object : KotlinScriptDefinition(template) {

            override fun <TF : Any> getDependenciesFor(
                file: TF,
                project: Project,
                previousDependencies: KotlinScriptExternalDependencies?): KotlinScriptExternalDependencies? =

                object : KotlinScriptExternalDependencies {
                    override val imports: Iterable<String>
                        get() = ImplicitImports.list
                }
        }

    private fun <T> withProgressLoggingFor(description: String, action: () -> T): T {
        val operation = progressLoggerFactory
            .newOperation(this::class.java)
            .start("Compiling script into cache", "Compiling $description into local build cache")
        try {
            return action()
        } finally {
            operation.completed()
        }
    }
}
