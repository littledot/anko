/*
 * Copyright 2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.android.anko.render

import org.jetbrains.android.anko.*
import org.jetbrains.android.anko.config.*
import org.jetbrains.android.anko.generator.GenerationState
import org.jetbrains.android.anko.generator.ViewElement
import org.jetbrains.android.anko.generator.ViewGenerator
import org.jetbrains.android.anko.generator.ViewGroupGenerator
import org.jetbrains.android.anko.utils.*
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import java.util.*

internal class ViewRenderer(config: AnkoConfiguration) : AbstractViewRenderer(config) {
    override fun processElements(state: GenerationState) =
            renderViews(state[ViewGenerator::class.java]) { it.fqName }
}

internal class ViewGroupRenderer(config: AnkoConfiguration) : AbstractViewRenderer(config) {
    override fun processElements(state: GenerationState) =
            renderViews(state[ViewGroupGenerator::class.java]) { "_" + it.simpleName }
}

class ViewFactoryClass(val config: AnkoConfiguration, val suffix: String) {
    private val name = config.artifactName.toCamelCase('-').capitalize()

    val entries = arrayListOf<String>()
    val fullName = "`${'$'}${'$'}Anko${'$'}Factories${'$'}$name$suffix`"

    fun render(): String {
        if (entries.isEmpty()) return ""

        return StringBuilder().apply {
            appendln("object $fullName {")
            entries.forEach { append(config.indent).appendln(it) }
            appendln("}").appendln()
        }.toString()
    }
}

internal abstract class AbstractViewRenderer(
        config: AnkoConfiguration
) : Renderer(config), ViewConstructorUtils {

    override val renderIf: Array<ConfigurationOption> = arrayOf(AnkoFile.VIEWS, ConfigurationTune.HELPER_CONSTRUCTORS)

    protected fun renderViews(views: Iterable<ViewElement>, nameResolver: (ClassNode) -> String): String = StringBuilder().apply {
        val renderViews = config[AnkoFile.VIEWS]
        val renderHelperConstructors = config[ConfigurationTune.HELPER_CONSTRUCTORS]

        val factoryClass = ViewFactoryClass(config, this@AbstractViewRenderer.javaClass.simpleName.replace("Renderer", ""))
        val functions = StringBuilder().apply {
            for (view in views.filter { !it.clazz.isAbstract }) {
                if (renderViews) renderView(view.clazz, view.isContainer, nameResolver(view.clazz), factoryClass)

                if (renderHelperConstructors) {
                    if (Props.helperConstructors.contains(view.fqName)) {
                        append(renderHelperConstructors(view, factoryClass))
                    } else if (view.clazz.isTinted()) {
                        val (className21, unused) = handleTintedView(view.clazz, nameResolver(view.clazz))
                        if (Props.helperConstructors.contains("android.widget.$className21")) {
                            append(renderHelperConstructors(view, factoryClass))
                        }
                    }
                }
            }
        }.toString()

        append(factoryClass.render())
        append(functions)
    }.toString()

    private fun StringBuilder.renderView(
            view: ClassNode,
            isContainer: Boolean,
            className: String,
            factoryClass: ViewFactoryClass
    ) {
        val constructors = ViewConstructorUtils.AVAILABLE_VIEW_CONSTRUCTORS.map { constructor ->
            view.getConstructors().firstOrNull() { Arrays.equals(it.args, constructor) }
        }

        val (className21, functionName) = handleTintedView(view, className)
        val tinted = className21 != null

        val factoryPropertyName = functionName.capitalize().toUPPER_CASE()
        with (factoryClass.entries) {
            val constructorArgs = renderConstructorArgs(view, constructors, "ctx")
            val constructorCall = if (tinted)
                "if (Build.VERSION.SDK_INT < 21) $className($constructorArgs) else $className21($constructorArgs)"
            else
                "$className($constructorArgs)"
            add("val $factoryPropertyName = { ctx: Context -> $constructorCall }")
        }

        fun renderView(receiver: String) = render("view") {
            "receiver" % receiver
            "functionName" % functionName
            "className" % className
            "lambdaArgType" % if (tinted) className21 else className
            "returnType" % if (tinted) className21 else view.fqName
            "additionalParams" % ""
            "factory" % (factoryClass.fullName + ".$factoryPropertyName")
        }

        append(renderView("ViewManager"))
        if (config[ConfigurationTune.TOP_LEVEL_DSL_ITEMS] && isContainer) {
            append(renderView("Context"))
            append(renderView("Activity"))
        }
    }

    private fun renderHelperConstructors(view: ViewElement, factoryClass: ViewFactoryClass) = StringBuilder().apply {
        val className = view.fqName

        val (className21, functionName) = handleTintedView(view.clazz, className)
        val tinted = className21 != null
        val lambdaArgType = if (tinted) className21 else className

        val helperConstructors = Props.helperConstructors[if (tinted) "android.widget.$className21" else view.fqName] ?:
                throw RuntimeException("Helper constructors not found for $className")
        val factory = factoryClass.fullName + "." + functionName.capitalize().toUPPER_CASE()

        for (constructor in helperConstructors) {
            val collected = constructor.zip(collectProperties(view, constructor))
            val helperArguments = collected.map {
                val argumentType = it.second.args[0].asString()
                "${it.first.name}: $argumentType"
            }.joinToString(", ")
            val setters = collected.map { "${it.second.name}(${it.first.name})" }

            fun add(extendFor: String) = buffer {
                val returnType = if (tinted) className21 else className

                line("inline fun $extendFor.$functionName(theme: Int = 0, $helperArguments): $returnType {")
                line("return ankoView($factory, theme) {")
                lines(setters)
                line("}")
                line("}")

                line("inline fun $extendFor.$functionName(theme: Int = 0, $helperArguments, init: $lambdaArgType.() -> Unit): $returnType {")
                line("return ankoView($factory, theme) {")
                line("init()")
                lines(setters)
                line("}")
                line("}")
                nl()
            }.toString()

            append(add("ViewManager"))
        }
    }.toString()

    private fun collectProperties(view: ViewElement, properties: List<Variable>): List<MethodNode> {
        return properties.map { property ->
            val methodName = "set" + property.name.capitalize()
            val methods = view.allMethods.filter {
                it.name == methodName && it.args.size == 1 && it.args[0].fqName.endsWith(property.type)
            }

            when (methods.size) {
                0 -> throw RuntimeException("Can't find a method $methodName for helper constructor ${view.fqName}($properties)")
                1 -> {}
                else -> throw RuntimeException("There are several methods $methodName for helper constructor ${view.fqName}($properties)")
            }

            methods[0]
        }
    }

    private fun handleTintedView(view: ClassNode, className: String): Pair<String?, String> {
        val tinted = view.isTinted()
        val className21 = if (tinted) className.substring(APP_COMPAT_VIEW_PREFIX.length) else null
        val functionName = if (tinted) "tinted$className21" else view.simpleName.decapitalize()
        return className21 to functionName
    }

    private fun ClassNode.isTinted() = fqName.startsWith(APP_COMPAT_VIEW_PREFIX)

    private companion object {
        val APP_COMPAT_VIEW_PREFIX = "android.support.v7.widget.AppCompat"
    }

}