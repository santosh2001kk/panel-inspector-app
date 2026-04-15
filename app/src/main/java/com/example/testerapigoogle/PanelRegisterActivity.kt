package com.example.testerapigoogle

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.*

class PanelRegisterActivity : AppCompatActivity() {

    private lateinit var adapter: PanelAdapter
    private val panels = mutableListOf<PanelAsset>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_panel_register)

        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }

        panels.addAll(PanelAssetStore.getAll(this))

        val tvEmpty = findViewById<TextView>(R.id.tvEmpty)
        tvEmpty.visibility = if (panels.isEmpty()) View.VISIBLE else View.GONE

        adapter = PanelAdapter(panels,
            onDelete = { panel, position ->
                AlertDialog.Builder(this)
                    .setTitle("Delete Panel")
                    .setMessage("Delete panel \"${panel.panelId}\" and all its history?")
                    .setPositiveButton("Delete") { _, _ ->
                        PanelAssetStore.delete(this, panel.panelId)
                        panels.removeAt(position)
                        adapter.notifyItemRemoved(position)
                        tvEmpty.visibility = if (panels.isEmpty()) View.VISIBLE else View.GONE
                        Toast.makeText(this, "Panel deleted", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        val rv = findViewById<RecyclerView>(R.id.rvPanels)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
    }
}

class PanelAdapter(
    private val panels: List<PanelAsset>,
    private val onDelete: (PanelAsset, Int) -> Unit
) : RecyclerView.Adapter<PanelAdapter.VH>() {

    private val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvPanelId           : TextView     = view.findViewById(R.id.tvPanelId)
        val tvPanelType         : TextView     = view.findViewById(R.id.tvPanelType)
        val tvSite              : TextView     = view.findViewById(R.id.tvSite)
        val tvBusbar            : TextView     = view.findViewById(R.id.tvBusbar)
        val tvLastIntervention  : TextView     = view.findViewById(R.id.tvLastIntervention)
        val tvCommissioned      : TextView     = view.findViewById(R.id.tvCommissioned)
        val btnDelete           : MaterialButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_panel_asset, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = panels[position]

        holder.tvPanelId.text   = p.panelId
        holder.tvPanelType.text = p.panelType
        holder.tvSite.text      = if (p.site.isNotBlank()) "📍 ${p.site}" else "📍 Site not specified"

        // Busbar warning
        holder.tvBusbar.text = when (p.busbarSide) {
            "left"  -> "⚠️ Bus bar compartment on LEFT — always LIVE"
            "right" -> "⚠️ Bus bar compartment on RIGHT — always LIVE"
            else    -> if (p.panelType.contains("PrismaSeT P", ignoreCase = true) ||
                           p.panelType.contains("Okken", ignoreCase = true))
                           "⚠️ Bus bar compartment — always LIVE"
                       else ""
        }

        // Last intervention
        val last = p.interventions.firstOrNull()
        holder.tvLastIntervention.text = if (last != null) {
            "Last: ${last.task.replaceFirstChar { it.uppercase() }} — ${fmt.format(Date(last.dateMs))} — by ${last.inspector}"
        } else {
            "No interventions recorded yet"
        }

        // First commissioned
        holder.tvCommissioned.text =
            "First registered: ${fmt.format(Date(p.firstSeenMs))} — ${p.firstTask.replaceFirstChar { it.uppercase() }} — by ${p.firstInspector}"

        holder.btnDelete.setOnClickListener { onDelete(p, holder.adapterPosition) }
    }

    override fun getItemCount() = panels.size
}
