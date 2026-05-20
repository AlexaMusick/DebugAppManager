// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.batchops

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.UserIdInt
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.IPackageManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.RemoteException
import android.os.UserHandleHidden
import androidx.annotation.IntDef
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import io.github.muntashirakon.AppManager.BuildConfig
import io.github.muntashirakon.AppManager.R
import io.github.muntashirakon.AppManager.accessibility.AccessibilityMultiplexer
import io.github.muntashirakon.AppManager.apk.ApkUtils
import io.github.muntashirakon.AppManager.apk.dexopt.DexOptimizer
import io.github.muntashirakon.AppManager.apk.installer.PackageInstallerCompat
import io.github.muntashirakon.AppManager.backup.BackupException
import io.github.muntashirakon.AppManager.backup.BackupManager
import io.github.muntashirakon.AppManager.backup.convert.ConvertUtils
import io.github.muntashirakon.AppManager.backup.dialog.BackupRestoreDialogFragment
import io.github.muntashirakon.AppManager.batchops.struct.*
import io.github.muntashirakon.AppManager.compat.*
import io.github.muntashirakon.AppManager.logs.Logger
import io.github.muntashirakon.AppManager.progress.NotificationProgressHandler
import io.github.muntashirakon.AppManager.progress.ProgressHandler
import io.github.muntashirakon.AppManager.rules.compontents.ComponentUtils
import io.github.muntashirakon.AppManager.rules.compontents.ComponentsBlocker
import io.github.muntashirakon.AppManager.rules.compontents.ExternalComponentsImporter
import io.github.muntashirakon.AppManager.self.SelfPermissions
import io.github.muntashirakon.AppManager.types.UserPackagePair
import io.github.muntashirakon.AppManager.utils.*
import io.github.muntashirakon.io.Paths
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@WorkerThread
class BatchOpsManager(private val mLogger: Logger?, private val mProgressHandler: ProgressHandler?) {
    companion object {
        const val TAG = "BatchOpsManager"\n@IntDef(
            OP_NONE, OP_ADVANCED_FREEZE, OP_BACKUP_APK, OP_BACKUP, OP_BLOCK_COMPONENTS,
            OP_BLOCK_TRACKERS, OP_CLEAR_CACHE, OP_CLEAR_DATA, OP_DELETE_BACKUP, OP_DEXOPT,
            OP_DISABLE_BACKGROUND, OP_EXPORT_RULES, OP_FORCE_STOP, OP_FREEZE, OP_GRANT_PERMISSIONS,
            OP_IMPORT_BACKUPS, OP_NET_POLICY, OP_REVOKE_PERMISSIONS, OP_RESTORE_BACKUP,
            OP_SET_APP_OPS, OP_UNBLOCK_COMPONENTS, OP_UNBLOCK_TRACKERS, OP_UNINSTALL,
            OP_UNFREEZE, OP_ARCHIVE, OP_EDIT_TAGS
        )
        @Retention(AnnotationRetention.SOURCE)
        annotation class OpType

        const val OP_NONE = -1
        const val OP_BACKUP_APK = 0
        const val OP_BACKUP = 1
        const val OP_BLOCK_TRACKERS = 2
        const val OP_CLEAR_DATA = 3
        const val OP_DELETE_BACKUP = 4
        const val OP_FREEZE = 5
        const val OP_DISABLE_BACKGROUND = 6
        const val OP_EXPORT_RULES = 7
        const val OP_FORCE_STOP = 8
        const val OP_RESTORE_BACKUP = 9
        const val OP_UNBLOCK_TRACKERS = 10
        const val OP_UNINSTALL = 11
        const val OP_BLOCK_COMPONENTS = 12
        const val OP_SET_APP_OPS = 13
        const val OP_UNFREEZE = 14
        const val OP_UNBLOCK_COMPONENTS = 15
        const val OP_CLEAR_CACHE = 16
        const val OP_GRANT_PERMISSIONS = 17
        const val OP_REVOKE_PERMISSIONS = 18
        const val OP_IMPORT_BACKUPS = 19
        const val OP_NET_POLICY = 20
        const val OP_DEXOPT = 21
        const val OP_ADVANCED_FREEZE = 22
        const val OP_ARCHIVE = 23
        const val OP_EDIT_TAGS = 24

        private val GROUP_ID = BuildConfig.APPLICATION_ID + ".notification_group.BATCH_OPS"\n}

