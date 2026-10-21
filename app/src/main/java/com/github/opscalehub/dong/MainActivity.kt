package com.github.opscalehub.dong

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.opscalehub.dong.ui.AppViewModel
import com.github.opscalehub.dong.ui.DongRoot
import com.github.opscalehub.dong.ui.theme.DongTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: AppViewModel = viewModel()
            val scale by vm.ui.textScale.collectAsState()
            DongTheme(textScale = scale) { DongRoot(vm) }
        }
    }
}
