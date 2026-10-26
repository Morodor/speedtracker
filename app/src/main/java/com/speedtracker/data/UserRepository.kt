package com.speedtracker.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.speedtracker.domain.model.Session
import com.speedtracker.domain.model.User
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class UserRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("speed_tracker_db", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveUser(name: String, weight: Double): User {
        val users = getUsers().toMutableList()
        val newUser = User(UUID.randomUUID().toString(), name, weight)
        users.add(newUser)
        prefs.edit().putString("users", gson.toJson(users)).apply()
        setActiveUserId(newUser.id)
        return newUser
    }

    fun updateUser(updatedUser: User) {
        val users = getUsers().map { if (it.id == updatedUser.id) updatedUser else it }
        prefs.edit().putString("users", gson.toJson(users)).apply()
    }

    fun deleteUser(userId: String) {
        val users = getUsers().filter { it.id != userId }
        val sessions = getSessions().filter { it.userId != userId }
        prefs.edit()
            .putString("users", gson.toJson(users))
            .putString("sessions", gson.toJson(sessions))
            .apply()

        if (getActiveUser()?.id == userId) {
            val nextUser = users.firstOrNull()
            if (nextUser != null) {
                setActiveUserId(nextUser.id)
            } else {
                prefs.edit().remove("active_user").apply()
            }
        }
    }

    fun getUsers(): List<User> {
        val json = prefs.getString("users", "[]") ?: "[]"
        val type = object : TypeToken<List<User>>() {}.type
        return gson.fromJson(json, type)
    }

    fun setActiveUserId(userId: String) {
        prefs.edit().putString("active_user", userId).apply()
    }

    fun getActiveUser(): User? {
        val id = prefs.getString("active_user", null) ?: return null
        return getUsers().find { it.id == id }
    }

    fun saveSession(session: Session) {
        val sessions = getSessions().toMutableList()
        sessions.add(session)
        prefs.edit().putString("sessions", gson.toJson(sessions)).apply()
    }

    fun getSessions(): List<Session> {
        val json = prefs.getString("sessions", "[]") ?: "[]"
        val type = object : TypeToken<List<Session>>() {}.type
        return gson.fromJson(json, type)
    }

    fun getSessionsForActiveUser(): List<Session> {
        val activeUserId = getActiveUser()?.id ?: return emptyList()
        return getSessions().filter { it.userId == activeUserId }.sortedByDescending { it.timestamp }
    }

    fun deleteSession(sessionId: String) {
        val sessions = getSessions().filter { it.id != sessionId }
        prefs.edit().putString("sessions", gson.toJson(sessions)).apply()
    }

    fun shareSessionsCsv(context: Context) {
        val sessions = getSessions().sortedByDescending { it.timestamp }
        val users = getUsers().associateBy { it.id }
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        val sb = StringBuilder()
        sb.append("Fecha,Atleta,Peso_kg,Tipo,Resultado,Velocidad_Media,Carga_kg,Tipo_Salto,Tiempo_Contacto_ms,RSI,Watts_Pico,W_kg,Desvio_Horizontal_cm\n")
        for (s in sessions) {
            val athleteName = users[s.userId]?.name ?: "Invitado"
            val athleteWeight = users[s.userId]?.bodyWeightKg ?: 70.0
            val dateStr = sdf.format(Date(s.timestamp))
            val resStr = if (s.activityType == "JUMP") {
                String.format(Locale.US, "%.1f cm", s.result * 100.0)
            } else {
                String.format(Locale.US, "%.2f m/s", s.result)
            }
            val meanVel = s.meanVelocity?.let { String.format(Locale.US, "%.2f m/s", it) } ?: ""
            val load = s.loadKg?.let { String.format(Locale.US, "%.1f", it) } ?: ""
            val jumpT = s.jumpType ?: ""
            val ct = s.contactTimeMs?.toString() ?: ""
            val rsiVal = s.rsi?.let { String.format(Locale.US, "%.2f", it) } ?: ""
            val watts = s.powerWatts?.toInt()?.toString() ?: ""
            val wkg = s.powerWattsPerKg?.let { String.format(Locale.US, "%.1f", it) } ?: ""
            val dev = s.horizontalDeviationCm?.let { String.format(Locale.US, "%.1f", it) } ?: ""
            sb.append("$dateStr,$athleteName,$athleteWeight,${s.activityType},$resStr,$meanVel,$load,$jumpT,$ct,$rsiVal,$watts,$wkg,$dev\n")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Speed Tracker - Historial Biomecanico")
            putExtra(Intent.EXTRA_TEXT, sb.toString())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(Intent.createChooser(intent, "Compartir datos CSV").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    fun hasSeenTutorial(): Boolean {
        return prefs.getBoolean("has_seen_tutorial", false)
    }

    fun setTutorialSeen() {
        prefs.edit().putBoolean("has_seen_tutorial", true).apply()
    }
}