    class BatchOpsInfo(
        @OpType val op: Int,
        val packages: List<String>,
        @UserIdInt val users: List<Int>,
        val options: IBatchOpOptions?
    ) {
        val size: Int = packages.size

        fun getPair(index: Int): UserPackagePair {
            return UserPackagePair(packages[index], users[index])
        }

        val pairList: List<UserPackagePair>
            get() {
                val list = ArrayList<UserPackagePair>(size)
                for (i in 0 until size) {
                    list.add(getPair(i))
                }
                return list
            }

        companion object {
            @JvmStatic
            fun fromQueue(queueItem: BatchQueueItem): BatchOpsInfo {
                return BatchOpsInfo(queueItem.op, queueItem.packages, queueItem.users, queueItem.options)
            }

            @JvmStatic
            fun fromUserPackagePair(@OpType op: Int, pairs: List<UserPackagePair>, options: IBatchOpOptions?): BatchOpsInfo {
                val result = Result(pairs)
                return BatchOpsInfo(op, result.failedPackages, result.associatedUsers, options)
            }

            @JvmStatic
            fun getInstance(@OpType op: Int, packages: List<String>, @UserIdInt users: List<Int>, options: IBatchOpOptions?): BatchOpsInfo {
                return BatchOpsInfo(op, packages, users, options)
            }
        }
    }

