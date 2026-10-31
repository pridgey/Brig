package dev.pridgey.brig.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class ReleaseInfo(
    val tag: String,
    val versionCode: Int,
    val name: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
    val htmlUrl: String,
)

/** Parses GitHub's "latest release" JSON. Pure Kotlin so it can be unit tested. */
object ReleaseParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** CI tags releases "v1.<build>"; the trailing number is the versionCode. */
    fun versionCodeFromTag(tag: String): Int? = Regex("""(\d+)$""").find(tag.trim())?.value?.toIntOrNull()

    fun parse(body: String): ReleaseInfo? {
        val o = json.parseToJsonElement(body).jsonObject
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val code = versionCodeFromTag(tag) ?: return null
        val asset = o["assets"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true }
            ?: return null
        return ReleaseInfo(
            tag = tag,
            versionCode = code,
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: tag,
            notes = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            apkUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null,
            size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            htmlUrl = o["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }
}
