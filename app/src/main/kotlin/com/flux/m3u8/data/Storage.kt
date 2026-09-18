package com.flux.m3u8.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.flux.m3u8.util.TempCleaner
import com.flux.m3u8.util.safeRelativePath
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** 目录可用性检查结果。 */
sealed class DirCheck {
    data class Ok(val displayPath: String, val freeBytes: Long) : DirCheck()
    data class Failed(val reason: String) : DirCheck()

    val isOk: Boolean get() = this is Ok
}

/**
 * 保存目录里已存在同名产物。
 *
 * 与"自动改名"不同：重名时直接抛出，交由上层明确提示用户。
 * 悄悄写成 `xxx (1).mp4` 会让用户以为下载失败（找不到文件），
 * 也违背"重名就不要下"的预期。
 */
class FileConflictException(val fileName: String) :
    java.io.IOException("保存目录已存在同名文件：$fileName")

/**
 * 统一的文件写入封装，屏蔽两种保存位置的区别：
 *
 *  1. **内部目录**（默认）：`Android/data/com.flux.m3u8/files/<子目录>`
 *     —— 零权限，任何安卓版本都能写，卸载 App 时随应用删除。
 *     子目录可自定义，默认 `Movies`。
 *  2. **SAF 树目录**：用户在系统文件选择器里选的任意目录，
 *     授权后长期有效，可以写到公共「下载」「影片」目录或 SD 卡。
 *
 * 保存位置编码（`Settings.saveDir`）：
 *  · `"internal"`          → 内部默认子目录 Movies
 *  · `"internal:A/B"`      → 内部自定义子目录（A/B 会做安全化处理）
 *  · `content://...`       → SAF 树 URI
 */
class Storage(private val context: Context) {

    companion object {
        const val INTERNAL = "internal"
        const val INTERNAL_PREFIX = "internal:"
        const val DEFAULT_SUBDIR = "Movies"

        /** 把用户填的子目录编码成保存位置字符串。 */
        fun encodeInternal(subDir: String): String {
            val safe = safeRelativePath(subDir)
            return if (safe.isBlank()) INTERNAL else INTERNAL_PREFIX + safe
        }

        fun isInternal(dirUri: String): Boolean =
            dirUri.isBlank() || dirUri == INTERNAL || dirUri.startsWith(INTERNAL_PREFIX)

        /**
         * 系统文件选择器的「初始位置」提示。
         * 这些是主流 ROM 文件提供方的约定 URI，不保证所有设备都认，
         * 因此全部包在 runCatching 里用，失败就退回无提示的选择器。
         */
        fun presetTreeUri(kind: Preset): Uri? = runCatching {
            when (kind) {
                Preset.Downloads ->
                    Uri.parse("content://com.android.providers.downloads.documents/tree/downloads")
                Preset.Movies ->
                    Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMovies")
                Preset.Dcim ->
                    Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM")
                Preset.Root ->
                    Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A")
            }
        }.getOrNull()
    }

    enum class Preset { Downloads, Movies, Dcim, Root }

    // ──────────────── 路径解析 ────────────────

    /** 内部存储根目录：Android/data/<pkg>/files */
    fun internalRoot(): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    /** 把保存位置解析成实际目录（内部目录会自动创建）。 */
    fun internalDir(dirUri: String = INTERNAL): File {
        val root = internalRoot()
        val sub = if (dirUri.startsWith(INTERNAL_PREFIX)) {
            safeRelativePath(dirUri.removePrefix(INTERNAL_PREFIX))
        } else DEFAULT_SUBDIR
        val dir = if (sub.isBlank()) root else File(root, sub)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** UI 用：取出内部模式的子目录文本。 */
    fun subDirOf(dirUri: String): String =
        if (dirUri.startsWith(INTERNAL_PREFIX)) dirUri.removePrefix(INTERNAL_PREFIX)
        else DEFAULT_SUBDIR

    /** 把存储标识解析成可展示的路径文本。 */
    fun describe(dirUri: String): String {
        if (isInternal(dirUri)) return internalDir(dirUri).absolutePath
        return runCatching {
            val tree = DocumentFile.fromTreeUri(context, dirUri.toUri())
            val name = tree?.name
            when {
                !name.isNullOrBlank() -> name
                else -> readTreeDocId(dirUri)
            }
        }.getOrDefault("已选择目录")
    }

    private fun readTreeDocId(treeUri: String): String = try {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri.toUri())
        if (docId.startsWith("primary:")) "内部存储/" + docId.substringAfter("primary:")
        else docId.substringBefore(':') + "（外部存储）"
    } catch (e: Exception) {
        "已选择目录"
    }

