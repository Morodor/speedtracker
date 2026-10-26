package com.speedtracker.presentation.athletes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.speedtracker.data.UserRepository
import com.speedtracker.domain.model.User

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AthletesScreen(
    userRepository: UserRepository
) {
    var users by remember { mutableStateOf(userRepository.getUsers()) }
    val activeUser = userRepository.getActiveUser()

    var showCreateDialog by remember { mutableStateOf(false) }
    var userToEdit by remember { mutableStateOf<User?>(null) }
    var userToDelete by remember { mutableStateOf<User?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gestión de Atletas", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF121212),
                    titleContentColor = Color(0xFF00FFCC)
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
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
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = "Toca un perfil para seleccionarlo como activo para tus entrenamientos y videos.",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall
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
                            Text("No hay atletas creados", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Toca '+ Nuevo Atleta' para comenzar.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
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
                                users = userRepository.getUsers()
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) Color(0xFF1B3830) else Color(0xFF262626)
                        ),
                        border = if (isCurrent) CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF00FFCC))) else null,
                        shape = RoundedCornerShape(10.dp)
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
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            user.name,
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (isCurrent) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                color = Color(0x3300FFCC),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    "ACTIVO",
                                                    color = Color(0xFF00FFCC),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Black,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        "Peso corporal: ${user.bodyWeightKg} Kg",
                                        color = Color.LightGray,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            Row {
                                IconButton(onClick = { userToEdit = user }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Editar", tint = Color.LightGray)
                                }
                                IconButton(onClick = { userToDelete = user }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Eliminar", tint = Color(0xFFFF5252))
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(72.dp)) }
        }

        // Diálogo Crear Atleta
        if (showCreateDialog) {
            var name by remember { mutableStateOf("") }
            var weight by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("Nuevo Atleta") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Nombre Completo") },
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
                                showCreateDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
                    ) {
                        Text("Crear", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) { Text("Cancelar") }
                }
            )
        }

        // Diálogo Editar Atleta
        userToEdit?.let { user ->
            var editName by remember { mutableStateOf(user.name) }
            var editWeight by remember { mutableStateOf(user.bodyWeightKg.toString()) }

            AlertDialog(
                onDismissRequest = { userToEdit = null },
                title = { Text("Editar Atleta") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text("Nombre") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = editWeight,
                            onValueChange = { editWeight = it },
                            label = { Text("Peso Corporal (Kg)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val w = editWeight.toDoubleOrNull() ?: user.bodyWeightKg
                            if (editName.isNotBlank()) {
                                userRepository.updateUser(user.copy(name = editName.trim(), bodyWeightKg = w))
                                users = userRepository.getUsers()
                                userToEdit = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
                    ) {
                        Text("Guardar Cambios", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { userToEdit = null }) { Text("Cancelar") }
                }
            )
        }

        // Diálogo Confirmar Eliminación
        userToDelete?.let { user ->
            AlertDialog(
                onDismissRequest = { userToDelete = null },
                title = { Text("¿Eliminar Atleta?") },
                text = { Text("Se eliminará a '${user.name}' y todas sus mediciones históricas registradas.") },
                confirmButton = {
                    Button(
                        onClick = {
                            userRepository.deleteUser(user.id)
                            users = userRepository.getUsers()
                            userToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252), contentColor = Color.White)
                    ) {
                        Text("Eliminar", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { userToDelete = null }) { Text("Cancelar") }
                }
            )
        }
    }
}
