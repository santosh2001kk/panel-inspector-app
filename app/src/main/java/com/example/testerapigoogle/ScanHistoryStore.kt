package com.example.testerapigoogle

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object ScanHistoryStore {

    private const val FILE_NAME = "scan_history.json"
    private val gson = Gson()

    fun save(context: Context, record: ScanRecord) {
        val records = getAll(context).toMutableList()
        records.add(0, record)          // newest first
        getFile(context).writeText(gson.toJson(records))
    }

    fun getAll(context: Context): List<ScanRecord> {
        val file = getFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<ScanRecord>>() {}.type
            gson.fromJson<List<ScanRecord>>(file.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deleteById(context: Context, id: String) {
        val updated = getAll(context).filter { it.id != id }
        getFile(context).writeText(gson.toJson(updated))
    }

    private fun getFile(context: Context): File = File(context.filesDir, FILE_NAME)
}
