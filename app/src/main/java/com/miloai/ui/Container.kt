package com.miloai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.miloai.AppContainer
import com.miloai.MiloAiApp

@Composable
fun rememberContainer(): AppContainer {
    val context = LocalContext.current
    return remember { (context.applicationContext as MiloAiApp).container }
}
