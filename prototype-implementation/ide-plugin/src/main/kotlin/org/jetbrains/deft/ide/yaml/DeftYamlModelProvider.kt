package org.jetbrains.deft.ide.yaml

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.ModificationTracker
import org.jetbrains.yaml.meta.model.Field
import org.jetbrains.yaml.meta.model.ModelAccess
import org.jetbrains.yaml.psi.YAMLDocument

@Suppress("UnstableApiUsage")
@Service(Service.Level.APP)
internal class DeftYamlModelProvider private constructor() {
    val meta: ModelAccess = object : ModelAccess {
        private val model: Field by lazy { Field("<deft-root>", DeftYamlModel()) }

        override fun getRoot(document: YAMLDocument): Field = model
    }

    companion object {
        @JvmStatic
        fun getInstance(): DeftYamlModelProvider = service()

        @JvmField
        internal val TRACKER = ModificationTracker { 0 }
    }
}
