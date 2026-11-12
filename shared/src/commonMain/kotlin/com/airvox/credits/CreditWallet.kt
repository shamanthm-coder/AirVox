package com.airvox.credits

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlin.random.Random

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

/**
 * What this phone shows. The real balance is kept by the Airvox server.
 * The phone counts the minutes it uses, including offline, and the server takes them off the wallet.
 */
class CreditWallet(
    initial: CreditBalance = CreditBalance(),
    private var persist: (CreditBalance) -> Unit = {},
) {
    private val _state = mutableStateOf(initial)
    val state: State<CreditBalance> = _state

    /** A random id for this install's wallet. The server counts [CreditBalance.spent] against it. */
    var install: String = ""
        private set

    /** Loads the balance saved for the rider who just signed in. */
    fun bind(balance: CreditBalance, install: String, persist: (CreditBalance) -> Unit) {
        this.persist = persist
        this.install = install
        _state.value = balance
    }

    /**
     * Shows the server's balance. [counted] is the spent total the server had already taken off,
     * so minutes used since then stay off the number shown.
     */
    fun sync(serverBalance: Int, counted: Int) {
        val uncounted = (_state.value.spent - counted).coerceAtLeast(0)
        val credits = (serverBalance - uncounted).coerceAtLeast(0)
        if (credits != _state.value.credits) update { it.copy(credits = credits) }
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

/** A new id for [CreditWallet.install]. */
fun newWalletInstall(): String = List(4) { Random.nextInt().toUInt().toString(16).padStart(8, '0') }.joinToString("")
