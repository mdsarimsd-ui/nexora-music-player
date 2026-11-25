package com.lastwave.app.data.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
 * Thin client for Google's Gemini `generateContent` endpoint, called with the
 * user's own API key. The key travels only inside requests to Google's
 * endpoint itself; no other server ever sees it.
 */
@Singleton
class GeminiClient @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun generate(
        apiKey: String,
        model: String,
        systemInstruction: String,
        prompt: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No AI API key set"))
        }
        try {
            val payload = buildJsonObject {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", systemInstruction) })
                    })
                })
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", prompt) })
                        })
                    })
                })
                put("generationConfig", buildJsonObject {
                    put("temperature", 0.8)
                    put("maxOutputTokens", 2048)
                })
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(payload)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use Result.failure(
                        IllegalStateException("Gemini error ${response.code}: ${bodyText.take(300)}"),
                    )
                }
                val root = json.parseToJsonElement(bodyText).jsonObject
                val answer = root["candidates"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray
                    ?.mapNotNull { part ->
                        (part.jsonObject["text"] as? JsonPrimitive)?.contentOrNull
                    }
                    ?.joinToString("")
                    .orEmpty()
                if (answer.isBlank()) {
                    Result.failure(IllegalStateException("Gemini returned an empty answer"))
                } else {
                    Result.success(answer)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Gemini request failed", error)
            Result.failure(error)
        }
    }

    private companion object {
        const val TAG = "GeminiClient"
    }
}
