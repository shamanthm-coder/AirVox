package com.airvox.voxy

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

data class VoxyState(
    val listening: Boolean = false,
    val line: String = "Say Voxy.",
    /** True after the rider allows Voxy to keep listening while the phone is locked. */
    val background: Boolean = false,
)

/**
 * Voxy is the in-app voice assistant.
 * Saying "Voxy" gets a spoken hello that uses the rider's name.
 */
interface Voxy {
    val state: State<VoxyState>

    /** Android can ask to keep the microphone on while the phone is locked. */
    val canBackground: Boolean get() = false

    fun start(displayName: String)

    fun stop()

    /**
     * When [enabled] is true, ask the rider and then listen while the phone is locked.
     * A notification stays visible the whole time the microphone is on.
     */
    fun setBackground(enabled: Boolean) = Unit

    fun dispose()
}

class UnavailableVoxy : Voxy {
    override val state: State<VoxyState> = mutableStateOf(
        VoxyState(line = "Voxy is available on Android."),
    )

    override fun start(displayName: String) = Unit

    override fun stop() = Unit

    override fun dispose() = Unit
}

private val exactWake = setOf("voxy", "voxie", "voxi", "voxxy", "heyvoxy")

/**
 * True when the transcript is "Voxy", or a short phrase the recognizer heard
 * as a close sound, such as "boxing", "boxy", or "foxy".
 */
fun heardVoxy(transcript: String): Boolean {
    val words = transcript.lowercase()
        .replace(Regex("[^a-z\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (words.isEmpty()) return false
    val tokens = words.split(' ')
    if (tokens.any { it in exactWake }) return true
    if (tokens.size > 5) return false
    return tokens.any(::soundsLikeVoxy) || soundsLikeVoxy(tokens.joinToString(""))
}

/** Folds labial consonants and the trailing vowel so "boxing" becomes "voxy". */
private fun soundsLikeVoxy(token: String): Boolean {
    if (token.length < 4) return false
    var word = token.replace("cks", "x").replace("ks", "x")
    word = word.replace(Regex("o{2,}"), "o")
        .replace(Regex("x{2,}"), "x")
        .replace(Regex("y{2,}"), "y")
    word = when {
        word.endsWith("ing") && word.length >= 6 -> word.dropLast(3) + "y"
        word.endsWith("in") && word.dropLast(2).endsWith("x") -> word.dropLast(2) + "y"
        word.endsWith("ies") -> word.dropLast(3) + "y"
        word.endsWith("ie") || word.endsWith("ee") || word.endsWith("ey") -> word.dropLast(2) + "y"
        word.endsWith("es") && word.dropLast(2).endsWith("x") -> word.dropLast(2) + "y"
        word.endsWith("ers") && word.dropLast(3).endsWith("x") -> word.dropLast(3) + "y"
        word.endsWith("er") && word.dropLast(2).endsWith("x") -> word.dropLast(2) + "y"
        word.endsWith("i") -> word.dropLast(1) + "y"
        else -> word
    }
    if (word.isEmpty()) return false
    if (word[0] in "bfpvw") word = "v" + word.substring(1)
    return word == "voxy"
}

/** Spoken reply: "Hi" plus the name the rider saved on their profile. */
fun voxyGreeting(displayName: String): String {
    val name = displayName.trim().replace(Regex("\\s+"), " ")
    return if (name.isEmpty()) "Hi there" else "Hi $name"
}
