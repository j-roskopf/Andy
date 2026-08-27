package com.joetr.andy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.andy.AndyApp
import app.andy.service.PlatformCapabilities
import app.andy.service.createUnavailableAndyServices

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndyApp(services = createUnavailableAndyServices(capabilities = PlatformCapabilities.Web))
        }
    }
}
