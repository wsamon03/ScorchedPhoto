package com.scorchedphoto.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.scorchedphoto.app.navigation.ScorchedNavGraph
import com.scorchedphoto.app.ui.theme.ScorchedPhotoTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScorchedPhotoTheme {
                ScorchedNavGraph()
            }
        }
    }
}
