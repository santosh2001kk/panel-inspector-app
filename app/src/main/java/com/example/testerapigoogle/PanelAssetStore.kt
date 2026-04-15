package com.example.testerapigoogle

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object PanelAssetStore {

    private const val FILE_NAME = "panel_assets.json"
    private val gson = Gson()

    fun getAll(context: Context): List<PanelAsset> {
        val file = getFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<PanelAsset>>() {}.type
            gson.fromJson<List<PanelAsset>>(file.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getById(context: Context, panelId: String): PanelAsset? {
        return getAll(context).find { it.panelId == panelId }
    }

    // Create a new panel record (called after Commissioning scan)
    fun register(context: Context, asset: PanelAsset) {
        val all = getAll(context).toMutableList()
        all.removeAll { it.panelId == asset.panelId } // replace if already exists
        all.add(0, asset)
        getFile(context).writeText(gson.toJson(all))
    }

    // Add a new intervention to an existing panel (called after any scan)
    fun addIntervention(context: Context, panelId: String, intervention: PanelIntervention) {
        val all = getAll(context).toMutableList()
        val idx = all.indexOfFirst { it.panelId == panelId }
        if (idx == -1) return
        val existing = all[idx]
        val updated = existing.copy(
            interventions = listOf(intervention) + existing.interventions
        )
        all[idx] = updated
        getFile(context).writeText(gson.toJson(all))
    }

    fun delete(context: Context, panelId: String) {
        val updated = getAll(context).filter { it.panelId != panelId }
        getFile(context).writeText(gson.toJson(updated))
    }

    private fun getFile(context: Context): File = File(context.filesDir, FILE_NAME)
}
