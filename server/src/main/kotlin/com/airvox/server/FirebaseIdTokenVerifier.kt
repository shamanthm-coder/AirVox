package com.airvox.server

import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class FirebaseIdentity(
    val uid: String,
    val phoneNumber: String?,
    val email: String?,
    val name: String?,
    val signInProvider: String,
    val providers: List<String>,
)

interface IdTokenVerifier {
    fun verify(token: String): FirebaseIdentity
}

class TokenRejected(message: String) : Exception(message)

class TokenCheckUnavailable(message: String) : Exception(message)

/**
 * Checks a Firebase ID token with Google's published certificates.
 * Verification does not need a service account. The private key stays at Google.
 */
class FirebaseIdTokenVerifier(
    private val projectId: String,
    private val keys: SigningKeys,
    private val clock: () -> Instant = Instant::now,
    private val skewSeconds: Long = 60,
) : IdTokenVerifier {
    private val json = Json { ignoreUnknownKeys = true }

    override fun verify(token: String): FirebaseIdentity {
        val parts = token.split('.')
        if (parts.size != 3) throw TokenRejected("Firebase ID token is not valid.")
        val header = parseObject(parts[0])
        if (header.text("alg") != "RS256") throw TokenRejected("Firebase ID token is not valid.")
        val kid = header.text("kid") ?: throw TokenRejected("Firebase ID token is not valid.")
        val key = keys.key(kid) ?: throw TokenRejected("Firebase ID token is not valid.")
        if (!signatureMatches(parts[0], parts[1], parts[2], key)) {
            throw TokenRejected("Firebase ID token is not valid.")
        }
        val payload = parseObject(parts[1])
        val now = clock().epochSecond
        val exp = payload.long("exp") ?: throw TokenRejected("Firebase ID token is not valid.")
        val iat = payload.long("iat") ?: throw TokenRejected("Firebase ID token is not valid.")
        if (payload.long("auth_time") == null) throw TokenRejected("Firebase ID token is not valid.")
        if (exp + skewSeconds < now) throw TokenRejected("Firebase ID token is expired. Sign in again.")
        if (iat > now + skewSeconds) throw TokenRejected("Firebase ID token is not valid.")
        val audiences = when (val aud = payload["aud"]) {
            is JsonArray -> aud.mapNotNull { element ->
                (element as? JsonPrimitive)?.content
            }
            is JsonPrimitive -> listOf(aud.content)
            else -> emptyList()
        }
        if (projectId !in audiences) throw TokenRejected("Firebase ID token is from a different app.")
        val issuer = "https://securetoken.google.com/$projectId"
        if (payload.text("iss") != issuer) throw TokenRejected("Firebase ID token is from a different project.")
        val uid = payload.text("sub")?.takeIf { it.isNotBlank() }
            ?: throw TokenRejected("Firebase ID token has no user.")
        val firebase = payload["firebase"] as? JsonObject
            ?: throw TokenRejected("Firebase ID token is not valid.")
        val provider = firebase.text("sign_in_provider")
            ?: throw TokenRejected("Firebase ID token is not valid.")
        if (provider !in AllowedProviders) {
            throw TokenRejected("Use your mobile number, Google, or email to sign in.")
        }
        val identities = firebase["identities"] as? JsonObject
        val providers = identities?.keys?.filter { it in AllowedProviders }.orEmpty().ifEmpty { listOf(provider) }
        return FirebaseIdentity(
            uid = uid,
            phoneNumber = payload.text("phone_number"),
            email = payload.text("email"),
            name = payload.text("name"),
            signInProvider = provider,
            providers = providers,
        )
    }

    private fun parseObject(part: String): JsonObject {
        val text = try {
            String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            throw TokenRejected("Firebase ID token is not valid.")
        }
        return try {
            json.parseToJsonElement(text).jsonObject
        } catch (error: IllegalArgumentException) {
            throw TokenRejected("Firebase ID token is not valid.")
        }
    }

    private fun signatureMatches(header: String, payload: String, signature: String, key: PublicKey): Boolean {
        val signed = try {
            Base64.getUrlDecoder().decode(signature)
        } catch (error: IllegalArgumentException) {
            throw TokenRejected("Firebase ID token is not valid.")
        }
        return try {
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(key)
            verifier.update("$header.$payload".toByteArray(Charsets.UTF_8))
            verifier.verify(signed)
        } catch (error: Exception) {
            throw TokenRejected("Firebase ID token is not valid.")
        }
    }

    private companion object {
        val AllowedProviders = setOf("phone", "google.com", "password")
    }
}

fun interface SigningKeys {
    fun key(kid: String): PublicKey?
}

class GoogleCertSigningKeys(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val clock: () -> Instant = Instant::now,
) : SigningKeys {
    private val lock = Any()
    private val refreshLock = Any()
    private var keys: Map<String, PublicKey> = emptyMap()
    private var expiresAt: Instant = Instant.EPOCH

    override fun key(kid: String): PublicKey? {
        synchronized(lock) {
            if (clock().isBefore(expiresAt) && keys.containsKey(kid)) return keys[kid]
        }
        try {
            refresh()
        } catch (error: TokenCheckUnavailable) {
            synchronized(lock) {
                if (keys.isNotEmpty() && clock().isBefore(expiresAt)) return keys[kid]
            }
            throw error
        }
        synchronized(lock) {
            return keys[kid]
        }
    }

    private fun refresh() {
        synchronized(refreshLock) {
            val fetched = fetch()
            synchronized(lock) {
                keys = fetched.keys
                expiresAt = fetched.expiresAt
            }
        }
    }

    private fun fetch(): CachedKeys {
        val request = HttpRequest.newBuilder(Certificates)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw TokenCheckUnavailable("Could not reach Google to check the sign-in.")
        }
        if (response.statusCode() != HttpsURLConnection.HTTP_OK) {
            throw TokenCheckUnavailable("Could not reach Google to check the sign-in.")
        }
        val parsed = parseCertificates(response.body())
        if (parsed.isEmpty()) throw TokenCheckUnavailable("Google did not return signing keys.")
        val maxAge = Regex("""max-age=(\d+)""")
            .find(response.headers().firstValue("cache-control").orElse(""))
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?: 3600L
        return CachedKeys(parsed, clock().plusSeconds(maxAge.coerceIn(60, 86_400)))
    }

    private data class CachedKeys(val keys: Map<String, PublicKey>, val expiresAt: Instant)

    private companion object {
        val Certificates: URI = URI.create(
            "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com",
        )
    }
}

internal fun parseCertificates(body: String): Map<String, PublicKey> {
    val parsed = try {
        Json.parseToJsonElement(body).jsonObject
    } catch (error: IllegalArgumentException) {
        throw TokenCheckUnavailable("Google did not return signing keys.")
    }
    val factory = CertificateFactory.getInstance("X.509")
    val keys = linkedMapOf<String, PublicKey>()
    for ((kid, value) in parsed) {
        val pem = (value as? JsonPrimitive)?.content ?: continue
        val certificate = try {
            factory.generateCertificate(pem.byteInputStream(Charsets.US_ASCII)) as X509Certificate
        } catch (error: Exception) {
            continue
        }
        keys[kid] = certificate.publicKey
    }
    return keys
}

private fun JsonObject.text(name: String): String? {
    val value = this[name] ?: return null
    if (value is JsonNull || value !is JsonPrimitive || !value.isString) return null
    return value.content.ifBlank { null }
}

private fun JsonObject.long(name: String): Long? {
    val value = this[name] as? JsonPrimitive ?: return null
    return value.content.toLongOrNull()
}
