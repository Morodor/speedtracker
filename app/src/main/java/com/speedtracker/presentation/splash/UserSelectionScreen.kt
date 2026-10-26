package com.speedtracker.presentation.splash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.speedtracker.data.UserRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSelectionScreen(
    userRepository: UserRepository,
    onUserSelected: () -> Unit
) {
    var users by remember { mutableStateOf(userRepository.getUsers()) }
    var showDialog by remember { mutableStateOf(false) }
    val activeUser = remember { userRepository.getActiveUser() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Atletas", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (activeUser != null) {
                        IconButton(onClick = onUserSelected) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF121212),
                    titleContentColor = Color(0xFF00FFCC)
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showDialog = true },
                containerColor = Color(0xFF00FFCC),
                contentColor = Color.Black
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Nuevo")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Nuevo Atleta", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF1E1E1E)
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = "Selecciona o crea un perfil de atleta para medir y guardar sus marcas.",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (users.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF262626))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "No hay atletas registrados",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Toca '+ Nuevo Atleta' para crear tu primer perfil.",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            } else {
                items(users) { user ->
                    val isCurrent = user.id == activeUser?.id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                userRepository.setActiveUserId(user.id)
                                onUserSelected()
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) Color(0xFF2A3A35) else Color(0xFF2C2C2C)
                        ),
                        border = if (isCurrent) CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF00FFCC))) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    tint = if (isCurrent) Color(0xFF00FFCC) else Color.Gray,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        user.name,
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "${user.bodyWeightKg} Kg",
                                        color = Color.LightGray,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            if (isCurrent) {
                                Text(
                                    "ACTIVO",
                                    color = Color(0xFF00FFCC),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showDialog) {
            var name by remember { mutableStateOf("") }
            var weight by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Nuevo Atleta") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Nombre") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = weight,
                            onValueChange = { weight = it },
                            label = { Text("Peso Corporal (Kg)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val w = weight.toDoubleOrNull() ?: 70.0
                            if (name.isNotBlank()) {
                                userRepository.saveUser(name.trim(), w)
                                users = userRepository.getUsers()
                                showDialog = false
                                onUserSelected()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00FFCC),
                            contentColor = Color.Black
                        )
                    ) {
                        Text("Crear y Seleccionar", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) {
                        Text("Cancelar")
                    }
                }
            )
        }
    }
}
