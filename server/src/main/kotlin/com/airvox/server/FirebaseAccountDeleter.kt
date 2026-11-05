package com.airvox.server

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Deletes the phone sign-in so that email can be used for a new account. */
fun interface AccountDeleter {
    /** True when that email is free to register again. */
    fun delete(firebaseUid: String): Boolean
}

/**
 * Deletes a Firebase Authentication user.
 * Uses a service account when [serviceAccountJson] is set. Otherwise, asks
 * Google with the project's web API key, which only works for some projects.
 */
class FirebaseAccountDeleter(
    private val projectId: String,
    private val apiKey: String?,
    private val serviceAccountJson: String?,
) : AccountDeleter {
    private val http = HttpClient.newBuilder().build()

    override fun delete(firebaseUid: String): Boolean {
        if (firebaseUid.isBlank()) return false
        val account = serviceAccountJson?.takeIf { it.isNotBlank() }
        if (account != null && adminDelete(account, firebaseUid)) return true
        val key = apiKey?.takeIf { it.isNotBlank() } ?: return false
        return keyDelete(key, firebaseUid)
    }

    private fun adminDelete(accountJson: String, firebaseUid: String): Boolean {
        val token = accessToken(accountJson) ?: return false
        val body = buildJsonObject { put("localId", firebaseUid) }.toString()
        val request = HttpRequest.newBuilder(
            URI.create("https://identitytoolkit.googleapis.com/v1/projects/$projectId/accounts:delete"),
        )
            .timeout(java.time.Duration.ofSeconds(15))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return freed(send(request))
    }

    private fun keyDelete(key: String, firebaseUid: String): Boolean {
        val body = buildJsonObject { put("localId", firebaseUid) }.toString()
        val request = HttpRequest.newBuilder(
            URI.create("https://identitytoolkit.googleapis.com/v1/projects/$projectId/accounts:delete?key=$key"),
        )
            .timeout(java.time.Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return freed(send(request))
    }

    /** True on success, and when Google says that sign-in is already gone. */
    private fun freed(response: HttpResponse<String>?): Boolean {
        if (response == null) return false
        if (response.statusCode() in 200..299) return true
        return response.body().contains("USER_NOT_FOUND")
    }

    private fun send(request: HttpRequest): HttpResponse<String>? {
        return try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: Exception) {
            null
        }
    }

    private fun accessToken(accountJson: String): String? {
        val account = runCatching { Json.parseToJsonElement(accountJson).jsonObject }.getOrNull() ?: return null
        val email = account["client_email"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val pem = account["private_key"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val now = Instant.now().epochSecond
        val claims = buildJsonObject {
            put("iss", email)
            put("scope", "https://www.googleapis.com/auth/identitytoolkit https://www.googleapis.com/auth/cloud-platform")
            put("aud", "https://oauth2.googleapis.com/token")
            put("iat", now)
            put("exp", now + 3600)
        }.toString()
        val jwt = sign(claims, pem) ?: return null
        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", Charsets.UTF_8) +
            "&assertion=" + URLEncoder.encode(jwt, Charsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
            .timeout(java.time.Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = send(request) ?: return null
        if (response.statusCode() !in 200..299) return null
        return runCatching {
            Json.parseToJsonElement(response.body()).jsonObject["access_token"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun sign(claims: String, pem: String): String? {
        val keyBytes = runCatching {
            val cleaned = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "")
                .replace("\n", "")
                .trim()
            Base64.getDecoder().decode(cleaned)
        }.getOrNull() ?: return null
        val key = runCatching {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        }.getOrNull() ?: return null
        val header = base64("""{"alg":"RS256","typ":"JWT"}""")
        val body = base64(claims)
        val input = "$header.$body"
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(key)
        signature.update(input.toByteArray(Charsets.UTF_8))
        return "$input.${base64(signature.sign())}"
    }

    private fun base64(text: String): String = base64(text.toByteArray(Charsets.UTF_8))

    private fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