    // ──────────────── 可用性校验 ────────────────

    /**
     * 检查目录是否真的能写。
     *
     * 为什么必须查：SAF 授权可能被系统回收（用户清了数据、卸载重装、ROM 清理），
     * 而授权失效只有在**最后写文件**那一刻才暴露——此时整个视频已经下完了，
     * 失败代价极高。这里用"建一个探针文件再删掉"提前验明正身。
     *
     * 注意：涉及文件 IO，不要在 UI 线程调用。
     */
    fun check(dirUri: String): DirCheck {
        return try {
            if (isInternal(dirUri)) checkInternal(dirUri) else checkSaf(dirUri)
        } catch (e: Exception) {
            DirCheck.Failed(e.message?.take(120) ?: "目录不可用")
        }
    }

    private fun checkInternal(dirUri: String): DirCheck {
        val dir = internalDir(dirUri)
        if (!dir.exists() && !dir.mkdirs()) return DirCheck.Failed("无法创建目录：${dir.absolutePath}")
        if (!dir.isDirectory) return DirCheck.Failed("目标不是目录：${dir.absolutePath}")
        if (!dir.canWrite()) return DirCheck.Failed("目录不可写：${dir.absolutePath}")
        return DirCheck.Ok(dir.absolutePath, freeBytes(dir))
    }

    private fun checkSaf(dirUri: String): DirCheck {
        if (!hasTreePermission(dirUri)) {
            return DirCheck.Failed("目录授权已失效，请重新选择")
        }
        val tree = DocumentFile.fromTreeUri(context, dirUri.toUri())
            ?: return DirCheck.Failed("无法访问已选择的目录，请重新选择")
        if (!tree.exists()) return DirCheck.Failed("所选目录不存在，请重新选择")
        if (!tree.canWrite()) return DirCheck.Failed("目录不可写，请重新选择")

        // 探针：真正建一个文件再删掉，避免 canWrite() 说谎
        val probeName = ".flux_probe_${System.currentTimeMillis()}"
        val probe = tree.createFile("application/octet-stream", probeName)
            ?: return DirCheck.Failed("目录不可写入，请重新选择")
        val created = probe.uri
        if (!probe.delete()) {
            runCatching { DocumentFile.fromSingleUri(context, created)?.delete() }
        }
        return DirCheck.Ok(describe(dirUri), freeBytes(internalRoot()))
    }

    /**
     * 取一个"当下真的能写"的目录。
     * 首选用户设置，不可用则回退内部默认目录——宁可存到内部，也不要下完才失败。
     */
    fun ensureUsable(dirUri: String): String {
        if (check(dirUri) is DirCheck.Ok) return dirUri
        val fallback = INTERNAL
        return if (check(fallback) is DirCheck.Ok) fallback else dirUri
    }

    private fun freeBytes(dir: File): Long = try {
        StatFs(dir.absolutePath).availableBytes
    } catch (e: Exception) {
        -1L
    }

    // ──────────────── 文件读写 ────────────────

    /** 创建文件的结果。 */
    data class CreatedFile(
        val stream: OutputStream,
        val name: String,
        val uri: String
    )

    /** 创建文件的结果 + 实际落盘目录（可能在创建失败时已回退）。 */
    data class CreateResult(
        val file: CreatedFile,
        val dirUri: String,
        val relocated: Boolean
    )

