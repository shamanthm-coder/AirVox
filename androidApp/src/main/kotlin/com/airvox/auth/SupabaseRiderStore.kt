package com.airvox.auth

import com.google.firebase.auth.FirebaseUser
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Saves the signed-in Firebase rider into Supabase `app_users`.
 * A later sign-in refreshes email and last seen, and keeps a name or phone
 * the rider already saved.
 */
class SupabaseRiderStore(
    baseUrl: String,
    private val apiKey: String,
    private val http: OkHttpClient,
) {
    private val root = baseUrl.trim().trimEnd('/')
    private val jsonType = "application/json".toMediaType()
    val enabled: Boolean = root.isNotBlank() && apiKey.isNotBlank()

    fun upsert(user: FirebaseUser, rider: RiderAccount, profileSaved: Boolean) {
        if (!enabled) return
        val body = JSONObject()
            .put("p_firebase_uid", rider.id)
            .put("p_provider", rider.signInProvider)
            .put("p_profile_saved", profileSaved)
        body.putNullable("p_email", rider.email ?: user.email)
        body.putNullable("p_name", rider.displayName ?: user.displayName)
        body.putNullable("p_phone", rider.phoneNumber)
        send(
            Request.Builder()
                .url("$root/rest/v1/rpc/upsert_firebase_user")
                .post(body.toString().toRequestBody(jsonType)),
        )
    }

    private fun send(builder: Request.Builder): String {
        val request = builder
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Prefer", "return=minimal")
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) return body
                throw SupabaseRejected(messageFor(response.code, body))
            }
        } catch (error: SupabaseRejected) {
            throw error
        } catch (error: IOException) {
            throw error
        }
    }

    private fun messageFor(code: Int, body: String): String {
        val raw = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
        val detail = runCatching { JSONObject(body).optString("code") }.getOrDefault("")
        return when {
            detail == "PGRST202" ->
                "Supabase is missing the sign-in save. Sign in again after the Airvox database is updated."
            code == 401 || code == 403 || detail == "42501" ->
                "Supabase blocked saving this account."
            raw.isNotBlank() -> raw
            else -> "Supabase couldn't save this account ($code)."
        }
    }

    private fun JSONObject.putNullable(key: String, value: String?) {
        if (value.isNullOrBlank()) put(key, JSONObject.NULL) else put(key, value)
    }
}

class SupabaseRejected(message: String) : Exception(message)
