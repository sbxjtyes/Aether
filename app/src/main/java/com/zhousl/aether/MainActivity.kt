package com.zhousl.aether

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.zhousl.aether.ui.AetherApp
import com.zhousl.aether.ui.MarketAlertLaunchRequest

class MainActivity : ComponentActivity() {
    private var marketAlertRequest by mutableStateOf<MarketAlertLaunchRequest?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        marketAlertRequest = intent.toMarketAlertLaunchRequest()
        enableEdgeToEdge()
        setContent {
            AetherApp(
                onPrivacyPolicyAccepted = ::maybeRequestNotificationPermission,
                marketAlertRequest = marketAlertRequest,
                onMarketAlertRequestConsumed = { marketAlertRequest = null },
            )
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        marketAlertRequest = intent.toMarketAlertLaunchRequest()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun android.content.Intent?.toMarketAlertLaunchRequest(): MarketAlertLaunchRequest? {
        val symbol = this?.getStringExtra("market_alert_symbol").orEmpty()
        if (symbol.isBlank()) return null
        return MarketAlertLaunchRequest(
            symbol = symbol,
            ruleId = this?.getStringExtra("market_alert_rule_id").orEmpty(),
            autoAnalyze = this?.getBooleanExtra("market_alert_auto_analyze", false) == true,
        )
    }
}