    /**
     * 在目标目录创建文件。
     *
     * 同名文件已存在时抛 [FileConflictException]——**不覆盖、也不自动改名**，
     * 由上层提示用户改名或换目录。提前用 [conflictOutput] 检查可以在下载前就拦住。
     */
    fun createFile(dirUri: String, fileName: String, mime: String = "video/mp4"): CreatedFile {
        if (exists(dirUri, fileName)) throw FileConflictException(fileName)
        return if (isInternal(dirUri)) {
            val dir = internalDir(dirUri)
            if (!dir.exists() && !dir.mkdirs()) {
                throw IllegalStateException("无法创建目录：${dir.absolutePath}")
            }
            val file = File(dir, fileName)
            CreatedFile(FileOutputStream(file), fileName, Uri.fromFile(file).toString())
        } else {
            val tree = DocumentFile.fromTreeUri(context, dirUri.toUri())
                ?: throw IllegalStateException("无法访问已选择的目录，请到设置里重新选择")
            // Android 11+ 的部分目录（如存储根目录、下载目录）会拒绝按特定 MIME 建文件，
            // 依次用「原 MIME → 通用 MIME → 无 MIME」重试，尽量把它建出来。
            val doc = tree.createFile(mime, fileName)
                ?: tree.createFile("application/octet-stream", fileName)
                ?: tree.createFile("*/*", fileName)
                ?: throw IllegalStateException(
                    "目录拒绝创建文件（Android 11+ 对部分公共目录有限制），请在设置中更换保存目录"
                )
            val os = context.contentResolver.openOutputStream(doc.uri)
                ?: throw IllegalStateException("无法打开文件输出流，请更换保存目录")
            CreatedFile(os, doc.name ?: fileName, doc.uri.toString())
        }
    }

    /**
     * 创建文件，若目标目录写不进去则**自动回退到内部目录**，绝不因为最后一步写盘失败而丢掉整个下载。
     *
     * 唯一例外是**同名冲突**：那属于用户要自己决策的情况（改名 or 换目录），
     * 必须原样抛出交给上层提示，不能偷偷换个目录把文件存下去。
     */
    fun createFileRelocating(
        dirUri: String,
        fileName: String,
        mime: String = "video/mp4"
    ): CreateResult {
        val first = runCatching { createFile(dirUri, fileName, mime) }
        first.getOrNull()?.let { return CreateResult(it, dirUri, false) }
        (first.exceptionOrNull() as? FileConflictException)?.let { throw it }

        if (!isInternal(dirUri)) {
            val fallback = runCatching { createFile(INTERNAL, fileName, mime) }
            fallback.getOrNull()?.let { return CreateResult(it, INTERNAL, true) }
            (fallback.exceptionOrNull() as? FileConflictException)?.let { throw it }
        }
        throw first.exceptionOrNull()
            ?: IllegalStateException("创建文件失败：$fileName")
    }

    fun exists(dirUri: String, fileName: String): Boolean {
        return if (isInternal(dirUri)) {
            File(internalDir(dirUri), fileName).exists()
        } else {
            val tree = DocumentFile.fromTreeUri(context, dirUri.toUri()) ?: return false
            tree.findFile(fileName) != null
        }
    }

    /**
     * 检查保存目录里是否已经有同名产物，有则返回那个文件名。
     *
     * 产物主文件按最终格式而定（转 MP4 是 `.mp4`，否则是单个 `.ts`），
     * 但任一格式已存在都算冲突——同一个任务只应该产出一份内容
     * （`.m3u8` 是旧版本产物，一并拦下）。
     * 用于"下载前先拦一道"，避免下完整个视频才发现重名。
     */
    fun conflictOutput(dirUri: String, baseName: String, toMp4: Boolean): String? {
        if (baseName.isBlank()) return null
        val names = if (toMp4) {
            listOf("$baseName.mp4", "$baseName.ts", "$baseName.m3u8")
        } else {
            listOf("$baseName.ts", "$baseName.mp4", "$baseName.m3u8")
        }
        return names.firstOrNull { exists(dirUri, it) }
    }

