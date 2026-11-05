package com.airvox.credits

import android.content.Context
import android.content.SharedPreferences

private const val LEGACY_PREFS = "airvox_credits"

/** Keeps each rider's balance separate, so a new account does not inherit the last one. */
fun bindUserCredits(context: Context, wallet: CreditWallet, userId: String) {
    val prefs = context.getSharedPreferences("airvox_credits_$userId", Context.MODE_PRIVATE)
    val balance = if (prefs.contains("credits")) read(prefs) else claimLegacy(context, userId) ?: CreditBalance()
    if (!prefs.contains("credits")) write(prefs, balance)
    wallet.bind(balance) { next -> write(prefs, next) }
}

fun offsetAlreadyRemoved(context: Context, userId: String, offset: Int): Boolean {
    val prefs = context.getSharedPreferences("airvox_credits_$userId", Context.MODE_PRIVATE)
    return prefs.getInt("offset_removed", 0) == offset
}

fun markOffsetRemoved(context: Context, userId: String, offset: Int) {
    context.getSharedPreferences("airvox_credits_$userId", Context.MODE_PRIVATE)
        .edit()
        .putInt("offset_removed", offset)
        .apply()
}

private fun claimLegacy(context: Context, userId: String): CreditBalance? {
    val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
    if (!legacy.contains("credits")) return null
    val owner = legacy.getString("owner", null)
    if (owner != null && owner != userId) return null
    legacy.edit().putString("owner", userId).apply()
    return read(legacy)
}

private fun read(prefs: SharedPreferences): CreditBalance = CreditBalance(
    credits = prefs.getInt("credits", 0),
    spent = prefs.getInt("spent", 0),
    minutesUsed = prefs.getInt("minutes", 0),
)

private fun write(prefs: SharedPreferences, balance: CreditBalance) {
    prefs.edit()
        .putInt("credits", balance.credits)
        .putInt("spent", balance.spent)
        .putInt("minutes", balance.minutesUsed)
        .apply()
}
