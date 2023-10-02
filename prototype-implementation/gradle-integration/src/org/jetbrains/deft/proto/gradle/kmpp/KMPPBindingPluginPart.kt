package org.jetbrains.deft.proto.gradle.kmpp

import org.gradle.api.attributes.Attribute
import org.gradle.api.file.SourceDirectorySet
import org.gradle.configurationcache.extensions.capitalized
import org.jetbrains.deft.proto.core.map
import org.jetbrains.deft.proto.core.messages.ProblemReporterContext
import org.jetbrains.deft.proto.frontend.*
import org.jetbrains.deft.proto.gradle.*
import org.jetbrains.deft.proto.gradle.base.*
import org.jetbrains.deft.proto.gradle.java.JavaBindingPluginPart
import org.jetbrains.deft.proto.gradle.kmpp.KotlinDeftNamingConvention.compilation
import org.jetbrains.deft.proto.gradle.kmpp.KotlinDeftNamingConvention.deftFragment
import org.jetbrains.deft.proto.gradle.kmpp.KotlinDeftNamingConvention.kotlinSourceSet
import org.jetbrains.deft.proto.gradle.kmpp.KotlinDeftNamingConvention.target
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.extraProperties
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBinary
import java.io.File


// Introduced function to remember to propagate language settings.
context(KMPEAware)
fun KotlinSourceSet.doDependsOn(it: FragmentWrapper) {
    val dependency = it.kotlinSourceSet
    dependsOn(dependency ?: return)
}

private fun KotlinSourceSet.doApplyPart(kotlinPart: KotlinPart?) = languageSettings.apply {
    kotlinPart ?: return@apply
    languageVersion = kotlinPart.languageVersion
    apiVersion = kotlinPart.apiVersion
    if (progressiveMode != (kotlinPart.progressiveMode ?: false)) progressiveMode =
        kotlinPart.progressiveMode ?: false
    kotlinPart.languageFeatures.forEach { enableLanguageFeature(it.capitalized()) }
    kotlinPart.optIns.forEach { optIn(it) }
}

/**
 * Plugin logic, bind to specific module, when multiple targets are available.
 */
