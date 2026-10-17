package com.github.opscalehub.dong

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.github.opscalehub.dong.ui.DongRoot
import com.github.opscalehub.dong.ui.theme.DongTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DongTheme { DongRoot() } }
    }
}
