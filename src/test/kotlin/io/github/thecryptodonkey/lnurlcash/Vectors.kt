package io.github.thecryptodonkey.lnurlcash

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The shared conformance vectors, read from the sibling repo. Nothing in the
 * Kotlin tests states what the protocol is - these files do.
 */
internal object Vectors {
    private val json = Json { ignoreUnknownKeys = true }

    val directory: Path by lazy {
        val configured = System.getenv("LNURLCASH_CONFORMANCE")
        val base = if (configured != null) {
            Paths.get(configured)
        } else {
            Paths.get("").toAbsolutePath().parent.resolve("lnurlcash-conformance")
        }
        base.resolve("vectors")
    }

    val available: Boolean get() = Files.isDirectory(directory)

    fun load(name: String): JsonObject =
        json.parseToJsonElement(Files.readString(directory.resolve(name))).jsonObject

    fun cases(file: String, key: String): List<JsonObject> =
        load(file)[key]!!.jsonArray.map { it.jsonObject }
}

internal fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

internal fun JsonObject.strOrNull(key: String): String? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

internal fun JsonObject.long(key: String): Long = this[key]!!.jsonPrimitive.long

internal fun JsonObject.longOrNull(key: String): Long? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long

internal fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.boolean

internal fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject

internal fun JsonObject.array(key: String): JsonArray = this[key]!!.jsonArray

internal fun JsonElement.mintFee(): MintFee =
    MintFee(baseFeeMsat = jsonObject.long("baseFeeMsat"), feePpm = jsonObject.long("feePpm"))
