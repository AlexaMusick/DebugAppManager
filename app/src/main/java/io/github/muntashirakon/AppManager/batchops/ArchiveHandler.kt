// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.batchops

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.IPackageDeleteObserver2
import android.content.pm.IPackageManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import io.github.muntashirakon.AppManager.db.AppsDb
import io.github.muntashirakon.AppManager.db.entity.ArchivedApp
import io.github.muntashirakon.AppManager.logs.Logger
import io.github.muntashirakon.AppManager.progress.ProgressHandler
import io.github.muntashirakon.AppManager.types.UserPackagePair
import io.github.muntashirakon.AppManager.utils.ContextUtils
import io.github.muntashirakon.AppManager.utils.ShizukuUtils
import io.github.muntashirakon.AppManager.settings.Ops
import io.github.muntashirakon.AppManager.batchops.struct.BatchArchiveOptions
import io.github.muntashirakon.AppManager.apk.installer.PackageInstallerCompat
import kotlinx.coroutines.runBlocking
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Archive Handler for app archiving operations
 * 
 * Archives apps by uninstalling them while preserving APK files.
 * This is separate from cache/data cleaning operations.
 */
object ArchiveHandler {
    const val MODE_AUTO = 0
    const val MODE_SHIZUKU = 1
    const val MODE_ROOT = 2
    const val MODE_STANDARD = 3

    @JvmStatic
    fun opArchive(
        info: BatchOpsManager.BatchOpsInfo,
        progressHandler: ProgressHandler?,
        logger: Logger?,
        mode: Int
    ): BatchOpsManager.Result {
        val failedPackages = ArrayList<UserPackagePair>()
        val lastProgress = progressHandler?.lastProgress ?: 0f
        val archivedAppDao = AppsDb.getInstance().archivedAppDao()
        val context = ContextUtils.getContext()
        val pm = context.packageManager

        val max = info.size()

        // Optimized Shizuku Shell
        val shell: ShizukuUtils.ShizukuShell? = if (mode == MODE_AUTO || mode == MODE_SHIZUKU) ShizukuUtils.newShell(context) else null

        try {
            for (i in 0 until max) {
                progressHandler?.postUpdate(lastProgress + i + 1)
                val pair = info.getPair(i)

                if (ContextUtils.getContext().packageName == pair.packageName) {
                    log(logger, "====> op=ARCHIVE, cannot archive the app itself")
                    failedPackages.add(pair)
                    continue
                }

                try {
                    val appInfo = pm.getApplicationInfo(pair.packageName, 0)
                    val appName = appInfo.loadLabel(pm).toString()
                    val apkPath = appInfo.sourceDir
                    val size = File(apkPath).length()
                    
                    // Retrieve existing tags if no new tags are provided in options
                    val archiveOptions = info.options as? BatchArchiveOptions
                    var tags = archiveOptions?.tags
                    if (tags == null) {
                        val app = AppsDb.getInstance().appDao().getByPackageNameSync(pair.packageName, pair.userId)
                        tags = app?.tags
                    }
                    
                    log(logger, "Archiving ${pair.packageName} size=$size bytes tags=$tags")

                    var success = false

                    // Modern Archiving API (Android 15+)
                    if (mode == MODE_AUTO && Build.VERSION.SDK_INT >= 35) {
                        val packageInstaller = context.packageManager.packageInstaller
                        val intent = Intent(ArchiveResultReceiver.ACTION_ARCHIVE_RESULT).apply {
                            setPackage(context.packageName)
                        }

                        val pendingIntent = PendingIntent.getBroadcast(
                            context,
                            pair.packageName.hashCode(),
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                        )

                        try {
                            packageInstaller.requestArchive(pair.packageName, pendingIntent.intentSender)
                            success = true
                            log(logger, "====> Requested system archive for ${pair.packageName}")
                        } catch (e: Exception) {
                            log(logger, "====> System archive failed for ${pair.packageName}, falling back...", e)
                        }
                    }
                    
                    if (!success && (mode == MODE_AUTO || mode == MODE_SHIZUKU)) {
                        if (ShizukuUtils.isShizukuAvailable()) {
                            log(logger, "Trying Shizuku Native Acceleration for ${pair.packageName}")
                            try {
                                val binder = Shizuku.getBinder()
                                if (binder != null) {
                                    val ipm = IPackageManager.Stub.asInterface(Shizuku.newBinderSender(binder, "package"))
                                    val latch = CountDownLatch(1)
                                    var returnCode = -1
                                    val observer = object : IPackageDeleteObserver2.Stub() {
                                        override fun onUserActionRequired(intent: Intent?) {
                                            latch.countDown()
                                        }

                                        override fun onPackageDeleted(packageName: String?, code: Int, msg: String?) {
                                            returnCode = code
                                            latch.countDown()
                                        }
                                    }
                                    // DELETE_KEEP_DATA = 0x00000002
                                    ipm.deletePackage(pair.packageName, observer, pair.userId, 0x00000002)
                                    if (latch.await(10, TimeUnit.SECONDS) && (returnCode == 1 || returnCode == 0)) {
                                        success = true
                                        log(logger, "====> Shizuku Native Acceleration successful")
                                    } else {
                                        log(logger, "====> Shizuku Native failed or timed out: returnCode=$returnCode")
                                    }
                                }
                            } catch (e: Exception) {
                                log(logger, "====> Shizuku Native failed with exception", e)
                            }
                        }
                        
                        if (!success && shell != null) {
                            log(logger, "Trying Shizuku Shell fallback for ${pair.packageName}")
                            val result = shell.runCommand("pm uninstall -k ${pair.packageName}")
                            if (result?.exitCode == 0) {
                                success = true
                                log(logger, "====> Shizuku Shell successful")
                            } else {
                                log(logger, "====> Shizuku Shell failed: exitCode=${result?.exitCode}, stderr=${result?.stderr}")
                            }
                        }
                    }

                    if (!success && (mode == MODE_AUTO || mode == MODE_ROOT) && Ops.isDirectRoot()) {
                        log(logger, "Trying Root archiving for ${pair.packageName}")
                        val installer = PackageInstallerCompat.getNewInstance()
                        success = installer.uninstall(pair.packageName, pair.userId, true)
                        if (success) log(logger, "====> Root archive successful")
                    }

                    if (!success && (mode == MODE_AUTO || mode == MODE_STANDARD)) {
                        log(logger, "Trying Standard archiving for ${pair.packageName}")
                        val installer = PackageInstallerCompat.getNewInstance()
                        success = installer.uninstall(pair.packageName, pair.userId, true)
                        if (success) log(logger, "====> Standard archive successful")
                    }

                    if (success) {
                        val archivedApp = ArchivedApp(pair.packageName, appName, System.currentTimeMillis(), apkPath, tags)
                        runBlocking { archivedAppDao.insert(archivedApp) }
                        log(logger, "====> Archived successfully")
                    } else {
                        failedPackages.add(pair)
                        log(logger, "====> op=ARCHIVE, pkg=$pair failed")
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    failedPackages.add(pair)
                    log(logger, "====> op=ARCHIVE, pkg=$pair not found", e)
                } catch (e: Exception) {
                    failedPackages.add(pair)
                    log(logger, "====> op=ARCHIVE, pkg=$pair", e)
                }
            }
        } finally {
            shell?.close()
        }
        return BatchOpsManager.Result(failedPackages)
    }

    private fun log(logger: Logger?, msg: String) {
        logger?.println(msg)
    }

    private fun log(logger: Logger?, msg: String, tr: Throwable) {
        logger?.println(msg, tr)
    }
}