context(ProblemReporterContext)
class KMPPBindingPluginPart(
    ctx: PluginPartCtx,
) : BindingPluginPart by ctx, KMPEAware, DeftNamingConventions {

    internal val fragmentsByName = module.fragments.associateBy { it.name }

    override val kotlinMPE: KotlinMultiplatformExtension =
        project.extensions.getByType(KotlinMultiplatformExtension::class.java)

    override val needToApply = true

    override fun applyBeforeEvaluate() {
        initTargets()
        initFragments()
        project.configurations.all { config ->
            config.resolutionStrategy.capabilitiesResolution.withCapability("org.jetbrains.kotlin:kotlin-test-framework-impl") {
                it.select(it.candidates.first())
                it.because("Select first, because they are the same")
            }
        }
    }

    override fun applyAfterEvaluate() {
        // IOS Compose uses UiKit, so we need to explicitly enable it, since it is experimental.
        project.extraProperties.set("org.jetbrains.compose.experimental.uikit.enabled", "true")

        // Do after fragments init!
        adjustSourceSetDirectories()
        project.afterEvaluate {
            // We need to do that second time, because of tricky gradle/KMPP/android stuff.
            //
            // First call is needed because we need to search for entry point.
            // Second call is needed, because we need to rewrite changes from KMPP that
            // are done in "afterEvaluate" also.
            adjustSourceSetDirectories(false)
        }
    }

    /**
     * Set deft specific directory layout.
     */
    private fun adjustSourceSetDirectories(firstTime: Boolean = true) {
        kotlinMPE.sourceSets.all { sourceSet ->
            val fragment = sourceSet.deftFragment
            when {
                // Do GRADLE_JVM specific.
                layout == Layout.GRADLE_JVM -> {
                    if (sourceSet.name == "jvmMain") {
                        replacePenultimatePaths(sourceSet.kotlin, sourceSet.resources, "main")
                    } else if (sourceSet.name == "jvmTest") {
                        replacePenultimatePaths(sourceSet.kotlin, sourceSet.resources, "test")
                    }
                }

                // Do GRADLE specific.
                firstTime && (layout == Layout.GRADLE || layout == Layout.GRADLE_JVM) ->
                    adjustForGradleLayout(sourceSet)

                // Do DEFT specific.
                layout == Layout.DEFT && fragment != null -> {
                    sourceSet.kotlin.setSrcDirs(listOf(fragment.src))
                    sourceSet.resources.setSrcDirs(listOf(fragment.resourcesPath))
                }
                layout == Layout.DEFT && fragment == null -> {
                    sourceSet.kotlin.setSrcDirs(emptyList<File>())
                    sourceSet.resources.setSrcDirs(emptyList<File>())
                }
            }
        }
    }

    private fun adjustForGradleLayout(sourceSet: KotlinSourceSet) {
        if (sourceSet.name == "common") {
            val commonMainSourceSet = kotlinMPE.sourceSets.findByName("commonMain")
            val commonMainSources = commonMainSourceSet?.kotlin?.srcDirs ?: emptyList()
            val commonMainResources = commonMainSourceSet?.resources?.srcDirs ?: emptyList()

            sourceSet.kotlin.setSrcDirs(commonMainSources)
            sourceSet.resources.setSrcDirs(commonMainResources)
            commonMainSourceSet?.kotlin?.setSrcDirs(emptyList<File>())
            commonMainSourceSet?.resources?.setSrcDirs(emptyList<File>())
        }
    }

    fun afterAll() {
        // Need after evaluate to catch up android compilation creation.
        project.afterEvaluate {
            module.leafFragments.forEach { fragment ->
                with(fragment.target ?: return@forEach) {
                    fragment.compilation?.apply {
                        fragment.parts.find<KotlinPart>()?.let {
                            kotlinOptions::allWarningsAsErrors trySet it.allWarningsAsErrors
                            kotlinOptions::freeCompilerArgs trySet it.freeCompilerArgs
                            kotlinOptions::suppressWarnings trySet it.suppressWarnings
                            kotlinOptions::verbose trySet it.verbose
                        }

                        // Set jvm target for all jvm like compilations.
                        val selectedJvmTarget = fragment.parts.find<JvmPart>()?.target
                        if (selectedJvmTarget != null) {
                            compileTaskProvider.configure {
                                it.compilerOptions.apply {
                                    this as? KotlinJvmCompilerOptions ?: return@apply
                                    this.jvmTarget.set(JvmTarget.fromTarget(selectedJvmTarget))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun initTargets() = with(KotlinDeftNamingConvention) {
        module.artifactPlatforms.forEach {
            val targetName = it.targetName
            when (it) {
                Platform.ANDROID -> kotlinMPE.androidTarget(targetName)
                Platform.JVM -> kotlinMPE.jvm(targetName)
                Platform.IOS_ARM64 -> kotlinMPE.iosArm64(targetName)
                Platform.IOS_SIMULATOR_ARM64 -> kotlinMPE.iosSimulatorArm64(targetName)
                Platform.IOS_X64 -> kotlinMPE.iosX64(targetName)
                Platform.MACOS_X64 -> kotlinMPE.macosX64(targetName)
                Platform.MACOS_ARM64 -> kotlinMPE.macosArm64(targetName)
                Platform.LINUX_X64 -> kotlinMPE.linuxX64(targetName)
                Platform.LINUX_ARM64 -> kotlinMPE.linuxArm64(targetName)
                Platform.JS -> kotlinMPE.js(targetName)
                else -> error("Unsupported platform: $targetName")
            }
        }

        // Skip tests binary creation for now.
        module.leafFragments.forEach { fragment ->
            val target = fragment.target ?: return@forEach
            with(target) target@{
                if (fragment.platform != Platform.ANDROID) {
                    fragment.maybeCreateCompilation {
                        if (this@target is KotlinNativeTarget)
                            adjust(
                                this@target,
                                this as KotlinNativeCompilation,
                                fragment
                            )
                    }
                }
            }
        }
    }

    private val disambiguationVariantAttribute = Attribute.of(
        "org.jetbrains.kotlin.deft.target.variant",
        String::class.java
    )

    private fun adjust(
        target: KotlinNativeTarget,
        kotlinNativeCompilation: KotlinNativeCompilation,
        fragment: LeafFragmentWrapper,
    ) {
        if (module.type.isLibrary()) return
        val part = fragment.parts.find<NativeApplicationPart>()

        target.binaries {
            when {
                (module.type == ProductType.IOS_APP && !fragment.isTest) -> framework(fragment.name) {
                    adjustExecutable(fragment, kotlinNativeCompilation)
                }

                fragment.isTest -> test(fragment.name) {
                    adjustExecutable(fragment, kotlinNativeCompilation)
                }

                else -> executable(fragment.name) {
                    adjustExecutable(fragment, kotlinNativeCompilation)
                    project.afterEvaluate {
                        // Check if entry point was not set in build script.
                        if (entryPoint == null) {
                            entryPoint = if (part?.entryPoint != null) {
                                part.entryPoint
                            } else {
                                val sources = kotlinNativeCompilation.defaultSourceSet.closureSources
                                findEntryPoint(sources, EntryPointType.NATIVE, JavaBindingPluginPart.logger)
                            }
                        }
                    }
                }
            }
        }
        // workaround to have a few variants of the same darwin target
        kotlinNativeCompilation.attributes {
            attribute(
                disambiguationVariantAttribute,
                target.name
            )
        }
    }

    private fun NativeBinary.adjustExecutable(
        fragment: LeafFragmentWrapper,
        kotlinNativeCompilation: KotlinNativeCompilation,
    ) {
        compilation = kotlinNativeCompilation
        fragment.parts.find<NativeApplicationPart>()?.let {
            ::baseName trySet it.baseName
            ::optimized trySet it.optimized
            binaryOptions.putAll(it.binaryOptions)
        }
        fragment.parts.find<KotlinPart>()?.let {
            linkerOpts.addAll(it.linkerOpts)
            ::debuggable trySet it.debug
        }
    }

    private fun initFragments() = with(KotlinDeftNamingConvention) {
        // First iteration - create source sets and add dependencies.
        module.fragments.forEach { fragment ->
            fragment.maybeCreateSourceSet {
                dependencies {
                    fragment.externalDependencies.forEach { externalDependency ->
                        val depFunction: KotlinDependencyHandler.(Any) -> Unit =
                            if (externalDependency is DefaultScopedNotation) with(externalDependency) {
                                // tmp variable fixes strangely red code with "ambiguity"
                                val tmp: KotlinDependencyHandler.(Any) -> Unit = when {
                                    compile && runtime && !exported -> KotlinDependencyHandler::implementation
                                    !compile && runtime && !exported -> KotlinDependencyHandler::runtimeOnly
                                    compile && !runtime && !exported -> KotlinDependencyHandler::compileOnly
                                    compile && runtime && exported -> KotlinDependencyHandler::api
                                    compile && !runtime && exported -> error("Not supported")
                                    !compile && runtime && exported -> error("Not supported")
                                    !compile && !runtime -> error("At least one scope of (compile, runtime) must be declared")
                                    else -> KotlinDependencyHandler::implementation
                                }
                                tmp
                            } else {
                                { implementation(it) }
                            }
                        when (externalDependency) {
                            is MavenDependency -> depFunction(externalDependency.coordinates)
                            is PotatoModuleDependency -> with(externalDependency) {
                                model.module.map { depFunction(it.linkedProject) }
                            }

                            else -> error("Unsupported dependency type: $externalDependency")
                        }
                    }

                    // Add a separator to WA kotlin-test bug: https://youtrack.jetbrains.com/issue/KT-60913/Rework-kotlin-test-jvm-variant-inference
                    // Add implicit tests dependencies.
                    // TODO In the future we wil need a flag to disable this on user will.
                    if (fragment.isTest) {
                        if (fragment.platforms.all { it == Platform.JVM || it == Platform.ANDROID }) {
                            implementation(kotlin("test-junit5"))
                            implementation(kotlin("test"))
                            implementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
                            implementation("org.junit.jupiter:junit-jupiter-engine:5.9.2")
                        } else {
                            implementation(kotlin("test"))
                        }
                    }
                }
            }
        }

        val adjustedSourceSets = mutableSetOf<KotlinSourceSet>()

        // Second iteration - create dependencies between fragments (aka source sets) and set source/resource directories.
        module.fragments.forEach { fragment ->
            val sourceSets = fragment.matchingKotlinSourceSets

            for (sourceSet in sourceSets) {
                // Apply language settings.
                sourceSet.doApplyPart(fragment.parts.find<KotlinPart>())
                adjustedSourceSets.add(sourceSet)
            }
        }

        // we imply, sourceSets which was not touched by loop by fragments, they depend only on common
        // to avoid gradle incompatibility error between sourceSets we apply to sourceSets left settings from common
        val commonKotlinPart = module.fragments
            .firstOrNull { it.fragmentDependencies.none { it.type == FragmentDependencyType.REFINE } }
            ?.parts
            ?.find<KotlinPart>()

        (kotlinMPE.sourceSets.toSet() - adjustedSourceSets).forEach { sourceSet ->
            commonKotlinPart?.let {
                sourceSet.doApplyPart(it)
            }
        }

        // it is implied newly added sourceSets will depend on common
        kotlinMPE.sourceSets
            .matching { !adjustedSourceSets.contains(it) }
            .configureEach { sourceSet ->
                commonKotlinPart?.let {
                    sourceSet.doApplyPart(it)
                }
            }

        module.fragments.forEach { fragment ->
            // TODO Replace with inner classes structure for wrappers.
            with(module) {
                val sourceSet = fragment.kotlinSourceSet
                // Set dependencies.
                fragment.fragmentDependencies.forEach {
                    when (it.type) {
                        FragmentDependencyType.REFINE ->
                            sourceSet?.doDependsOn(it.target.wrapped)

                        FragmentDependencyType.FRIEND ->
                            // TODO Add associate with for related compilations.
                            // Not needed for default "test" - "main" relations.
                            run { }
                    }
                }
            }
        }

        // Third iteration - adjust kotlin prebuilt source sets (UNMANAGED ones)
        // to match created ones.
        module.leafFragments.forEach { fragment ->
            val platform = fragment.platform
            val target = fragment.target ?: return@forEach
            with(target) {
                val compilation = fragment.compilation ?: return@forEach
                compilation.source(
                    fragment.kotlinSourceSet ?: error("Sourceset not found for fragment ${fragment.name}")
                )
                val compilationSourceSet = compilation.defaultSourceSet
                if (compilationSourceSet != fragment.kotlinSourceSet) {
                    // Add dependency from compilation source set ONLY for unmanaged source sets.
                    if (compilationSourceSet.deftFragment == null) {
                        compilationSourceSet.dependsOn(fragment.kotlinSourceSet ?: return@with)
                    }
                }
            }
        }

    }

    // ------
    private fun FragmentWrapper.maybeCreateSourceSet(
        block: KotlinSourceSet.() -> Unit,
    ) {
        val sourceSet = kotlinMPE.sourceSets.maybeCreate(name)
        sourceSet.block()
    }
}
