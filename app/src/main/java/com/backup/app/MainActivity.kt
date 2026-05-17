package com.backup.app

import android.app.ProgressDialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var btnSelectAll: Button
    private lateinit var btnBackup: Button
    private lateinit var adapter: AppListAdapter
    private val appList = mutableListOf<AppInfo>()
    private val selectedApps = mutableSetOf<Int>()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // 服务器地址 - 修改为你的服务器地址
    private val serverUrl = "http://36.151.146.29"

    data class AppInfo(
        val name: String,
        val packageName: String,
        val apkPath: String,
        val icon: android.graphics.drawable.Drawable?,
        val size: Long,
        var isSelected: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnBackup = findViewById(R.id.btnBackup)

        adapter = AppListAdapter(appList) { position, isChecked ->
            if (isChecked) selectedApps.add(position) else selectedApps.remove(position)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnSelectAll.setOnClickListener {
            val allSelected = selectedApps.size == appList.size
            if (allSelected) {
                selectedApps.clear()
                appList.forEach { it.isSelected = false }
            } else {
                appList.indices.forEach { selectedApps.add(it) }
                appList.forEach { it.isSelected = true }
            }
            adapter.notifyDataSetChanged()
        }

        btnBackup.setOnClickListener {
            if (selectedApps.isEmpty()) {
                Toast.makeText(this, "请先选择要备份的应用", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showLoginDialog()
        }

        // 检查 root 权限并加载应用列表
        checkRootAndLoadApps()
    }

    private fun checkRootAndLoadApps() {
        scope.launch {
            val hasRoot = withContext(Dispatchers.IO) { checkRootAccess() }
            if (!hasRoot) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("需要 Root 权限")
                    .setMessage("本应用需要 Root 权限才能备份应用数据。\n请确保手机已 Root 并授权本应用。")
                    .setPositiveButton("确定") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
                return@launch
            }
            loadInstalledApps()
        }
    }

    private fun checkRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val result = process.inputStream.bufferedReader().readText()
            process.waitFor()
            result.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    private fun loadInstalledApps() {
        scope.launch {
            val progressDialog = ProgressDialog(this@MainActivity).apply {
                setMessage("正在加载应用列表...")
                setCancelable(false)
                show()
            }

            val apps = withContext(Dispatchers.IO) {
                getInstalledApps()
            }

            appList.clear()
            appList.addAll(apps)
            adapter.notifyDataSetChanged()
            progressDialog.dismiss()
        }
    }

    private fun getInstalledApps(): List<AppInfo> {
        val pm = packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        val apps = mutableListOf<AppInfo>()

        for (packageInfo in packages) {
            val appInfo = packageInfo.applicationInfo ?: continue

            // 跳过系统应用（可选）
            if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue

            val name = appInfo.loadLabel(pm).toString()
            val packageName = packageInfo.packageName
            val apkPath = appInfo.sourceDir ?: continue

            // 获取应用数据目录大小
            val dataDir = "/data/data/$packageName"
            val size = getDirSize(dataDir) + File(apkPath).length()

            val icon = try {
                appInfo.loadIcon(pm)
            } catch (e: Exception) {
                null
            }

            apps.add(AppInfo(name, packageName, apkPath, icon, size))
        }

        return apps.sortedBy { it.name }
    }

    private fun getDirSize(path: String): Long {
        return try {
            val process = Runtime.getRuntime().exec("su -c du -sb $path")
            val result = process.inputStream.bufferedReader().readText()
            process.waitFor()
            result.trim().split("\\s+".toRegex()).firstOrNull()?.toLongOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun showLoginDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_login, null)
        val etUsername = dialogView.findViewById<android.widget.EditText>(R.id.etUsername)
        val etPassword = dialogView.findViewById<android.widget.EditText>(R.id.etPassword)

        AlertDialog.Builder(this)
            .setTitle("登录服务器")
            .setView(dialogView)
            .setPositiveButton("开始备份") { _, _ ->
                val username = etUsername.text.toString().trim()
                val password = etPassword.text.toString().trim()
                if (username.isEmpty()) {
                    Toast.makeText(this, "请输入用户名", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                startBackup(username, password)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startBackup(username: String, password: String) {
        val progressDialog = ProgressDialog(this).apply {
            setMessage("正在备份...")
            setCancelable(false)
            show()
        }

        scope.launch {
            var successCount = 0
            var failCount = 0
            val selectedList = appList.filterIndexed { index, _ -> index in selectedApps }

            for (app in selectedList) {
                progressDialog.setMessage("正在备份: ${app.name}")

                val result = withContext(Dispatchers.IO) {
                    backupAndUpload(app, username, password)
                }

                if (result) successCount++ else failCount++
            }

            progressDialog.dismiss()

            AlertDialog.Builder(this@MainActivity)
                .setTitle("备份完成")
                .setMessage("成功: $successCount\n失败: $failCount")
                .setPositiveButton("确定", null)
                .show()
        }
    }

    private fun backupAndUpload(app: AppInfo, username: String, password: String): Boolean {
        return try {
            android.util.Log.d("BackupApp", "开始备份: ${app.name}")

            // 1. 创建临时目录
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val backupDir = File(cacheDir, "backup_${app.packageName}_$timestamp")
            backupDir.mkdirs()
            android.util.Log.d("BackupApp", "临时目录: ${backupDir.absolutePath}")

            // 2. 复制 APK
            val apkFile = File(backupDir, "base.apk")
            File(app.apkPath).copyTo(apkFile, overwrite = true)
            android.util.Log.d("BackupApp", "APK 大小: ${apkFile.length()} bytes")

            // 3. 备份应用数据（使用 tar + root）
            val dataFile = File(backupDir, "data.tar.gz")
            backupAppData(app.packageName, dataFile)
            android.util.Log.d("BackupApp", "数据文件大小: ${dataFile.length()} bytes")

            // 4. 打包成 zip
            val zipFile = File(cacheDir, "${app.packageName}_$timestamp.zip")
            createZip(backupDir, zipFile)
            android.util.Log.d("BackupApp", "ZIP 大小: ${zipFile.length()} bytes")

            // 5. 检查大小限制（150M）
            if (zipFile.length() > 150 * 1024 * 1024) {
                android.util.Log.e("BackupApp", "文件过大: ${zipFile.length()} bytes")
                zipFile.delete()
                backupDir.deleteRecursively()
                runOnUiThread {
                    Toast.makeText(this, "备份失败: 文件过大", Toast.LENGTH_LONG).show()
                }
                return false
            }

            // 6. 上传到服务器
            android.util.Log.d("BackupApp", "开始上传到: $serverUrl/api/backup/upload")
            val result = uploadToServer(zipFile, app.name, app.packageName, username, password)
            android.util.Log.d("BackupApp", "上传结果: $result")

            // 7. 清理临时文件
            zipFile.delete()
            backupDir.deleteRecursively()

            if (!result) {
                runOnUiThread {
                    Toast.makeText(this, "备份失败: 上传到服务器失败", Toast.LENGTH_LONG).show()
                }
            }

            result
        } catch (e: Exception) {
            android.util.Log.e("BackupApp", "备份失败", e)
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "备份异常: ${e.message}", Toast.LENGTH_LONG).show()
            }
            false
        }
    }

    private fun backupAppData(packageName: String, outputFile: File) {
        try {
            val dataDir = "/data/data/$packageName"
            val cmd = "su -c 'tar czf - -C / data/data/$packageName' > ${outputFile.absolutePath}"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createZip(sourceDir: File, outputZip: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZip))).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val entryName = file.relativeTo(sourceDir).path
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun uploadToServer(
        file: File,
        appName: String,
        packageName: String,
        username: String,
        password: String
    ): Boolean {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    file.name,
                    file.asRequestBody("application/zip".toMediaType())
                )
                .addFormDataPart("app_name", appName)
                .addFormDataPart("package_name", packageName)
                .addFormDataPart("username", username)
                .addFormDataPart("password", password)
                .build()

            val url = "$serverUrl/api/backup/upload"
            android.util.Log.d("BackupApp", "请求 URL: $url")

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            android.util.Log.d("BackupApp", "响应码: ${response.code}")
            android.util.Log.d("BackupApp", "响应体: $body")

            response.isSuccessful
        } catch (e: Exception) {
            android.util.Log.e("BackupApp", "上传失败", e)
            e.printStackTrace()
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
