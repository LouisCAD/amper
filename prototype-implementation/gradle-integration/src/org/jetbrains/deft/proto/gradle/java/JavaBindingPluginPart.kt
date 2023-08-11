package org.jetbrains.deft.proto.gradle.java

import org.gradle.api.JavaVersion
import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.jetbrains.deft.proto.frontend.JavaPart
import org.jetbrains.deft.proto.frontend.Platform
import org.jetbrains.deft.proto.gradle.EntryPointType
import org.jetbrains.deft.proto.gradle.base.DeftNamingConventions
import org.jetbrains.deft.proto.gradle.base.PluginPartCtx
import org.jetbrains.deft.proto.gradle.base.SpecificPlatformPluginPart
import org.jetbrains.deft.proto.gradle.contains
import org.jetbrains.deft.proto.gradle.findEntryPoint
import org.jetbrains.deft.proto.gradle.java.JavaDeftNamingConvention.deftFragment
import org.jetbrains.deft.proto.gradle.java.JavaDeftNamingConvention.maybeCreateJavaSourceSet
import org.jetbrains.deft.proto.gradle.kmpp.KMPEAware
import org.jetbrains.deft.proto.gradle.kmpp.KotlinDeftNamingConvention
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.slf4j.Logger
import org.slf4j.LoggerFactory

fun applyJavaAttributes(ctx: PluginPartCtx) = JavaBindingPluginPart(ctx).apply()

/**
 * Plugin logic, bind to specific module, when only default target is available.
 */
class JavaBindingPluginPart(
    ctx: PluginPartCtx,
) : SpecificPlatformPluginPart(ctx, Platform.JVM), KMPEAware, DeftNamingConventions {

    companion object {
        val logger: Logger = LoggerFactory.getLogger("some-logger")
    }

    private val javaAPE: JavaApplication get() = project.extensions.getByType(JavaApplication::class.java)
    internal val javaPE: JavaPluginExtension get() = project.extensions.getByType(JavaPluginExtension::class.java)

    override val kotlinMPE: KotlinMultiplatformExtension =
        project.extensions.getByType(KotlinMultiplatformExtension::class.java)

    fun apply() {
        applyJavaTargetForKotlin()

        if (Platform.ANDROID in module) {
            logger.warn(
                "Cant enable java integration when android is enabled. " +
                        "Module: ${module.userReadableName}"
            )
            return
        }

        adjustJavaGeneralProperties()
        addJavaIntegration()
    }

    private fun applyJavaTargetForKotlin() = with(KotlinDeftNamingConvention) {
        leafPlatformFragments.forEach { fragment ->
            with(fragment.target!!) {
                fragment.parts.find<JavaPart>()?.target?.let { jvmTarget ->
                    fragment.compilation?.compileTaskProvider?.configure {
                        it as KotlinCompilationTask<KotlinJvmCompilerOptions>
                        println("Java compilation ${it.name} has jvmTarget $jvmTarget")
                        it.compilerOptions.jvmTarget.set(JvmTarget.fromTarget(jvmTarget))
                    }
                }
            }
        }
    }

    private fun adjustJavaGeneralProperties() {
        project.plugins.apply(ApplicationPlugin::class.java)

        if (leafPlatformFragments.size > 1)
            logger.warn(
                "Cant apply multiple settings for application plugin. " +
                        "Affected artifacts: ${platformArtifacts.joinToString { it.name }}. " +
                        "Applying application settings from first one."
            )
        val fragment = leafPlatformFragments.firstOrNull() ?: return
        val javaPart = fragment.parts.find<JavaPart>()
        javaAPE.apply {
            if (module.type.isLibrary()) return@apply
            val foundMainClass = if (javaPart?.mainClass != null) {
                javaPart.mainClass
            } else {
                findEntryPoint(fragment, EntryPointType.JVM, logger)
            }
            mainClass.set(foundMainClass)
        }
        javaPart?.target?.let {
            javaPE.targetCompatibility = JavaVersion.toVersion(it)
        }
        javaPart?.source?.let {
            javaPE.sourceCompatibility = JavaVersion.toVersion(it)
        }
        project.tasks.withType(KotlinCompile::class.java).configureEach {
            it.javaPackagePrefix = javaPart?.packagePrefix
        }
    }


    // TODO Rewrite this completely by not calling
    //  KMPP code and following out own conventions.
    private fun addJavaIntegration() {
        project.plugins.apply(JavaPlugin::class.java)

        kotlinMPE.targets.toList().forEach {
            if (it is KotlinJvmTarget) it.withJava()
        }

        // Set sources for all deft related source sets.
        platformFragments.forEach {
            it.maybeCreateJavaSourceSet()
        }

        javaPE.sourceSets.all {
            val fragment = it.deftFragment ?: return@all
            it.java.setSrcDirs(fragment.sourcePaths)
            it.resources.setSrcDirs(fragment.resourcePaths)
        }
    }
}