    fun run(info: BatchOpsInfo): Result {
        return when (info.op) {
            OP_BACKUP_APK -> opBackupApk(info)
            OP_BACKUP -> opBackupRestore(info, BackupRestoreDialogFragment.MODE_BACKUP)
            OP_RESTORE_BACKUP -> opBackupRestore(info, BackupRestoreDialogFragment.MODE_RESTORE)
            OP_DELETE_BACKUP -> opBackupRestore(info, BackupRestoreDialogFragment.MODE_DELETE)
            OP_IMPORT_BACKUPS -> opImportBackups(info)
            OP_BLOCK_COMPONENTS -> opBlockComponents(info)
            OP_BLOCK_TRACKERS -> opBlockTrackers(info)
            OP_CLEAR_CACHE -> opClearCache(info)
            OP_CLEAR_DATA -> opClearData(info)
            OP_FREEZE -> opFreezeUnfreeze(info, true)
            OP_UNFREEZE -> opFreezeUnfreeze(info, false)
            OP_ADVANCED_FREEZE -> opFreeze(info)
            OP_DISABLE_BACKGROUND -> opDisableBackground(info)
            OP_GRANT_PERMISSIONS -> opGrantOrRevokePermissions(info, true)
            OP_REVOKE_PERMISSIONS -> opGrantOrRevokePermissions(info, false)
            OP_FORCE_STOP -> opForceStop(info)
            OP_NET_POLICY -> opNetPolicy(info)
            OP_SET_APP_OPS -> opSetAppOps(info)
            OP_UNBLOCK_COMPONENTS -> opUnblockComponents(info)
            OP_UNBLOCK_TRACKERS -> opUnblockTrackers(info)
            OP_UNINSTALL -> opUninstall(info)
            OP_DEXOPT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) opPerformDexOpt(info) else Result(info.pairList, false)
            OP_ARCHIVE -> {
                val mode = (info.options as? BatchArchiveOptions)?.mode ?: ArchiveHandler.MODE_AUTO
                ArchiveHandler.opArchive(info, mProgressHandler, mLogger, mode)
            }
            OP_EDIT_TAGS -> opEditTags(info)
            else -> Result(info.pairList, false)
        }
    }

    private fun opEditTags(info: BatchOpsInfo): Result {
        val failedPackages = mutableListOf<UserPackagePair>()
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        val tags = (info.options as? BatchTagOptions)?.tags
        val appDao = AppsDb.getInstance().appDao()
        for (i in 0 until info.size) {
            updateProgress(lastProgress, i + 1)
            val pair = info.getPair(i)
            try {
                val app = appDao.getByPackageNameSync(pair.packageName, pair.userId)
                if (app != null) {
                    app.tags = tags
                    appDao.insert(app)
                }
            } catch (e: Exception) {
                log("====> op=EDIT_TAGS, pkg=$pair", e)
                failedPackages.add(pair)
            }
        }
        return Result(failedPackages)
    }

    private fun opBackupApk(info: BatchOpsInfo): Result {
        val failedPackages = mutableListOf<UserPackagePair>()
        val context = ContextUtils.getContext()
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        for (i in 0 until info.size) {
            updateProgress(lastProgress, i + 1)
            val pair = info.getPair(i)
            try {
                ApkUtils.backupApk(context, pair.packageName, pair.userId)
            } catch (e: Exception) {
                failedPackages.add(pair)
                log("====> op=BACKUP_APK, pkg=$pair", e)
            }
        }
        return Result(failedPackages)
    }

    private fun opBackupRestore(info: BatchOpsInfo, @BackupRestoreDialogFragment.ActionMode mode: Int): Result {
        return when (mode) {
            BackupRestoreDialogFragment.MODE_BACKUP -> backup(info)
            BackupRestoreDialogFragment.MODE_RESTORE -> restoreBackups(info)
            BackupRestoreDialogFragment.MODE_DELETE -> deleteBackups(info)
            else -> Result(info.pairList)
        }
    }

    private fun backup(info: BatchOpsInfo): Result {
        val failedPackages = Collections.synchronizedList(mutableListOf<UserPackagePair>())
        val context = ContextUtils.getContext()
        val pm = context.packageManager
        val operationName = context.getString(R.string.backup_restore)
        val executor = AppExecutor.getExecutor()
        val counter = AtomicInteger(0)
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        try {
            val options = info.options as BatchBackupOptions
            val max = info.size
            val backupManager = BackupManager()
            for (i in 0 until max) {
                val pair = info.getPair(i)
                executor.submit {
                    synchronized(counter) {
                        counter.set(counter.get() + 1)
                        updateProgress(lastProgress, counter.get())
                    }
                    val appLabel = PackageUtils.getPackageLabel(pm, pair.packageName, pair.userId)
                    val title = context.getString(R.string.backing_up_app, appLabel)
                    val subProgressHandler = newSubProgress(operationName, title)
                    try {
                        backupManager.backup(options.getBackupOpOptions(pair.packageName, pair.userId), subProgressHandler)
                    } catch (e: BackupException) {
                        log("====> op=BACKUP_RESTORE, mode=BACKUP pkg=$pair", e)
                        failedPackages.add(pair)
                    }
                    subProgressHandler?.let {
                        ThreadUtils.postOnMainThread { it.onResult(null) }
                    }
                }
            }
        } catch (th: Throwable) {
            log("====> op=BACKUP_RESTORE, mode=BACKUP", th)
        }
        return Result(failedPackages)
    }

    private fun restoreBackups(info: BatchOpsInfo): Result {
        val failedPackages = Collections.synchronizedList(mutableListOf<UserPackagePair>())
        val context = ContextUtils.getContext()
        val pm = context.packageManager
        val operationName = context.getString(R.string.backup_restore)
        val executor = AppExecutor.getExecutor()
        val requiresRestart = AtomicBoolean()
        val count = AtomicInteger(0)
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        try {
            val options = info.options as BatchBackupOptions
            val max = info.size
            val backupManager = BackupManager()
            for (i in 0 until max) {
                val pair = info.getPair(i)
                executor.submit {
                    synchronized(count) {
                        count.set(count.get() + 1)
                        updateProgress(lastProgress, count.get())
                    }
                    val appLabel = PackageUtils.getPackageLabel(pm, pair.packageName, pair.userId)
                    val title = context.getString(R.string.restoring_app, appLabel)
                    val subProgressHandler = newSubProgress(operationName, title)
                    try {
                        backupManager.restore(options.getRestoreOpOptions(pair.packageName, pair.userId), subProgressHandler)
                        requiresRestart.set(requiresRestart.get() or backupManager.requiresRestart())
                    } catch (e: Throwable) {
                        log("====> op=BACKUP_RESTORE, mode=RESTORE pkg=$pair", e)
                        failedPackages.add(pair)
                    }
                    subProgressHandler?.let {
                        ThreadUtils.postOnMainThread { it.onResult(null) }
                    }
                }
            }
        } catch (th: Throwable) {
            log("====> op=BACKUP_RESTORE, mode=RESTORE", th)
        }
        val result = Result(failedPackages)
        result.requiresRestart = requiresRestart.get()
        return result
    }

    private fun deleteBackups(info: BatchOpsInfo): Result {
        val failedPackages = mutableListOf<UserPackagePair>()
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        try {
            val options = info.options as BatchBackupOptions
            val max = info.size
            val backupManager = BackupManager()
            for (i in 0 until max) {
                updateProgress(lastProgress, i + 1)
                val pair = info.getPair(i)
                try {
                    backupManager.deleteBackup(options.getDeleteOpOptions(pair.packageName, pair.userId))
                } catch (e: BackupException) {
                    log("====> op=BACKUP_RESTORE, mode=DELETE pkg=$pair", e)
                    failedPackages.add(pair)
                }
            }
        } catch (th: Throwable) {
            log("====> op=BACKUP_RESTORE, mode=DELETE", th)
        }
        return Result(failedPackages)
    }

    private fun opImportBackups(info: BatchOpsInfo): Result {
        val failedPkgList = Collections.synchronizedList(mutableListOf<UserPackagePair>())
        val executor = AppExecutor.getExecutor()
        try {
            val userId = UserHandleHidden.myUserId()
            val options = info.options as BatchBackupImportOptions
            val uri = options.directory
            val backupPath = Paths.get(uri)
            if (!backupPath.isDirectory) {
                log("====> op=IMPORT_BACKUP, Not a directory.")
                return Result(emptyList(), false)
            }
            val files = ConvertUtils.getRelevantImportFiles(backupPath, options.importType)
            fixProgress(files.size)
            val lastProgress = mProgressHandler?.lastProgress ?: 0f
            val counter = AtomicInteger(0)
            for (file in files) {
                executor.submit {
                    synchronized(counter) {
                        counter.set(counter.get() + 1)
                        updateProgress(lastProgress, counter.get())
                    }
                    val converter = ConvertUtils.getConversionUtil(options.importType, file)
                    try {
                        converter.convert()
                        if (options.isRemoveImportedDirectory) {
                            converter.cleanup()
                        }
                    } catch (e: BackupException) {
                        log("====> op=IMPORT_BACKUP, pkg=${converter.packageName}", e)
                        failedPkgList.add(UserPackagePair(converter.packageName, userId))
                    }
                }
            }
        } catch (th: Throwable) {
            log("====> op=IMPORT_BACKUP", th)
        }
        return Result(failedPkgList)
    }

    private fun opBlockComponents(info: BatchOpsInfo): Result {
        val options = info.options as BatchComponentOptions
        val failedPackages = mutableListOf<UserPackagePair>()
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        val max = info.size
        for (i in 0 until max) {
            updateProgress(lastProgress, i + 1)
            val pair = info.getPair(i)
            try {
                ComponentUtils.blockFilteredComponents(pair, options.signatures)
            } catch (e: Exception) {
                log("====> op=BLOCK_COMPONENTS, pkg=$pair", e)
                failedPackages.add(pair)
            }
        }
        return Result(failedPackages)
    }

    private fun opBlockTrackers(info: BatchOpsInfo): Result {
        val failedPackages = mutableListOf<UserPackagePair>()
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        val max = info.size
        for (i in 0 until max) {
            updateProgress(lastProgress, i + 1)
            val pair = info.getPair(i)
            try {
                ComponentUtils.blockTrackingComponents(pair)
            } catch (e: Exception) {
                log("====> op=BLOCK_TRACKERS, pkg=$pair", e)
                failedPackages.add(pair)
            }
        }
        return Result(failedPackages)
    }

    private fun opClearCache(info: BatchOpsInfo): Result {
        if (info.size == 0) {
            return opTrimCaches()
        }
        val failedPackages = mutableListOf<UserPackagePair>()
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        val max = info.size
        for (i in 0 until max) {
            updateProgress(lastProgress, i + 1)
            val pair = info.getPair(i)
            try {
                PackageManagerCompat.deleteApplicationCacheFilesAsUser(pair)
            } catch (e: Exception) {
                log("====> op=CLEAR_CACHE, pkg=$pair", e)
                failedPackages.add(pair)
            }
        }
        return Result(failedPackages)
    }

    private fun opTrimCaches(): Result {
        val size = 1024L * 1024L * 1024L * 1024L // 1 TB
        var isSuccessful: Boolean
        try {
            PackageManagerCompat.freeStorageAndNotify(null, size, StorageManagerCompat.FLAG_ALLOCATE_DEFY_ALL_RESERVED)
            isSuccessful = true
        } catch (e: Throwable) {
            log("====> op=TRIM_CACHES", e)
            isSuccessful = false
        }
        return Result(emptyList(), isSuccessful)
    }

    private fun opClearData(info: BatchOpsInfo): Result {
        val failedPackages = mutableListOf<UserPackagePair>()
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        val max = info.size
        for (i in 0 until max) {
            updateProgress(lastProgress, i + 1)
            val pair = info.getPair(i)
            try {
                PackageManagerCompat.clearApplicationUserData(pair)
            } catch (e: Exception) {
                log("====> op=CLEAR_DATA, pkg=$pair", e)
                failedPackages.add(pair)
            }
        }
        return Result(failedPackages)
    }

    private fun opFreeze(info: BatchOpsInfo): Result {
        val options = info.options as BatchFreezeOptions
        val failedPackages = mutableListOf<UserPackagePair>()
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        val max = info.size
        for (i in 0 until max) {
            updateProgress(lastProgress, i + 1)
            val pair = info.getPair(i)
            val type = if (options.isPreferCustom) {
                FreezeUtils.loadFreezeMethod(pair.packageName) ?: options.type
            } else options.type
            try {
                FreezeUtils.freeze(pair.packageName, pair.userId, type)
            } catch (e: Throwable) {
                log("====> op=ADVANCED_FREEZE, pkg=$pair, type = $type", e)
                failedPackages.add(pair)
            }
        }
        return Result(failedPackages)
    }

    private fun opFreezeUnfreeze(info: BatchOpsInfo, freeze: Boolean): Result {
        val failedPackages = mutableListOf<UserPackagePair>()
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        val max = info.size
        for (i in 0 until max) {
            updateProgress(lastProgress, i + 1)
            val pair = info.getPair(i)
            try {
                if (freeze) {
                    FreezeUtils.freeze(pair.packageName, pair.userId)
                } else {
                    FreezeUtils.unfreeze(pair.packageName, pair.userId)
                }
            } catch (e: Throwable) {
                log("====> op=APP_FREEZE, pkg=$pair, freeze = $freeze", e)
                failedPackages.add(pair)
            }
        }
        return Result(failedPackages)
    }

    private fun opDisableBackground(info: BatchOpsInfo): Result {
        val failedPackages = mutableListOf<UserPackagePair>()
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        val appOpsManager = AppOpsManagerCompat()
        val max = info.size
        for (i in 0 until max) {
            updateProgress(lastProgress, i + 1)
            val pair = info.getPair(i)
            val uid = PackageUtils.getAppUid(pair)
            if (uid == -1) {
                failedPackages.add(pair)
                continue
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    appOpsManager.setMode(AppOpsManagerCompat.OP_RUN_IN_BACKGROUND, uid, pair.packageName, AppOpsManager.MODE_IGNORED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    appOpsManager.setMode(AppOpsManagerCompat.OP_RUN_ANY_IN_BACKGROUND, uid, pair.packageName, AppOpsManager.MODE_IGNORED)
                }
                ComponentsBlocker.getMutableInstance(pair.packageName, pair.userId).use { cb ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        cb.setAppOp(AppOpsManagerCompat.OP_RUN_IN_BACKGROUND, AppOpsManager.MODE_IGNORED)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        cb.setAppOp(AppOpsManagerCompat.OP_RUN_ANY_IN_BACKGROUND, AppOpsManager.MODE_IGNORED)
                    }
                }
            } catch (e: Throwable) {
                log("====> op=DISABLE_BACKGROUND, pkg=$pair", e)
                failedPackages.add(pair)
            }
        }
        return Result(failedPackages)
    }

    private fun opGrantOrRevokePermissions(info: BatchOpsInfo, isGrant: Boolean): Result {
        val options = info.options as BatchPermissionOptions
        var permissions = options.permissions
        val failedPackages = mutableListOf<UserPackagePair>()
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        val max = info.size
        if (permissions.size == 1 && permissions[0] == "*") {
            // Wildcard detected
            for (i in 0 until max) {
                updateProgress(lastProgress, i + 1)
                val pair = info.getPair(i)
                try {
                    val pkgPermissions = PackageUtils.getPermissionsForPackage(pair.packageName, pair.userId) ?: continue
                    for (permission in pkgPermissions) {
                        if (isGrant) {
                            PermissionCompat.grantPermission(pair.packageName, permission, pair.userId)
                        } else {
                            PermissionCompat.revokePermission(pair.packageName, permission, pair.userId)
                        }
                    }
                } catch (e: Throwable) {
                    log("====> op=GRANT_OR_REVOKE_PERMISSIONS, pkg=$pair", e)
                    failedPackages.add(pair)
                }
            }
        } else {
            for (i in 0 until max) {
                updateProgress(lastProgress, i + 1)
                val pair = info.getPair(i)
                for (permission in permissions) {
                    try {
                        if (isGrant) {
                            PermissionCompat.grantPermission(pair.packageName, permission, pair.userId)
                        } else {
                            PermissionCompat.revokePermission(pair.packageName, permission, pair.userId)
                        }
                    } catch (e: Throwable) {
                        log("====> op=GRANT_OR_REVOKE_PERMISSIONS, pkg=$pair", e)
                        failedPackages.add(pair)
                    }
                }
            }
        }
        return Result(failedPackages)
    }

    private fun opForceStop(info: BatchOpsInfo): Result {
        val failedPackages = mutableListOf<UserPackagePair>()
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        val max = info.size
        for (i in 0 until max) {
            updateProgress(lastProgress, i + 1)
            val pair = info.getPair(i)
            try {
                PackageManagerCompat.forceStopPackage(pair.packageName, pair.userId)
            } catch (e: Throwable) {
                log("====> op=FORCE_STOP, pkg=$pair", e)
                failedPackages.add(pair)
            }
        }
        return Result(failedPackages)
    }

    private fun opNetPolicy(info: BatchOpsInfo): Result {
        val failedPackages = mutableListOf<UserPackagePair>()
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        val options = info.options as BatchNetPolicyOptions
        val max = info.size
        for (i in 0 until max) {
            updateProgress(lastProgress, i + 1)
            val pair = info.getPair(i)
            try {
                val uid = PackageUtils.getAppUid(pair)
                NetworkPolicyManagerCompat.setUidPolicy(uid, options.policies)
            } catch (e: Throwable) {
                log("====> op=NET_POLICY, pkg=$pair", e)
                failedPackages.add(pair)
            }
        }
        return Result(failedPackages)
    }

    private fun opSetAppOps(info: BatchOpsInfo): Result {
        val failedPkgList = mutableListOf<UserPackagePair>()
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        val appOpsManager = AppOpsManagerCompat()
        val options = info.options as BatchAppOpsOptions
        val appOps = options.appOps
        val max = info.size
        if (appOps.size == 1 && appOps[0] == AppOpsManagerCompat.OP_NONE) {
            // Wildcard detected
            for (i in 0 until max) {
                updateProgress(lastProgress, i + 1)
                val pair = info.getPair(i)
                try {
                    val applicationInfo = PackageManagerCompat.getApplicationInfo(
                        pair.packageName,
                        PackageManagerCompat.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES, pair.userId
                    )
                    val entries = AppOpsManagerCompat.getConfiguredOpsForPackage(
                        appOpsManager, applicationInfo!!.packageName, applicationInfo.uid
                    )
                    val appOpList = entries.map { it.op }.toIntArray()
                    ExternalComponentsImporter.setModeToFilteredAppOps(appOpsManager, pair, appOpList, options.mode)
                } catch (e: Exception) {
                    log("====> op=SET_APP_OPS, pkg=$pair", e)
                    failedPkgList.add(pair)
                }
            }
        } else {
            for (i in 0 until max) {
                updateProgress(lastProgress, i + 1)
                val pair = info.getPair(i)
                try {
                    ExternalComponentsImporter.setModeToFilteredAppOps(appOpsManager, pair, appOps, options.mode)
                } catch (e: RemoteException) {
                    log("====> op=SET_APP_OPS, pkg=$pair", e)
                    failedPkgList.add(pair)
                }
            }
        }
        return Result(failedPkgList)
    }

    private fun opUnblockComponents(info: BatchOpsInfo): Result {
        val failedPackages = mutableListOf<UserPackagePair>()
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        val options = info.options as BatchComponentOptions
        val max = info.size
        for (i in 0 until max) {
            updateProgress(lastProgress, i + 1)
            val pair = info.getPair(i)
            try {
                ComponentUtils.unblockFilteredComponents(pair, options.signatures)
            } catch (th: Throwable) {
                log("====> op=UNBLOCK_COMPONENTS, pkg=$pair", th)
                failedPackages.add(pair)
            }
        }
        return Result(failedPackages)
    }

    private fun opUnblockTrackers(info: BatchOpsInfo): Result {
        val failedPackages = mutableListOf<UserPackagePair>()
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        val max = info.size
        for (i in 0 until max) {
            updateProgress(lastProgress, i + 1)
            val pair = info.getPair(i)
            try {
                ComponentUtils.unblockTrackingComponents(pair)
            } catch (th: Throwable) {
                log("====> op=UNBLOCK_TRACKERS, pkg=$pair", th)
                failedPackages.add(pair)
            }
        }
        return Result(failedPackages)
    }

    private fun opUninstall(info: BatchOpsInfo): Result {
        val failedPackages = mutableListOf<UserPackagePair>()
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        val accessibility = AccessibilityMultiplexer.getInstance()
        if (!SelfPermissions.checkSelfOrRemotePermission(Manifest.permission.DELETE_PACKAGES)) {
            // Try to use accessibility in unprivileged mode
            accessibility.enableUninstall(true)
        }
        val max = info.size
        for (i in 0 until max) {
            updateProgress(lastProgress, i + 1)
            val pair = info.getPair(i)
            val installer = PackageInstallerCompat.getNewInstance()
            if (!installer.uninstall(pair.packageName, pair.userId, false)) {
                log("====> op=UNINSTALL, pkg=$pair")
                failedPackages.add(pair)
            }
        }
        accessibility.enableUninstall(false)
        return Result(failedPackages)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun opPerformDexOpt(info: BatchOpsInfo): Result {
        val failedPackages = mutableListOf<UserPackagePair>()
        val pm = PackageManagerCompat.getPackageManager()
        val options = (info.options as BatchDexOptOptions).dexOptOptions
        if (info.size > 0) {
            options.setPackages(info.packages.toTypedArray())
        } else if (options.packages == null) {
            try {
                options.setPackages(pm.allPackages.toTypedArray())
            } catch (e: RemoteException) {
                log("====> op=DEXOPT", e)
                return Result(failedPackages, false)
            }
        }
        fixProgress(options.packages!!.size)
        val lastProgress = mProgressHandler?.lastProgress ?: 0f
        var i = 0
        for (packageName in options.packages!!) {
            updateProgress(lastProgress, ++i)
            if (packageName == BuildConfig.APPLICATION_ID) continue
            val dexOptimizer = DexOptimizer(pm, packageName)
            if (options.compilerFilter != null) {
                var result = true
                if (options.clearProfileData) {
                    result = result and dexOptimizer.clearApplicationProfileData()
                }
                result = result and dexOptimizer.performDexOptMode(
                    options.checkProfiles, options.compilerFilter!!,
                    options.forceCompilation, options.bootComplete, null
                )
                if (!result) {
                    log("====> op=DEXOPT, pkg=$packageName, failed=dexopt-mode", dexOptimizer.lastError)
                    failedPackages.add(UserPackagePair(packageName, 0))
                    continue
                }
            }
            if (options.compileLayouts && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var result = true
                if (options.clearProfileData) {
                    result = result and dexOptimizer.clearApplicationProfileData()
                }
                result = result and dexOptimizer.compileLayouts()
                if (!result) {
                    log("====> op=DEXOPT, pkg=$packageName, failed=compile-layouts", dexOptimizer.lastError)
                    failedPackages.add(UserPackagePair(packageName, 0))
                    continue
                }
            }
            if (options.forceDexOpt) {
                if (!dexOptimizer.forceDexOpt()) {
                    log("====> op=DEXOPT, pkg=$packageName, failed=force-dexopt", dexOptimizer.lastError)
                    failedPackages.add(UserPackagePair(packageName, 0))
                }
            }
        }
        return Result(failedPackages)
    }

    private fun log(message: String?, th: Throwable?) {
        mLogger?.println(message, th)
    }

    private fun log(message: String?) {
        mLogger?.println(message)
    }

    private fun updateProgress(last: Float, current: Int) {
        mProgressHandler?.postUpdate(last + current)
    }

    private fun fixProgress(appendMax: Int) {
        if (mProgressHandler == null) return
        val max = Math.max(mProgressHandler.lastMax, 0) + appendMax
        val current = mProgressHandler.lastProgress
        mProgressHandler.postUpdate(max, current)
    }

    private fun newSubProgress(operationName: CharSequence?, title: CharSequence?): ProgressHandler? {
        if (mProgressHandler == null) return null
        val message = mProgressHandler.lastMessage ?: return null
        val p = mProgressHandler.newSubProgressHandler()
        if (p is NotificationProgressHandler) {
            val parentNotificationInfo = message as NotificationProgressHandler.NotificationInfo
            val notificationInfo = NotificationProgressHandler.NotificationInfo(parentNotificationInfo)
                .setOperationName(operationName)
                .setTitle(title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                notificationInfo.groupId = GROUP_ID
            }
            ThreadUtils.postOnMainThread { p.onProgressStart(-1, 0, notificationInfo) }
        }
        return p
    }

    class Result {
        val failedPackages: ArrayList<String>
        val associatedUsers: ArrayList<Int>
        val isSuccessful: Boolean
        var requiresRestart: Boolean = false

        constructor(failedUserPackagePairs: List<UserPackagePair>) : this(failedUserPackagePairs, failedUserPackagePairs.isEmpty())

        constructor(failedUserPackagePairs: List<UserPackagePair>, isSuccessful: Boolean) {
            failedPackages = ArrayList()
            associatedUsers = ArrayList()
            for (userPackagePair in failedUserPackagePairs) {
                failedPackages.add(userPackagePair.packageName)
                associatedUsers.add(userPackagePair.userId)
            }
            this.isSuccessful = isSuccessful
        }
    }
}
