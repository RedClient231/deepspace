package com.vspace.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vspace.app.R
import com.vspace.app.databinding.ActivityLauncherBinding
import com.vspace.engine.VirtualCore
import com.vspace.engine.model.VirtualAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding
    private lateinit var adapter: AppListAdapter
    private val apps = mutableListOf<VirtualAppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupFab()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        loadApps()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "DeepSpace"
    }

    private fun setupRecyclerView() {
        adapter = AppListAdapter(
            apps,
            onClick = { app -> launchApp(app) },
            onLongClick = { app -> showAppOptions(app) }
        )
        binding.recyclerView.layoutManager = GridLayoutManager(this, 4)
        binding.recyclerView.adapter = adapter
    }

    private fun setupFab() {
        binding.fab.setOnClickListener {
            startActivity(Intent(this, InstallActivity::class.java))
        }
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val installed = withContext(Dispatchers.IO) {
                VirtualCore.get().getInstalledApps()
            }
            apps.clear()
            apps.addAll(installed)
            adapter.notifyDataSetChanged()
            binding.emptyView.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showAppOptions(app: VirtualAppInfo) {
        AlertDialog.Builder(this)
            .setTitle(app.name)
            .setItems(arrayOf("Launch", "Uninstall", "App Info")) { _, which ->
                when (which) {
                    0 -> launchApp(app)
                    1 -> uninstallApp(app)
                    2 -> showAppInfo(app)
                }
            }
            .show()
    }

    private fun launchApp(app: VirtualAppInfo) {
        Toast.makeText(this, "Launching ${app.name}...", Toast.LENGTH_SHORT).show()
        // MUST launch on main thread — startActivity requires it
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                VirtualCore.get().launchApp(this@LauncherActivity, app.packageName)
            }
            if (!result) {
                Toast.makeText(this@LauncherActivity, "Failed to launch ${app.name}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun uninstallApp(app: VirtualAppInfo) {
        AlertDialog.Builder(this)
            .setTitle("Uninstall ${app.name}?")
            .setMessage("This will remove the app and all its data from the virtual space.")
            .setPositiveButton("Uninstall") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    VirtualCore.get().uninstallApp(app.packageName)
                    withContext(Dispatchers.Main) { loadApps() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAppInfo(app: VirtualAppInfo) {
        AlertDialog.Builder(this)
            .setTitle(app.name)
            .setMessage(
                "Package: ${app.packageName}\n" +
                "Version: ${app.versionName}\n" +
                "APK: ${app.apkPath}\n" +
                "Data: ${app.dataDir}\n" +
                "Process Slot: ${app.stubProcessIndex}"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Adapter ──────────────────────────────────────────────────────

    class AppListAdapter(
        private val apps: List<VirtualAppInfo>,
        private val onClick: (VirtualAppInfo) -> Unit,
        private val onLongClick: (VirtualAppInfo) -> Unit
    ) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val name: TextView = view.findViewById(R.id.app_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.name.text = app.name
            if (app.icon != null) {
                holder.icon.setImageDrawable(app.icon)
            } else {
                holder.icon.setImageResource(R.drawable.ic_default_app)
            }
            holder.itemView.setOnClickListener { onClick(app) }
            holder.itemView.setOnLongClickListener { onLongClick(app); true }
        }

        override fun getItemCount() = apps.size
    }
}
