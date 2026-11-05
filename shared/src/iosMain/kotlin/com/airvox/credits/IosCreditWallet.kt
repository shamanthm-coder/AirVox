package com.airvox.credits

import platform.Foundation.NSUserDefaults

/** Keeps each rider's balance on this iPhone, so a new build does not open an empty wallet. */
fun bindUserCredits(wallet: CreditWallet, userId: String) {
    val store = NSUserDefaults.standardUserDefaults
    val key = "airvox.credits.$userId"
    val balance = read(store.stringForKey(key))
    wallet.bind(balance) { next ->
        store.setObject("${next.credits},${next.spent},${next.minutesUsed}", key)
    }
}

fun offsetAlreadyRemoved(userId: String, offset: Int): Boolean {
    val stored = NSUserDefaults.standardUserDefaults.stringForKey(offsetKey(userId))
    return stored == offset.toString()
}

fun markOffsetRemoved(userId: String, offset: Int) {
    NSUserDefaults.standardUserDefaults.setObject(offset.toString(), offsetKey(userId))
}

private fun offsetKey(userId: String) = "airvox.offset.$userId"

private fun read(raw: String?): CreditBalance {
    val parts = raw?.split(',') ?: return CreditBalance()
    if (parts.size != 3) return CreditBalance()
    val credits = parts[0].toIntOrNull() ?: return CreditBalance()
    val spent = parts[1].toIntOrNull() ?: return CreditBalance()
    val minutes = parts[2].toIntOrNull() ?: return CreditBalance()
    return CreditBalance(credits = credits, spent = spent, minutesUsed = minutes)
}
