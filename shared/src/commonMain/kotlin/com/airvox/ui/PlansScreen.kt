package com.airvox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airvox.auth.RiderAccount
import com.airvox.credits.CREDIT_PER_MINUTE
import com.airvox.credits.CreditBalance
import com.airvox.credits.CreditPlan
import com.airvox.theme.AirvoxPalette

@Composable
fun PlansScreen(
    account: RiderAccount?,
    balance: CreditBalance,
    plans: List<CreditPlan>,
    onRefresh: () -> Unit,
    onBuy: (Int) -> Unit,
) {
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { onRefresh() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 16.dp),
    ) {
        SectionLabel("Plans")
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Credits",
            style = MaterialTheme.typography.headlineLarge,
            fontSize = 36.sp,
        )
        account?.displayName?.takeIf { it.isNotBlank() }?.let { name ->
            Spacer(Modifier.height(6.dp))
            Text(text = name, color = AirvoxPalette.Muted, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Credits are the currency in Airvox. They are points, not a cash balance.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(22.dp))
        AirvoxCard {
            Text("Wallet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = balance.credits.toString(),
                fontSize = 42.sp,
                fontWeight = FontWeight.SemiBold,
                color = AirvoxPalette.White,
            )
            Text(
                text = "credits left",
                color = AirvoxPalette.Dim,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Joining a channel uses $CREDIT_PER_MINUTE credit, then $CREDIT_PER_MINUTE more each minute. A short stay still costs $CREDIT_PER_MINUTE credit.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
        AirvoxCard {
            Text("Usage", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            UsageRow("Minutes on channel", balance.minutesUsed.toString())
            Spacer(Modifier.height(8.dp))
            UsageRow("Credits used", balance.spent.toString())
            Spacer(Modifier.height(8.dp))
            UsageRow("Credits left", balance.credits.toString())
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Add credits", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        Spacer(Modifier.height(10.dp))
        if (plans.isEmpty()) {
            Text(
                text = "No plans yet. Add them in the Airvox portal.",
                color = AirvoxPalette.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            plans.forEach { plan ->
                Button(
                    onClick = {
                        onBuy(plan.credits)
                        note = "Added ${plan.credits} credits for ₹${plan.rupees}."
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AirvoxPalette.White,
                        contentColor = AirvoxPalette.Black,
                    ),
                ) {
                    Text("${plan.credits} credits · ₹${plan.rupees}", fontWeight = FontWeight.Medium)
                }
            }
        }
        note?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(text = message, color = AirvoxPalette.Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, color = AirvoxPalette.White, fontWeight = FontWeight.Medium)
    }
}
