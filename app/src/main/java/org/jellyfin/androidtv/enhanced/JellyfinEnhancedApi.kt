package org.jellyfin.androidtv.enhanced

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import java.io.IOException

/**
 * Error returned by the Jellyfin Enhanced plugin (or a service it proxies, like Seerr).
 * [message] is the server provided message when available.
 */
class JellyfinEnhancedException(
	val status: Int,
	message: String?,
	val code: String? = null,
	cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Small HTTP client for the Jellyfin Enhanced server plugin (`/JellyfinEnhanced/...`).
 *
 * It reuses the app's OkHttp connection pool and the session of the shared [ApiClient], so no extra login is
 * needed. All calls run on [Dispatchers.IO] and never touch the main thread.
 */
class JellyfinEnhancedApi(
	private val api: ApiClient,
	okHttpFactory: OkHttpFactory,
	httpClientOptions: HttpClientOptions,
) {
	companion object {
		private const val ROOT = "JellyfinEnhanced"
		private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

		val json = Json {
			ignoreUnknownKeys = true
			isLenient = true
		}
	}

	private val client = okHttpFactory.createClient(httpClientOptions)

	/** Identifies the current server session, used to invalidate caches when the user or server changes. */
	val sessionKey: String
		get() = "${api.baseUrl}|${api.accessToken}"

	val hasSession: Boolean
		get() = !api.baseUrl.isNullOrBlank() && !api.accessToken.isNullOrBlank()

	private fun authorizationHeader() = AuthorizationHeaderBuilder.buildHeader(
		clientName = api.clientInfo.name,
		clientVersion = api.clientInfo.version,
		deviceId = api.deviceInfo.id,
		deviceName = api.deviceInfo.name,
		accessToken = api.accessToken,
	)

	/**
	 * Perform a request against the plugin. [path] segments are encoded individually so values like `603:s1:e2`
	 * are safe to pass. Returns the raw body as text.
	 */
	suspend fun requestText(
		method: String,
		path: List<String>,
		query: Map<String, Any?> = emptyMap(),
		body: JsonElement? = null,
	): String = withContext(Dispatchers.IO) {
		val baseUrl = api.baseUrl?.toHttpUrlOrNull()
			?: throw JellyfinEnhancedException(0, "No server connected")

		val url = baseUrl.newBuilder().apply {
			addPathSegment(ROOT)
			path.forEach { segment -> addPathSegment(segment) }
			query.forEach { (key, value) -> if (value != null) addQueryParameter(key, value.toString()) }
		}.build()

		val requestBody = when {
			body != null -> body.toString().toRequestBody(JSON_MEDIA_TYPE)
			method == "POST" -> "".toRequestBody(JSON_MEDIA_TYPE)
			else -> null
		}

		val request = Request.Builder()
			.url(url)
			.method(method, requestBody)
			.header("Authorization", authorizationHeader())
			.header("Accept", "application/json, text/plain;q=0.9, */*;q=0.8")
			.build()

		try {
			client.newCall(request).execute().use { response ->
				val text = response.body?.string().orEmpty()
				if (!response.isSuccessful) {
					val error = parseOrNull(text) as? JsonObject
					throw JellyfinEnhancedException(
						status = response.code,
						message = error?.string("message") ?: error?.string("error")?.takeIf { it != "true" },
						code = error?.string("code"),
					)
				}
				text
			}
		} catch (err: IOException) {
			throw JellyfinEnhancedException(0, err.message, cause = err)
		}
	}

	suspend fun request(
		method: String,
		path: List<String>,
		query: Map<String, Any?> = emptyMap(),
		body: JsonElement? = null,
	): JsonElement = parseOrNull(requestText(method, path, query, body)) ?: JsonNull

	suspend fun get(vararg path: String, query: Map<String, Any?> = emptyMap()) =
		request("GET", path.toList(), query)

	suspend fun post(vararg path: String, body: JsonElement? = null) =
		request("POST", path.toList(), body = body)

	suspend fun delete(vararg path: String) =
		request("DELETE", path.toList())

	private fun parseOrNull(text: String): JsonElement? =
		if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()
}

// JSON helpers, tolerant to both PascalCase and camelCase property names

private fun JsonObject.lookup(key: String): JsonElement? =
	this[key]
		?: this[key.replaceFirstChar { it.lowercaseChar() }]
		?: this[key.replaceFirstChar { it.uppercaseChar() }]

fun JsonObject.obj(key: String): JsonObject? = lookup(key) as? JsonObject
fun JsonObject.array(key: String): List<JsonElement> = (lookup(key) as? kotlinx.serialization.json.JsonArray).orEmpty()
fun JsonObject.string(key: String): String? = (lookup(key) as? JsonPrimitive)?.contentOrNull
fun JsonObject.int(key: String): Int? = string(key)?.toDoubleOrNull()?.toInt()
fun JsonObject.double(key: String): Double? = string(key)?.toDoubleOrNull()
fun JsonObject.bool(key: String): Boolean? = when (string(key)?.lowercase()) {
	"true" -> true
	"false" -> false
	else -> null
}
