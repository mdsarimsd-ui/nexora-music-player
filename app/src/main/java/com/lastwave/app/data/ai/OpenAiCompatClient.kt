package com.lastwave.app.data.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin client for every OpenAI-compatible chat endpoint: OpenAI, Groq and
 * OpenRouter share the same `/chat/completions` shape, so one client with a
 * per-provider base URL covers them. The key travels only to that provider.
 */
@Singleton
class OpenAiCompatClient @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun generate(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemInstruction: String,
        prompt: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = buildJsonObject {
                put("model", model)
                put("max_tokens", 2048)
                put("temperature", 0.8)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", systemInstruction)
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use Result.failure(
                        IllegalStateException("AI error ${response.code}: ${bodyText.take(300)}"),
                    )
                }
                val root = json.parseToJsonElement(bodyText).jsonObject
                val answer = root["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.content
                    .orEmpty()
                if (answer.isBlank()) {
                    Result.failure(IllegalStateException("The provider returned an empty answer"))
                } else {
                    Result.success(answer)
                }
            }
        } catch (error: Exception) {
            Log.w("AiOpenAiCompat", "request failed", error)
            Result.failure(error)
        }
    }
}
