package com.speedtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.speedtracker.data.UserRepository
import com.speedtracker.presentation.athletes.AthletesScreen
import com.speedtracker.presentation.camera.CameraScreen
import com.speedtracker.presentation.camera.CameraTutorialScreen
import com.speedtracker.presentation.dashboard.DashboardScreen
import com.speedtracker.presentation.history.HistoryScreen
import com.speedtracker.presentation.splash.SplashScreen
import com.speedtracker.presentation.splash.UserSelectionScreen
import com.speedtracker.presentation.video.VideoAnalysisScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SpeedTrackerApp()
        }
    }
}

@Composable
fun SpeedTrackerApp() {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val context = LocalContext.current
            val userRepository = remember { UserRepository(context) }

            var isSplashFinished by remember { mutableStateOf(false) }
            var currentTab by remember { mutableStateOf("dashboard") } // "dashboard", "athletes", "history"
            var activeOverlay by remember { mutableStateOf<String?>(null) } // "camera", "quick_camera", "camera_tutorial", "video_analysis", "user_select"
            var selectedVideoUri by remember { mutableStateOf<String?>(null) }

            if (!isSplashFinished) {
                SplashScreen(
                    onTimeout = {
                        isSplashFinished = true
                        if (userRepository.getActiveUser() == null) {
                            activeOverlay = "user_select"
                        }
                    }
                )
            } else if (activeOverlay != null) {
                // Vistas en Pantalla Completa (Cámara, Tutorial, Análisis Video)
                when (activeOverlay) {
                    "user_select" -> {
                        UserSelectionScreen(
                            userRepository = userRepository,
                            onUserSelected = { activeOverlay = null }
                        )
                    }
                    "camera_tutorial" -> {
                        CameraTutorialScreen(
                            onContinue = { activeOverlay = "camera" },
                            onSkipForever = {
                                userRepository.setTutorialSeen()
                                activeOverlay = "camera"
                            }
                        )
                    }
                    "camera" -> {
                        CameraScreen(
                            userRepository = userRepository,
                            isQuickMode = false,
                            onCloseClick = { activeOverlay = null },
                            onNavigateToVideoAnalysis = { uri ->
                                selectedVideoUri = uri
                                activeOverlay = "video_analysis"
                            }
                        )
                    }
                    "quick_camera" -> {
                        // ⚡ MODO RÁPIDO: Medición Express en Vivo sin registro
                        CameraScreen(
                            userRepository = userRepository,
                            isQuickMode = true,
                            onCloseClick = { activeOverlay = null },
                            onNavigateToVideoAnalysis = { uri ->
                                selectedVideoUri = uri
                                activeOverlay = "video_analysis"
                            }
                        )
                    }
                    "video_analysis" -> {
                        if (selectedVideoUri != null) {
                            VideoAnalysisScreen(
                                videoUriString = selectedVideoUri!!,
                                userRepository = userRepository,
                                onCloseClick = { activeOverlay = null }
                            )
                        } else {
                            activeOverlay = null
                        }
                    }
                }
            } else {
                // Navegación Principal con Bottom Navigation Bar
                Scaffold(
                    bottomBar = {
                        NavigationBar(
                            containerColor = Color(0xFF121212),
                            contentColor = Color.White
                        ) {
                            NavigationBarItem(
                                selected = currentTab == "dashboard",
                                onClick = { currentTab = "dashboard" },
                                icon = { Icon(Icons.Default.Home, contentDescription = "Panel") },
                                label = { Text("Panel") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.Black,
                                    selectedTextColor = Color(0xFF00FFCC),
                                    indicatorColor = Color(0xFF00FFCC),
                                    unselectedIconColor = Color.Gray,
                                    unselectedTextColor = Color.Gray
                                )
                            )
                            NavigationBarItem(
                                selected = false,
                                onClick = { activeOverlay = "quick_camera" },
                                icon = { Icon(Icons.Default.Bolt, contentDescription = "Rápido") },
                                label = { Text("Rápido") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color(0xFFFF9800),
                                    selectedTextColor = Color(0xFFFF9800),
                                    unselectedIconColor = Color(0xFFFF9800),
                                    unselectedTextColor = Color(0xFFFF9800)
                                )
                            )
                            NavigationBarItem(
                                selected = currentTab == "athletes",
                                onClick = { currentTab = "athletes" },
                                icon = { Icon(Icons.Default.People, contentDescription = "Atletas") },
                                label = { Text("Atletas") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.Black,
                                    selectedTextColor = Color(0xFF00FFCC),
                                    indicatorColor = Color(0xFF00FFCC),
                                    unselectedIconColor = Color.Gray,
                                    unselectedTextColor = Color.Gray
                                )
                            )
                            NavigationBarItem(
                                selected = currentTab == "history",
                                onClick = { currentTab = "history" },
                                icon = { Icon(Icons.Default.History, contentDescription = "Historial") },
                                label = { Text("Historial") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.Black,
                                    selectedTextColor = Color(0xFF00FFCC),
                                    indicatorColor = Color(0xFF00FFCC),
                                    unselectedIconColor = Color.Gray,
                                    unselectedTextColor = Color.Gray
                                )
                            )
                        }
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentTab) {
                            "dashboard" -> {
                                DashboardScreen(
                                    userRepository = userRepository,
                                    onNavigateToCamera = {
                                        if (userRepository.hasSeenTutorial()) {
                                            activeOverlay = "camera"
                                        } else {
                                            activeOverlay = "camera_tutorial"
                                        }
                                    },
                                    onNavigateToQuickCamera = {
                                        activeOverlay = "quick_camera"
                                    },
                                    onNavigateToVideoAnalysis = { uri ->
                                        selectedVideoUri = uri
                                        activeOverlay = "video_analysis"
                                    },
                                    onChangeAthlete = {
                                        currentTab = "athletes"
                                        activeOverlay = null
                                    }
                                )
                            }
                            "athletes" -> {
                                AthletesScreen(userRepository = userRepository)
                            }
                            "history" -> {
                                HistoryScreen(userRepository = userRepository)
                            }
                        }
                    }
                }
            }
        }
    }
}
