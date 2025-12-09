package com.vcontrol.ktor

import io.ktor.utils.io.KtorDsl
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * DSL builder for constructing JSON Schema for MCP tool parameters.
 *
 * Example:
 * ```kotlin
 * schema {
 *     string("name", "User's name", required = true)
 *     int("age", "User's age") { minimum = 0; maximum = 150 }
 *     enum("status", "Status", listOf("active", "inactive"))
 *     obj("address", "Address") {
 *         string("street", "Street", required = true)
 *         string("city", "City", required = true)
 *     }
 * }
 * ```
 */
@KtorDsl
public class McpSchemaBuilder {
    private val properties = mutableMapOf<String, JsonObject>()
    private val requiredProps = mutableListOf<String>()

    /**
     * Add a string parameter.
     */
    public fun string(
        name: String,
        description: String,
        required: Boolean = false,
        block: StringConstraints.() -> Unit = {}
    ) {
        val cfg = StringConstraints().apply(block)
        properties[name] = buildJsonObject {
            put("type", "string")
            put("description", description)
            cfg.minLength?.let { put("minLength", it) }
            cfg.maxLength?.let { put("maxLength", it) }
            cfg.pattern?.let { put("pattern", it) }
        }
        if (required) requiredProps.add(name)
    }

    /**
     * Add an integer parameter.
     */
    public fun int(
        name: String,
        description: String,
        required: Boolean = false,
        block: NumberConstraints.() -> Unit = {}
    ) {
        val cfg = NumberConstraints().apply(block)
        properties[name] = buildJsonObject {
            put("type", "integer")
            put("description", description)
            cfg.minimum?.let { put("minimum", it) }
            cfg.maximum?.let { put("maximum", it) }
        }
        if (required) requiredProps.add(name)
    }

    /**
     * Add a number (floating point) parameter.
     */
    public fun number(
        name: String,
        description: String,
        required: Boolean = false,
        block: NumberConstraints.() -> Unit = {}
    ) {
        val cfg = NumberConstraints().apply(block)
        properties[name] = buildJsonObject {
            put("type", "number")
            put("description", description)
            cfg.minimum?.let { put("minimum", it) }
            cfg.maximum?.let { put("maximum", it) }
        }
        if (required) requiredProps.add(name)
    }

    /**
     * Add a boolean parameter.
     */
    public fun boolean(name: String, description: String, required: Boolean = false) {
        properties[name] = buildJsonObject {
            put("type", "boolean")
            put("description", description)
        }
        if (required) requiredProps.add(name)
    }

    /**
     * Add an enum (string with allowed values) parameter.
     */
    public fun enum(name: String, description: String, values: List<String>, required: Boolean = false) {
        properties[name] = buildJsonObject {
            put("type", "string")
            put("description", description)
            put("enum", JsonArray(values.map { JsonPrimitive(it) }))
        }
        if (required) requiredProps.add(name)
    }

    /**
     * Add a string array parameter.
     */
    public fun stringArray(name: String, description: String, required: Boolean = false) {
        properties[name] = buildJsonObject {
            put("type", "array")
            put("description", description)
            putJsonObject("items") { put("type", "string") }
        }
        if (required) requiredProps.add(name)
    }

    /**
     * Add an integer array parameter.
     */
    public fun intArray(name: String, description: String, required: Boolean = false) {
        properties[name] = buildJsonObject {
            put("type", "array")
            put("description", description)
            putJsonObject("items") { put("type", "integer") }
        }
        if (required) requiredProps.add(name)
    }

    /**
     * Add a nested object parameter.
     */
    public fun obj(
        name: String,
        description: String,
        required: Boolean = false,
        block: McpSchemaBuilder.() -> Unit
    ) {
        val nested = McpSchemaBuilder().apply(block)
        properties[name] = buildJsonObject {
            put("type", "object")
            put("description", description)
            put("properties", JsonObject(nested.properties))
            if (nested.requiredProps.isNotEmpty()) {
                put("required", JsonArray(nested.requiredProps.map { JsonPrimitive(it) }))
            }
        }
        if (required) requiredProps.add(name)
    }

    internal fun build(): ToolSchema = ToolSchema(
        properties = JsonObject(properties),
        required = requiredProps.ifEmpty { null }
    )
}

/**
 * Constraints for string parameters.
 */
public class StringConstraints {
    /** Minimum length of the string. */
    public var minLength: Int? = null
    /** Maximum length of the string. */
    public var maxLength: Int? = null
    /** Regex pattern the string must match. */
    public var pattern: String? = null
}

/**
 * Constraints for numeric parameters (int/number).
 */
public class NumberConstraints {
    /** Minimum value (inclusive). */
    public var minimum: Number? = null
    /** Maximum value (inclusive). */
    public var maximum: Number? = null
}
