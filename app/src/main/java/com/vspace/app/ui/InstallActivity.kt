package com.vspace.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vspace.app.databinding.ActivityInstallBinding
import com.vspace.engine.VirtualCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class InstallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInstallBinding
    private var selectedApkUri: Uri? = null

    private val pickApk = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedApkUri = uri
                showApkInfo(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Install APK"

        binding.btnSelectApk.setOnClickListener { openFilePicker() }
        binding.btnInstall.setOnClickListener { installApk() }
        binding.btnInstall.visibility = View.GONE
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
        }
        pickApk.launch(intent)
    }

    private fun showApkInfo(uri: Uri) {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                copyAndParseApk(uri)
            }
            if (info != null) {
                binding.apkInfoCard.visibility = View.VISIBLE
                binding.tvAppName.text = info.appName
                binding.tvPackageName.text = info.packageName
                binding.tvVersion.text = info.versionName
                binding.btnInstall.visibility = View.VISIBLE
            } else {
                Toast.makeText(this@InstallActivity, "Failed to parse APK", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyAndParseApk(uri: Uri): ApkPreview? {
        return try {
            val tempFile = File(cacheDir, "temp_install.apk")
            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            val pm = packageManager
            val pkgInfo = pm.getPackageArchiveInfo(tempFile.absolutePath, 0) ?: return null
            val appInfo = pkgInfo.applicationInfo?.apply {
                sourceDir = tempFile.absolutePath
                publicSourceDir = tempFile.absolutePath
            }
            ApkPreview(
                appName = appInfo?.loadLabel(pm)?.toString() ?: pkgInfo.packageName,
                packageName = pkgInfo.packageName,
                versionName = pkgInfo.versionName ?: "unknown",
                apkPath = tempFile.absolutePath
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun installApk() {
        val uri = selectedApkUri ?: return
        binding.progressBar.visibility = View.VISIBLE
        binding.btnInstall.isEnabled = false

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                val tempFile = File(cacheDir, "temp_install.apk")
                if (!tempFile.exists()) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                VirtualCore.get().installApp(tempFile.absolutePath)
            }
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                if (success) {
                    Toast.makeText(this@InstallActivity, "Installed successfully", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@InstallActivity, "Installation failed", Toast.LENGTH_SHORT).show()
                    binding.btnInstall.isEnabled = true
                }
            }
        }
    }

    data class ApkPreview(
        val appName: String,
        val packageName: String,
        val versionName: String,
        val apkPath: String
    )
}
