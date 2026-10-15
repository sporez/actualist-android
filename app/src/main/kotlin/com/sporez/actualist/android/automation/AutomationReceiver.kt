package com.sporez.actualist.android.automation

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.sporez.actualist.android.ActualistApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Entry point for Tasker, Samsung Routines and other automation apps. Senders must hold the
 * automation permission (declared in the manifest) and the user must have turned on
 * "Allow Automation"; otherwise the call is refused with Actualist's wording.
 *
 * LOG_TRANSACTION takes string extras amount, payee, account, category, notes, direction
 * (spend or inflow). GET_BALANCES takes an optional account. The outcome comes back as the
 * result code, data and extras of an ordered broadcast, and, when reply_package is set, as an
 * ACTION_RESULT broadcast to that package.
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Automation.ACTION_LOG_TRANSACTION && action != Automation.ACTION_GET_BALANCES) return
        val pending = goAsync()
        val app = context.applicationContext
        val ordered = isOrderedBroadcast
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (app as ActualistApplication).container
                val result = if (action == Automation.ACTION_LOG_TRANSACTION) {
                    Automation.logTransaction(app, container, requestFrom(intent))
                } else {
                    Automation.getBalances(app, container, text(intent, Automation.EXTRA_ACCOUNT))
                }
                val extras = Bundle().apply {
                    putBoolean(Automation.EXTRA_OK, result.ok)
                    putString(Automation.EXTRA_MESSAGE, result.message)
                    putStringArray(Automation.EXTRA_BALANCES, result.balances.map { (name, minor) -> "$name=$minor" }.toTypedArray())
                }
                if (ordered) {
                    pending.resultCode = if (result.ok) Activity.RESULT_OK else Activity.RESULT_CANCELED
                    pending.resultData = result.message
                    pending.setResultExtras(extras)
                }
                intent.getStringExtra(Automation.EXTRA_REPLY_PACKAGE)?.takeIf { it.isNotBlank() }?.let { target ->
                    app.sendBroadcast(Intent(Automation.ACTION_RESULT).setPackage(target).putExtras(extras))
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun requestFrom(intent: Intent) = LogRequest(
        amount = text(intent, Automation.EXTRA_AMOUNT),
        payee = text(intent, Automation.EXTRA_PAYEE),
        account = text(intent, Automation.EXTRA_ACCOUNT),
        category = text(intent, Automation.EXTRA_CATEGORY),
        notes = text(intent, Automation.EXTRA_NOTES),
        direction = text(intent, Automation.EXTRA_DIRECTION),
    )

    /** An extra as text, whatever type the sender used (Tasker often sends numbers). */
    @Suppress("DEPRECATION")
    private fun text(intent: Intent, key: String): String? = intent.extras?.get(key)?.toString()
}
