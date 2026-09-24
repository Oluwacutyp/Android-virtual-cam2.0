package com.vcamstudio.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import com.vcamstudio.app.ui.onboarding.PermissionsGate
import com.vcamstudio.app.ui.studio.StudioScreen
import androidx.lifecycle.ViewModelProvider
import com.vcamstudio.app.ui.studio.StudioViewModel
import com.vcamstudio.app.ui.theme.StudioTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Given to the ViewModel so CameraSource can bind to this lifecycle. */
    private var lifecycleBridge: LifecycleOwner? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Manifest handles orientation configChanges in-place (portrait
        // locked, no recreate) — the ONLY hook where Display.getRotation()
        // can change under this app. Re-run compensation per round-25.
        // Same ViewModelStore owner as the Compose UI -> same instance.
        ViewModelProvider(this)[StudioViewModel::class.java].onDisplayRotationMaybeChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Studio screen: keep the compositor visible and hot while foregrounded.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleBridge = this
        setContent {
            StudioTheme {
                PermissionsGate {
                    StudioScreen(lifecycleOwner = this)
                }
            }
        }
    }
}
