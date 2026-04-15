package com.example.testerapigoogle

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class ReportsActivity : AppCompatActivity() {

    private lateinit var adapter: ReportAdapter
    private val files = mutableListOf<File>()
    private val selected = mutableSetOf<String>()   // selected file absolute paths
    private var selectMode = false

    private lateinit var bottomBar: LinearLayout
    private lateinit var tvSelCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_reports)

        val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        files.addAll(
            dir.listFiles { f -> f.extension == "pdf" }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        )

        bottomBar  = findViewById(R.id.bottomActionBar)
        tvSelCount = findViewById(R.id.tvSelCount)

        adapter = ReportAdapter(
            files      = files,
            getSelected = { selected },
            isSelectMode = { selectMode },
            onClick    = { file ->
                if (selectMode) toggleSelect(file)
                else openPdf(file)
            },
            onLongClick = { file ->
                if (!selectMode) {
                    selectMode = true
                    toggleSelect(file)
                }
            },
            onDelete   = { file, position -> confirmDelete(file, position) },
            onShare    = { file -> sharePdf(file) }
        )

        val rv = findViewById<RecyclerView>(R.id.rvReports)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        val tvEmpty = findViewById<TextView>(R.id.tvEmpty)
        tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE

        // ── Header buttons ────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btnExportCsv).setOnClickListener { exportCsv() }

        // ── Bottom bar (shown during multi-select) ────────────────────
        findViewById<MaterialButton>(R.id.btnBackupDrive).setOnClickListener {
            if (selected.isEmpty()) {
                Toast.makeText(this, "Select at least 1 report", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            backupToDrive()
        }

        findViewById<MaterialButton>(R.id.btnCancelSelect).setOnClickListener {
            exitSelectMode()
        }
    }

    // ── Select mode helpers ───────────────────────────────────────────

    private fun toggleSelect(file: File) {
        if (selected.contains(file.absolutePath)) selected.remove(file.absolutePath)
        else selected.add(file.absolutePath)
        updateBottomBar()
        adapter.notifyDataSetChanged()
    }

    private fun updateBottomBar() {
        bottomBar.visibility = if (selectMode) View.VISIBLE else View.GONE
        tvSelCount.text = "${selected.size} selected"
    }

    private fun exitSelectMode() {
        selectMode = false
        selected.clear()
        updateBottomBar()
        adapter.notifyDataSetChanged()
    }

    // ── Open / Share / Delete ─────────────────────────────────────────

    private fun openPdf(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "No PDF viewer installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sharePdf(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share Report"
        ))
    }

    private fun confirmDelete(file: File, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete Report")
            .setMessage("Delete \"${file.nameWithoutExtension}\"?")
            .setPositiveButton("Delete") { _, _ ->
                if (file.delete()) {
                    files.removeAt(position)
                    selected.remove(file.absolutePath)
                    adapter.notifyItemRemoved(position)
                    val tvEmpty = findViewById<TextView>(R.id.tvEmpty)
                    tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
                    Toast.makeText(this, "Report deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── CSV Export ────────────────────────────────────────────────────

    private fun exportCsv() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dir  = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
            val name = "ScanHistory_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
            val csvFile = File(dir, name)

            // Build a lookup map from report file path → scan record
            val historyMap = ScanHistoryStore.getAll(this@ReportsActivity)
                .associateBy { it.reportFilePath }

            val fmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

            FileWriter(csvFile).use { fw ->
                fw.write("Date,Task,Project,Site,Inspector,Panel Type,Panel Summary,Notes,Warnings,Report File\n")

                for (pdfFile in files) {
                    val r = historyMap[pdfFile.absolutePath]
                    if (r != null) {
                        // Full metadata from scan history
                        val warnings = r.warnings.joinToString("; ").replace("\"", "'")
                        val notes    = r.notes.replace("\"", "'").replace("\n", " ")
                        fw.write("\"${fmt.format(Date(r.dateMs))}\",")
                        fw.write("\"${(r.task ?: "others").replaceFirstChar { it.uppercase() }}\",")
                        fw.write("\"${r.projectName}\",")
                        fw.write("\"${r.siteLocation}\",")
                        fw.write("\"${r.inspectorName}\",")
                        fw.write("\"${r.panelType}\",")
                        fw.write("\"${r.panelSummary}\",")
                        fw.write("\"$notes\",")
                        fw.write("\"$warnings\",")
                        fw.write("\"${pdfFile.name}\"\n")
                    } else {
                        // Fallback — only file name and date (old reports saved before this update)
                        fw.write("\"${fmt.format(Date(pdfFile.lastModified()))}\",")
                        fw.write("\"\",\"\",\"\",\"\",\"\",\"\",\"\",")
                        fw.write("\"${pdfFile.name}\"\n")
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (files.isEmpty()) {
                    Toast.makeText(this@ReportsActivity, "No reports to export.", Toast.LENGTH_SHORT).show()
                    csvFile.delete()
                    return@withContext
                }
                val uri = FileProvider.getUriForFile(this@ReportsActivity, "$packageName.provider", csvFile)
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Panel Inspection History")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Export CSV"
                ))
            }
        }
    }

    // ── Combined Report ───────────────────────────────────────────────


    // ── Cloud Backup (share multiple PDFs) ────────────────────────────

    private fun backupToDrive() {
        val uris = ArrayList<Uri>()
        for (path in selected) {
            val file = File(path)
            if (file.exists()) {
                uris.add(FileProvider.getUriForFile(this, "$packageName.provider", file))
            }
        }
        if (uris.isEmpty()) return

        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/pdf"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "Panel Inspection Reports Backup")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Back up to Drive / Email"
        ))
    }
}

// ── Adapter ───────────────────────────────────────────────────────────

class ReportAdapter(
    private val files: MutableList<File>,
    private val getSelected: () -> Set<String>,
    private val isSelectMode: () -> Boolean,
    private val onClick: (File) -> Unit,
    private val onLongClick: (File) -> Unit,
    private val onDelete: (File, Int) -> Unit,
    private val onShare: (File) -> Unit
) : RecyclerView.Adapter<ReportAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName  : TextView     = view.findViewById(R.id.tvReportName)
        val tvDate  : TextView     = view.findViewById(R.id.tvReportDate)
        val btnShare: MaterialButton = view.findViewById(R.id.btnShare)
        val btnDelete: MaterialButton = view.findViewById(R.id.btnDelete)
        val checkbox: CheckBox     = view.findViewById(R.id.cbSelect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_report, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = files[position]
        holder.tvName.text = file.nameWithoutExtension
            .replace("PanelReport_", "Report ")
            .replace("CombinedReport_", "Combined ")
        holder.tvDate.text = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault())
            .format(Date(file.lastModified()))

        val inSelectMode = isSelectMode()
        holder.checkbox.visibility = if (inSelectMode) View.VISIBLE else View.GONE
        holder.checkbox.isChecked  = getSelected().contains(file.absolutePath)
        holder.btnShare.visibility  = if (inSelectMode) View.GONE else View.VISIBLE
        holder.btnDelete.visibility = if (inSelectMode) View.GONE else View.VISIBLE

        holder.itemView.setOnClickListener     { onClick(file) }
        holder.itemView.setOnLongClickListener { onLongClick(file); true }
        holder.btnShare.setOnClickListener  { onShare(file) }
        holder.btnDelete.setOnClickListener { onDelete(file, holder.adapterPosition) }
    }

    override fun getItemCount() = files.size
}
