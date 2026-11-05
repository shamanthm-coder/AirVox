package com.airvox.credits

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

data class CreditBalance(
    val credits: Int = 0,
    val spent: Int = 0,
    val minutesUsed: Int = 0,
)

const val CREDIT_PER_MINUTE = 1

/** A credit pack published from the admin portal. */
data class CreditPlan(
    val id: Long,
    val name: String,
    val credits: Int,
    val rupees: Int,
)

class CreditWallet(
    initial: CreditBalance = CreditBalance(),
    private var persist: (CreditBalance) -> Unit = {},
) {
    private val _state = mutableStateOf(initial)
    val state: State<CreditBalance> = _state

    /** Loads the balance saved for the rider who just signed in. */
    fun bind(balance: CreditBalance, persist: (CreditBalance) -> Unit) {
        this.persist = persist
        _state.value = balance
    }

    fun add(amount: Int) {
        if (amount <= 0) return
        update { it.copy(credits = it.credits + amount) }
    }

    /** Removes credits that were added by mistake, such as a second welcome grant. */
    fun remove(amount: Int) {
        if (amount <= 0) return
        update { it.copy(credits = (it.credits - amount).coerceAtLeast(0)) }
    }

    /** One minute on a channel costs [CREDIT_PER_MINUTE]. Returns false when the balance is empty. */
    fun spendMinute(): Boolean {
        if (_state.value.credits < CREDIT_PER_MINUTE) return false
        update {
            it.copy(
                credits = it.credits - CREDIT_PER_MINUTE,
                spent = it.spent + CREDIT_PER_MINUTE,
                minutesUsed = it.minutesUsed + 1,
            )
        }
        return true
    }

    private fun update(block: (CreditBalance) -> CreditBalance) {
        val next = block(_state.value)
        _state.value = next
        persist(next)
    }
}
