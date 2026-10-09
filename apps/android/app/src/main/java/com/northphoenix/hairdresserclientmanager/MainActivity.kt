package com.northphoenix.hairdresserclientmanager

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.northphoenix.hairdresserclientmanager.ui.HcmApp

// AppCompatActivity is what applies the per-app language on Android versions before 13.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light-only app: keep dark system bar icons on the paper background.
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = SystemBarStyle.light(transparent, transparent),
        )
        super.onCreate(savedInstanceState)

        setContent { HcmApp((application as HcmApplication).container) }
    }
}
