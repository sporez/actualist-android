package com.sporez.actualist.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sporez.actualist.android.ui.AppState
import com.sporez.actualist.android.ui.AppViewModel
import com.sporez.actualist.android.ui.MainScaffold
import com.sporez.actualist.android.ui.components.LoadingBox
import com.sporez.actualist.android.ui.components.LocalHideAmounts
import com.sporez.actualist.android.ui.components.MessageBox
import com.sporez.actualist.android.ui.onboarding.OnboardingScreen
import com.sporez.actualist.android.ui.onboarding.ServerScreen
import com.sporez.actualist.android.ui.theme.ActualistTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ActualistApp() }
        handleOpenIdReturn(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleOpenIdReturn(intent)
    }

    /** The OpenID redirect back from the browser: `<applicationId>://localhost/openid/<nonce>/openid-cb?token=`. */
    private fun handleOpenIdReturn(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != packageName || data.host != com.sporez.actualist.core.server.OpenIdLogin.CALLBACK_HOST) return
        (application as ActualistApplication).container.server.completeOpenId(data.toString(), packageName)
        intent.data = null
    }
}

@Composable
fun ActualistApp(app: AppViewModel = viewModel()) {
    val container = app.container
    val settings by container.settings.settings.collectAsState()
    val state by app.state.collectAsState()
    ActualistTheme(settings.themeMode) {
        CompositionLocalProvider(LocalHideAmounts provides settings.hideAmounts) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when (val current = state) {
                    AppState.Loading -> LoadingBox()
                    is AppState.Failed -> MessageBox("Could not open the budget", current.message, action = "Start over" to app::closeBudget)
                    AppState.Onboarding -> {
                        var connecting by rememberSaveable { mutableStateOf(false) }
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            if (connecting) {
                                ServerScreen(container.server, onDownloaded = { app.openBudget(it.id) }, onCancel = { connecting = false })
                            } else {
                                OnboardingScreen(
                                    localBudgets = container.library.list(),
                                    onDemo = app::openDemo,
                                    onOpenLocal = app::openBudget,
                                    onConnect = { connecting = true },
                                )
                            }
                        }
                    }
                    is AppState.Open -> MainScaffold(current.session, container, app)
                }
            }
        }
    }
}