    /** 删除已写入的文件（任务被移除或合并失败回滚时用）。 */
    fun delete(dirUri: String, fileName: String): Boolean {
        return try {
            if (isInternal(dirUri)) {
                File(internalDir(dirUri), fileName).delete()
            } else {
                val tree = DocumentFile.fromTreeUri(context, dirUri.toUri()) ?: return false
                tree.findFile(fileName)?.delete() ?: false
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 删除指定 URI 的文件（SAF 精确删除，避免同名误删）。 */
    fun deleteUri(uri: String): Boolean = try {
        val parsed = uri.toUri()
        if (parsed.scheme == "file") {
            val path = parsed.path
            if (path == null) false else File(path).delete()
        } else {
            DocumentFile.fromSingleUri(context, parsed)?.delete() ?: false
        }
    } catch (e: Exception) {
        false
    }

    /**
     * 重命名已保存的文件（「防相册识别」的伪装/还原用，只改名不改内容）。
     *
     * 内部目录直接 `File.renameTo`（同分区，瞬时完成）；
     * SAF 目录优先用 [DocumentsContract.renameDocument]（文档提供方原生改名，不复制数据），
     * 个别提供方不支持改名时退回「新建目标 + 流复制 + 删旧文件」。
     *
     * @return 重命名后的文件 URI；失败返回 null
     */
    fun rename(dirUri: String, oldName: String, newName: String): String? {
        if (isInternal(dirUri)) {
            val dir = internalDir(dirUri)
            val old = File(dir, oldName)
            if (!old.exists()) return null
            val fresh = File(dir, newName)
            return if (old.renameTo(fresh)) Uri.fromFile(fresh).toString() else null
        }
        return renameSaf(dirUri, oldName, newName)
    }

    private fun renameSaf(dirUri: String, oldName: String, newName: String): String? {
        val tree = DocumentFile.fromTreeUri(context, dirUri.toUri()) ?: return null
        val doc = tree.findFile(oldName) ?: return null
        // 原生改名：文档 ID 在改名后通常不变，URI 依然有效；
        // 返回非 null 时以返回值为准，null 表示改名已生效只是没回传新 URI。
        val direct = runCatching {
            val r = DocumentsContract.renameDocument(context.contentResolver, doc.uri, newName)
            r?.toString() ?: doc.uri.toString()
        }.getOrNull()
        if (direct != null) return direct

        // 回退：新建目标文件 + 流复制 + 删旧文件（少量 ROM 的文件提供方不支持改名）
        return runCatching {
            val created = createFile(dirUri, newName)
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                created.stream.use { output -> input.copyTo(output) }
            }
            delete(dirUri, oldName)
            created.uri
        }.getOrNull()
    }

    // ──────────────── 临时文件 ────────────────

    /** 分片临时目录（始终放在应用私有目录，避免污染用户目录）。 */
    fun taskTempDir(taskId: String): File = TempCleaner.taskDir(context, taskId).apply {
        if (!exists()) mkdirs()
    }

    fun clearTemp(taskId: String) = TempCleaner.clearTask(context, taskId)

    /** 私有缓存剩余空间（合并/转换临时文件写在这里）。 */
    fun cacheFreeBytes(): Long = runCatching { context.cacheDir.usableSpace }
        .getOrDefault(Long.MAX_VALUE)

    /** 临时文件占用字节数（设置页展示用）。 */
    fun tempUsage(): Long = TempCleaner.sizeOf(context)

    // ──────────────── SAF 授权 ────────────────

    /** 释放 SAF 目录的长期授权。 */
    fun releaseTree(dirUri: String) {
        if (isInternal(dirUri)) return
        try {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(dirUri.toUri(), flags)
        } catch (e: Exception) {
            // 授权可能已过期，忽略
        }
    }

    /** 当前是否持有某个 SAF 目录的有效授权。 */
    fun hasTreePermission(dirUri: String): Boolean {
        if (isInternal(dirUri)) return true
        return try {
            context.contentResolver.persistedUriPermissions.any {
                it.uri.toString() == dirUri && it.isWritePermission
            }
        } catch (e: Exception) {
            false
        }
    }
}
