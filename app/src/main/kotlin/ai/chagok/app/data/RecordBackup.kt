package ai.chagok.app.data

import ai.chagok.app.AppController
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract as Docs
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/** Folder grants and maintenance state belong to this installation, not to its restored DB. */
class RecordBackup(private val context: Context, private val c: AppController, private val changed: () -> Unit) {
    private val prefs = context.getSharedPreferences("record_backup", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var observed = -1L
    private var saved = -1L
    private var filesRevision = 0L
    private var displayedSaleState = saleState
    var busy = false
        private set
    var message = ""
        private set
    val saleState: String get() = prefs.getString("sale", "").orEmpty()
    val folder: String get() = prefs.getString("folder", "").orEmpty()
    val lastSuccess: Long get() = prefs.getLong("last_success", 0)
    val pending: Boolean get() = observed != saved
    val restoreFolderSelected: Boolean get() = prefs.getString("restore_folder", "").orEmpty().isNotEmpty()
    /** 운행 삭제 뒤 사본 파기가 끝나지 않았다. 끝날 때까지 `삭제 미완료`로 표시하고 이전 백업 세대를 남기지 않는다 */
    val purgePending: Boolean get() = prefs.getBoolean("purge_pending", false)
    val enabled: Boolean get() = folder.isNotEmpty() && prefs.getBoolean("active", false)

    private fun roots() = JSONArray(prefs.getString("roots", "[]"))
    private fun files(): JSONArray = distinctFiles(JSONArray(prefs.getString("files", "[]")))
    private fun distinctFiles(input: JSONArray): JSONArray {
        val byUri = linkedMapOf<String, JSONObject>()
        for (i in 0 until input.length()) input.getJSONObject(i).let { byUri[it.getString("uri")] = it }
        return JSONArray(byUri.values.toList())
    }
    private fun revision(): Long = c.db.revision.get() + filesRevision
    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            if (displayedSaleState != saleState) { displayedSaleState = saleState; changed() }
            if (saleState.isEmpty() && c.dataSuspended && !operationRunning && prefs.getBoolean("restoring", false)) {
                prefs.edit().putBoolean("restoring", false).commit()
                c.resumeRecords()
                update("이전 복구 작업이 중단되었습니다. 현재 기록을 확인하고 필요하면 다시 복구하세요")
            }
            if (saleState.isEmpty() && !c.dataSuspended) {
                val r = revision()
                if (r != observed) { observed = r; changed() }
                if (enabled && observed != saved && !busy && !message.startsWith("백업 실패")) backup()
            }
            main.postDelayed(this, 2_000)
        }
    }

    fun start() { main.post(poll); if (saleState == "pending") deleteForSale() else if (purgePending) purgeCopies() }
    fun close() {
        if (enabled && saleState.isEmpty() && !c.dataSuspended) {
            // Capture after controller.stop() committed the final trip; shared IO orders it before any sale job.
            runCatching { RecordSnapshot.create(c.db, files(), roots()) }.onSuccess { bytes ->
                val tree = Uri.parse(folder)
                io.execute { runCatching { writeGeneration(tree, bytes) }.onSuccess {
                    prefs.edit().putLong("last_success", System.currentTimeMillis()).commit()
                } }
            }
        }
        closed = true; main.removeCallbacks(poll)
    }
    private fun update(text: String = "") { message = text; if (!closed) changed() }

    fun selectFolder(uri: Uri, forSale: Boolean, activate: Boolean = true) {
        require(uri.authority == "com.android.externalstorage.documents") { "기기 내 저장소 폴더를 선택하세요" }
        val id = Docs.getTreeDocumentId(uri)
        require(':' in id && id.substringAfter(':').isNotBlank()) { "전용 하위 폴더를 선택하세요" }
        require(!id.substringAfter(':').equals("Android", true) && !id.substringAfter(':').startsWith("Android/", true)) { "앱 전용 폴더는 백업에 사용할 수 없습니다" }
        val list = roots()
        if ((0 until list.length()).none { list.getString(it) == uri.toString() }) list.put(uri.toString())
        prefs.edit().putString("roots", list.toString()).apply()
        if (!forSale) {
            require(saleState.isEmpty()) { "매각 삭제를 먼저 완료하세요" }
            if (activate) {
                prefs.edit().putString("folder", uri.toString()).putBoolean("active", true).apply()
                saved = -1
            } else prefs.edit().putString("restore_folder", uri.toString()).apply()
        }
        update()
    }

    data class Document(val uri: Uri, val name: String, val modified: Long = 0)
    private fun documents(tree: Uri): List<Document> {
        if (tree.scheme == "file") return (java.io.File(tree.path!!).listFiles() ?: error("폴더를 읽을 수 없습니다"))
            .map { Document(Uri.fromFile(it), it.name, it.lastModified()) }
        val root = Docs.getTreeDocumentId(tree)
        val children = Docs.buildChildDocumentsUriUsingTree(tree, root)
        return resolver.query(children, arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(Document(Docs.buildDocumentUriUsingTree(tree, cursor.getString(0)), cursor.getString(1), cursor.getLong(2))) }
        } ?: error("폴더를 읽을 수 없습니다. 다시 접근을 허용하세요")
    }
    private fun read(uri: Uri): ByteArray = (resolver.openInputStream(uri) ?: error("파일을 열 수 없습니다")).use {
        val bytes = it.readBytesLimited(RecordSnapshot.MAX_BYTES / 3 * 4 + 65536)
        bytes
    }
    private fun create(tree: Uri, name: String, bytes: ByteArray): Uri {
        if (tree.scheme == "file") return Uri.fromFile(java.io.File(tree.path!!, name).apply { writeBytes(bytes) })
        val root = Docs.buildDocumentUriUsingTree(tree, Docs.getTreeDocumentId(tree))
        val uri = Docs.createDocument(resolver, root, "application/octet-stream", name) ?: error("백업 파일 생성 실패")
        (resolver.openOutputStream(uri, "wt") ?: error("백업 파일 열기 실패")).use { it.write(bytes) }
        return uri
    }

    private fun isDoc(uri: Uri) = uri.scheme == "file" || Docs.isDocumentUri(context, uri)
    private fun deleteDoc(uri: Uri): Boolean =
        if (uri.scheme == "file") java.io.File(uri.path!!).let { !it.exists() || it.delete() } else Docs.deleteDocument(resolver, uri)

    /**
     * 데모 빌드 전용 테스트 경로: 에뮬레이터에 문서 선택기가 없어 고정 폴더([demoDir])를 백업·복구 폴더로 쓴다.
     * 앱을 지워도 남도록 공용 저장소에 두며 '모든 파일 접근'(appops MANAGE_EXTERNAL_STORAGE)이 필요하다. 실제 빌드는 쓰지 않는다
     */
    fun selectDemoFolder(forRestore: Boolean) {
        require(ai.chagok.app.BuildConfig.DEMO) { "데모 빌드 전용입니다" }
        require(saleState.isEmpty()) { "매각 삭제를 먼저 완료하세요" }
        require(android.os.Environment.isExternalStorageManager()) { "모든 파일 접근을 허용해야 합니다 (adb: appops set <패키지> MANAGE_EXTERNAL_STORAGE allow)" }
        val dir = demoDir().apply { mkdirs() }
        require(dir.isDirectory) { "데모 폴더를 만들 수 없습니다" }
        val uri = Uri.fromFile(dir).toString()
        val list = roots()
        if ((0 until list.length()).none { list.getString(it) == uri }) list.put(uri)
        prefs.edit().putString("roots", list.toString()).apply()
        if (forRestore) prefs.edit().putString("restore_folder", uri).apply()
        else { prefs.edit().putString("folder", uri).putBoolean("active", true).apply(); saved = -1 }
        update()
    }

    fun track(uri: Uri, name: String) {
        val all = files()
        all.put(JSONObject().put("uri", uri.toString()).put("name", name))
        prefs.edit().putString("files", distinctFiles(all).toString()).apply()
        filesRevision++
    }

    fun backup() {
        if (closed || busy || operationRunning || !enabled || c.dataSuspended || saleState.isNotEmpty()) return
        busy = true
        update()
        val r = revision()
        val bytes = try { RecordSnapshot.create(c.db, files(), roots()) } catch (e: Exception) {
            busy = false; update("백업 실패: ${safeError(e)}"); return
        }
        val tree = Uri.parse(folder)
        io.execute {
            val result = runCatching {
                writeGeneration(tree, bytes, keepPrevious = !purgePending)
            }
            main.post {
                busy = false
                if (result.isSuccess) {
                    saved = r
                    if (saleState.isEmpty()) prefs.edit().putLong("last_success", System.currentTimeMillis()).apply()
                    update()
                } else update("백업 실패: ${safeError(result.exceptionOrNull())}")
            }
        }
    }

    private fun writeGeneration(tree: Uri, plainBytes: ByteArray, keepPrevious: Boolean = true) {
                val keys = c.admin.backupKeys
                val bytes = BackupCrypto.seal(plainBytes, keys)
                val previousDocs = documents(tree)
                // 이전 세대로는 현재 PIN·복구 코드 키로 만든 암호화 백업만 남긴다. 평문·이전 키 세대는 새 세대 기록 뒤 정리된다
                val previous = previousDocs.filter { it.name.matches(BACKUP_NAME) }
                    .sortedByDescending { it.modified }
                    .firstOrNull { f -> previousDocs.firstOrNull { it.name == "${f.name}.done" }?.let { marker ->
                        runCatching {
                            val b = read(f.uri)
                            RecordSnapshot.digest(b) == String(read(marker.uri), Charsets.UTF_8) &&
                                BackupCrypto.isEnvelope(b) && BackupCrypto.sameKeys(BackupCrypto.header(b), keys)
                        }.getOrDefault(false)
                    } == true }
                val name = "popolog-backup-${UUID.randomUUID()}.json"
                val uri = create(tree, name, bytes)
                val written = read(uri)
                require(written.contentEquals(bytes)) { "백업 쓰기 검증 실패" }
                require(BackupCrypto.open(written, keys, null).contentEquals(plainBytes)) { "백업 암호화 검증 실패" }
                RecordSnapshot.decode(plainBytes)
                val marker = RecordSnapshot.digest(bytes).toByteArray(Charsets.UTF_8)
                val done = create(tree, "$name.done", marker)
                require(read(done).contentEquals(marker)) { "완료 표시 검증 실패" }
                // Keep the new generation and the previous valid one; never prune before the commit marker.
                previousDocs.filter { it.name.removeSuffix(".done").matches(BACKUP_NAME) &&
                    it.name.removeSuffix(".done") != previous?.name.takeIf { keepPrevious } }.forEach { old ->
                    if (!deleteDoc(old.uri)) error("이전 백업 정리 실패")
                }
                // 데모 시험 확인용: 백업 폴더에 남은 파일 목록
                if (ai.chagok.app.BuildConfig.DEMO) android.util.Log.i("Popolog", "backup folder: ${documents(tree).map { it.name.takeLast(14) }.sorted()}")
    }

    /**
     * 미제출 운행 단건 삭제 뒤, 앱이 관리하는 사본에서 삭제한 원문을 파기한다.
     * 백업은 새 세대를 쓴 뒤 이전 세대를 모두 지우고, 앱이 만든 내보내기 파일은 제출 PDF만 남기고 지운다.
     * 하나라도 실패하면 `삭제 미완료`로 남기고 다시 시도할 수 있다. 외부로 복사된 사본은 지울 수 없다
     */
    fun purgeCopies(onDone: (String) -> Unit = {}) {
        if (busy || operationRunning || saleState.isNotEmpty() || c.dataSuspended) { onDone("진행 중인 작업이 끝난 뒤 다시 시도하세요"); return }
        if (!prefs.edit().putBoolean("purge_pending", true).commit()) { onDone("삭제 상태 저장 실패"); return }
        val keep = c.submissions.map { it.pdfName }.filter { it.isNotEmpty() }.toSet()
        val bytes = if (enabled) try { RecordSnapshot.create(c.db, files(), roots()) } catch (e: Exception) {
            update("삭제 미완료: ${safeError(e)}"); onDone("삭제 미완료: ${safeError(e)}"); return
        } else null
        operationRunning = true
        busy = true; update()
        val r = revision()
        io.execute {
            val failures = mutableListOf<String>()
            if (bytes != null) runCatching {
                val current = Uri.parse(folder)
                writeGeneration(current, bytes, keepPrevious = false)
                // 이전에 쓴 다른 백업 폴더의 옛 세대도 지운다 (삭제 전 원문이 들어 있다)
                val all = roots()
                for (i in 0 until all.length()) {
                    val tree = Uri.parse(all.getString(i))
                    if (tree == current) continue
                    runCatching { documents(tree).filter { it.name.removeSuffix(".done").matches(BACKUP_NAME) }.forEach {
                        require(deleteDoc(it.uri)) { "삭제 실패" }
                    } }.onFailure { failures += "이전 백업 폴더 정리 실패: $tree" }
                }
            }.onFailure { failures += "백업 갱신 실패: ${safeError(it)}" }
            val targets = linkedMapOf<String, Document>()
            val known = files()
            for (i in 0 until known.length()) {
                val f = known.getJSONObject(i)
                targets[f.getString("uri")] = Document(Uri.parse(f.getString("uri")), f.getString("name"))
            }
            runCatching {
                resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    // 이 앱이 만든 파일만. 다른 앱·이전 설치가 같은 폴더에 둔 파일은 건드리지 않는다
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
                    arrayOf("Download/Popolog/", context.packageName), null)?.use { cur ->
                    while (cur.moveToNext()) if (cur.getString(1).matches(EXPORT_NAME)) {
                        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cur.getLong(0))
                        targets[uri.toString()] = Document(uri, cur.getString(1))
                    }
                } ?: error("다운로드 목록 접근 실패")
            }.onFailure { failures += "다운로드 파일 목록 접근 실패" }
            val removed = mutableSetOf<String>()
            for ((key, f) in targets) {
                if (f.name in keep) continue
                runCatching {
                    if (isDoc(f.uri)) require(deleteDoc(f.uri)) { "삭제 실패" }
                    else resolver.query(f.uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { cur ->
                        // 이미 없으면 지운 것으로 본다. 다른 파일이 같은 id 를 쓰고 있으면 지우지 않는다
                        if (cur.moveToFirst()) {
                            require(cur.getString(0) == f.name && cur.getString(1).trimEnd('/') == "Download/Popolog") { "파일 식별 불일치" }
                            require(resolver.delete(f.uri, null, null) > 0) { "삭제 실패" }
                        }
                    } ?: error("파일 접근 확인 실패")
                    removed += key
                }.onFailure { failures += "내보내기 파일 삭제 실패: ${f.name}" }
            }
            if (ai.chagok.app.BuildConfig.DEMO) android.util.Log.i("Popolog", "purge: removed ${removed.size} exports, failures=${failures.distinct()}")
            val remaining = JSONArray(files().let { all -> (0 until all.length()).map { all.getJSONObject(it) }.filter { it.getString("uri") !in removed } })
            val kept = prefs.edit().putString("files", remaining.toString())
            if (failures.isEmpty()) kept.putBoolean("purge_pending", false)
            kept.commit()
            main.post {
                operationRunning = false
                busy = false
                if (failures.isEmpty()) saved = r
                val text = if (failures.isEmpty()) "원문 파기 완료 · 백업과 앱이 만든 내보내기 사본을 정리했어요. 외부로 복사한 파일은 직접 관리해 주세요"
                else "삭제 미완료\n${failures.distinct().joinToString("\n")}\n폴더 접근을 확인한 뒤 다시 시도하세요"
                update(text)
                onDone(text)
            }
        }
    }

    fun retry() { update(); saved = -1; backup() }

    fun candidates(onResult: (List<Document>) -> Unit) {
        val selected = prefs.getString("restore_folder", folder).orEmpty()
        if (busy || selected.isEmpty() || saleState.isNotEmpty()) return
        busy = true; update()
        val tree = Uri.parse(selected)
        io.execute {
            val result = runCatching {
                val docs = documents(tree)
                docs.filter { it.name.matches(BACKUP_NAME) }.filter { file ->
                    val marker = docs.firstOrNull { it.name == "${file.name}.done" } ?: return@filter false
                    runCatching { RecordSnapshot.digest(read(file.uri)) == String(read(marker.uri), Charsets.UTF_8) }.getOrDefault(false)
                }.sortedByDescending { file ->
                    runCatching { JSONObject(String(read(file.uri), Charsets.UTF_8)).getLong("createdAt") }.getOrDefault(0)
                }
            }
            main.post {
                busy = false
                if (closed) return@post
                if (result.isSuccess) { update(); onResult(result.getOrThrow()) }
                else update("백업 목록 읽기 실패: ${safeError(result.exceptionOrNull())}")
            }
        }
    }

    fun restore(document: Document, secret: String? = null, onSecretNeeded: () -> Unit = {}) {
        require(c.canManage) { "관리자 잠금을 해제한 뒤 다시 복구하세요" }
        if (busy || operationRunning || saleState.isNotEmpty() || c.isDriving) return
        require(prefs.edit().putBoolean("restoring", true).commit()) { "복구 상태 저장 실패" }
        operationRunning = true
        busy = true
        c.pauseRecords()
        update()
        io.execute {
            val result = runCatching {
                val raw = read(document.uri)
                val bytes = if (BackupCrypto.isEnvelope(raw)) BackupCrypto.open(raw, c.admin.backupKeys, secret) else raw
                val decoded = RecordSnapshot.decode(bytes)
                val manifest = decoded.getJSONArray("files")
                val folders = decoded.getJSONArray("folders")
                val mergedRoots = roots()
                validateManifest(manifest, folders)
                for (i in 0 until folders.length()) {
                    val uri = folders.getString(i)
                    if ((0 until mergedRoots.length()).none { mergedRoots.getString(it) == uri }) mergedRoots.put(uri)
                }
                val previousFiles = files().toString()
                val previousRoots = roots().toString()
                val merged = JSONArray(previousFiles)
                for (i in 0 until manifest.length()) merged.put(manifest.getJSONObject(i))
                require(prefs.edit().putString("files", distinctFiles(merged).toString()).putString("roots", mergedRoots.toString()).commit()) { "파일 목록 저장 실패" }
                try { RecordSnapshot.restore(c.db, bytes) }
                catch (e: Exception) {
                    prefs.edit().putString("files", previousFiles).putString("roots", previousRoots).commit()
                    throw e
                }
            }
            main.post {
                operationRunning = false
                busy = false
                saved = -1
                if (result.isSuccess) prefs.edit().putBoolean("active", true)
                    .putString("folder", prefs.getString("restore_folder", folder)).apply()
                if (!closed) {
                    prefs.edit().putBoolean("restoring", false).commit()
                    c.resumeRecords()
                }
                if (result.exceptionOrNull() is SecretRequired) { update(); onSecretNeeded() }
                else update(if (result.isSuccess) "복구 완료" else "복구 실패: ${safeError(result.exceptionOrNull())}")
            }
        }
    }

    private fun validateManifest(files: JSONArray, folders: JSONArray) {
        require(files.length() <= 250_000 && folders.length() <= 256) { "관리 파일 목록 한도 초과" }
        for (i in 0 until files.length()) {
            val item = files.getJSONObject(i)
            require(item.getString("name").matches(EXPORT_NAME)) { "내보내기 파일 이름 불일치" }
            val uri = Uri.parse(item.getString("uri"))
            require(uri.scheme == "content" && uri.authority == "media" && uri.pathSegments.let { it.size == 3 && it[1] == "downloads" && it.last().toLongOrNull() != null }) { "내보내기 파일 주소 불일치" }
        }
        for (i in 0 until folders.length()) {
            val tree = Uri.parse(folders.getString(i))
            // 데모 테스트 경로만 file 주소를 허용한다
            if (ai.chagok.app.BuildConfig.DEMO && tree == Uri.fromFile(demoDir())) continue
            require(tree.scheme == "content" && tree.authority == "com.android.externalstorage.documents" && Docs.isTreeUri(tree)) { "백업 폴더 형식 불일치" }
            val path = Docs.getTreeDocumentId(tree).substringAfter(':', "")
            require(path.isNotBlank() && path != "Android" && !path.startsWith("Android/", true)) { "백업 폴더 범위 불일치" }
        }
    }


    // ---- 매각 전 차량 밖 저장 ----

    /** 차량 밖 저장 확인 시점의 기록 상태 표식. 이후 기록이 바뀌면 달라져 다시 저장해야 한다 */
    private fun stateToken(): String {
        val l = c.db.ledger()
        return "${l.size}|${l.lastOrNull()?.hash}|${c.db.trips().size}|${c.db.expenses().size}|${c.db.anchors().size}|${files().length()}"
    }

    val offsiteVerified: Boolean get() = prefs.getString("offsite_token", null).let { it != null && it == stateToken() }
    val offsiteAt: Long get() = prefs.getLong("offsite_at", 0)

    /** 선택한 폴더가 USB·SD 카드 같은 분리 가능한 저장소인가. 기기 내부 저장소는 허용하지 않는다 */
    private fun requireRemovable(tree: Uri) {
        require(tree.authority == "com.android.externalstorage.documents") { "USB 등 차량 밖 저장소의 폴더를 선택하세요" }
        val volume = Docs.getTreeDocumentId(tree).substringBefore(':')
        require(volume.isNotEmpty() && !volume.equals("primary", true) && !volume.equals("home", true)) { "차량 내부 저장소는 사용할 수 없습니다. USB 등 차량 밖 저장소를 선택하세요" }
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        val v = sm.storageVolumes.firstOrNull { it.uuid.equals(volume, true) }
        require(v != null && v.isRemovable) { "분리할 수 있는 저장소(USB·SD 카드)인지 확인하지 못했습니다" }
    }

    /**
     * 보존 중인 내보내기 파일(제출 PDF·CSV·검증 JSON)과 암호화한 운행·비용 기록 사본을 차량 밖 저장소에 저장하고,
     * 다시 읽어 해시가 같은지 확인한다. 하나라도 어긋나면 완료로 표시하지 않아 매각 삭제가 열리지 않는다
     */
    fun saveOutsideVehicle(tree: Uri, onDone: (String) -> Unit) {
        if (closed || busy || operationRunning || c.dataSuspended || saleState.isNotEmpty()) { onDone("진행 중인 작업이 끝난 뒤 다시 시도하세요"); return }
        try { requireRemovable(tree) } catch (e: Exception) { onDone(e.message ?: "저장소를 확인하지 못했습니다"); return }
        val token = stateToken()
        val snapshot = try { BackupCrypto.seal(RecordSnapshot.create(c.db, files(), roots()), c.admin.backupKeys) } catch (e: Exception) {
            onDone("저장 실패: ${safeError(e)}"); return
        }
        val known = files()
        busy = true; update()
        io.execute {
            val failures = mutableListOf<String>()
            var saved = 0
            var gone = 0
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
            fun copy(name: String, bytes: ByteArray) {
                runCatching {
                    val uri = create(tree, name, bytes)
                    require(RecordSnapshot.digest(read(uri)) == RecordSnapshot.digest(bytes)) { "재읽기 불일치" }
                    saved++
                }.onFailure { failures += "$name: ${safeError(it)}" }
            }
            copy("popolog-offsite-$stamp-records.json", snapshot)
            for (i in 0 until known.length()) {
                val f = known.getJSONObject(i)
                val bytes = try { read(Uri.parse(f.getString("uri"))) } catch (_: java.io.FileNotFoundException) { gone++; continue }
                  catch (e: Exception) { failures += "${f.getString("name")}: 읽기 실패"; continue }
                copy("popolog-offsite-$stamp-${f.getString("name")}", bytes)
            }
            main.post {
                busy = false
                val text = if (failures.isEmpty()) {
                    // 저장하는 동안 기록이 바뀌었으면 확인으로 인정하지 않는다
                    if (token == stateToken()) {
                        prefs.edit().putString("offsite_token", token).putLong("offsite_at", System.currentTimeMillis()).commit()
                        "차량 밖 저장 완료 · ${saved}개 확인${if (gone > 0) " (이미 없는 내보내기 파일 ${gone}개 제외)" else ""}"
                    } else "저장하는 동안 기록이 바뀌었습니다. 다시 저장하세요"
                } else "저장 미완료\n${failures.joinToString("\n")}"
                update(text); onDone(text)
            }
        }
    }

    fun deleteForSale() {
        if (busy || operationRunning || saleState == "done") return
        require(!c.isDriving) { "정차 후 삭제하세요" }
        // A pending deletion already carries persisted authorization across process restarts.
        if (saleState.isEmpty()) require(c.canManage && c.admin.recentlyVerified()) { "관리자 PIN을 다시 확인하세요" }
        if (saleState.isEmpty()) require(offsiteVerified) { "먼저 USB 등 차량 밖 저장소에 보존 자료를 저장하세요" }
        // Persist before stopping any source: restart resumes this mode without starting a gateway.
        require(prefs.edit().putString("sale", "pending").commit()) { "삭제 상태 저장 실패" }
        c.pauseRecords()
        operationRunning = true
        busy = true; update()
        io.execute {
            val failures = mutableListOf<String>()
            val targets = linkedMapOf<String, Document>()
            val known = files()
            for (i in 0 until known.length()) {
                val f = known.getJSONObject(i)
                targets[f.getString("uri")] = Document(Uri.parse(f.getString("uri")), f.getString("name"))
            }
            val allRoots = roots()
            val discoveredFiles = files()
            val foundNames = mutableSetOf<String>()
            var exportFolderScanned = false
            var rootIndex = 0
            while (rootIndex < allRoots.length()) {
                val tree = Uri.parse(allRoots.getString(rootIndex++))
                runCatching {
                    val contents = documents(tree)
                    for (snapshot in contents.filter { it.name.matches(BACKUP_NAME) }) {
                        runCatching {
                            val raw = read(snapshot.uri)
                            val manifest = if (BackupCrypto.isEnvelope(raw)) BackupCrypto.header(raw) else RecordSnapshot.decode(raw, checkMode = false)
                            val fs = manifest.getJSONArray("files")
                            val folders = manifest.getJSONArray("folders")
                            validateManifest(fs, folders)
                            for (j in 0 until fs.length()) {
                                val file = fs.getJSONObject(j)
                                discoveredFiles.put(file)
                                targets[file.getString("uri")] = Document(Uri.parse(file.getString("uri")), file.getString("name"))
                            }
                            for (j in 0 until folders.length()) {
                                val uri = folders.getString(j)
                                if ((0 until allRoots.length()).none { allRoots.getString(it) == uri }) {
                                    require(allRoots.length() < 256) { "관리 폴더 수 한도 초과" }
                                    allRoots.put(uri)
                                }
                            }
                        }.onFailure {
                            // Incomplete generations have no completion marker and cannot add recovery locations.
                            if (contents.any { it.name == "${snapshot.name}.done" }) failures += "백업 삭제 목록 읽기 실패: ${snapshot.name}"
                        }
                    }
                    val isExportFolder = tree.scheme != "file" && Docs.getTreeDocumentId(tree).substringAfter(':').trimEnd('/') == "Download/Popolog"
                    if (isExportFolder) exportFolderScanned = true
                    contents.filter { it.name.removeSuffix(".done").matches(BACKUP_NAME) ||
                        (isExportFolder && it.name.matches(EXPORT_NAME)) }.forEach { f ->
                        foundNames += f.name
                        targets[f.uri.toString()] = f
                    }
                }.onFailure { failures += "폴더 접근 권한을 다시 허용하세요: $tree" }
            }
            if (!exportFolderScanned) failures += "Download/Popolog 폴더 접근을 허용하세요. 폴더가 없으면 선택기에서 생성하세요"
            if (!prefs.edit().putString("roots", allRoots.toString()).putString("files", distinctFiles(discoveredFiles).toString()).commit()) failures += "삭제 대상 목록 저장 실패"
            // Include exports from before backup was enabled, when still owned by this installation.
            runCatching {
                resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf("Download/Popolog/"), null)?.use { cur ->
                    while (cur.moveToNext()) if (cur.getString(1).matches(EXPORT_NAME)) {
                        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cur.getLong(0))
                        targets[uri.toString()] = Document(uri, cur.getString(1))
                    }
                } ?: error("다운로드 목록 접근 실패")
            }.onFailure { failures += "다운로드 파일 접근 권한을 다시 허용하세요" }
            // Do not destroy a backup containing undiscovered locations when preflight access/manifest checks failed.
            if (failures.isEmpty()) for ((_, f) in targets) {
                // A document and a MediaStore row may be two addresses of the same export.
                if (f.uri.authority == "media" && targets.values.any { it.name == f.name && isDoc(it.uri) }) continue
                runCatching {
                    if (isDoc(f.uri)) {
                        require(deleteDoc(f.uri)) { "파일 삭제 실패" }
                    } else {
                        // Never delete a reused MediaStore id unless both path and name still match.
                        resolver.query(f.uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { cur ->
                            if (cur.moveToFirst()) {
                                require(cur.getString(0) == f.name && cur.getString(1).trimEnd('/') == "Download/Popolog") { "파일 식별 불일치" }
                                require(resolver.delete(f.uri, null, null) > 0) { "파일 삭제 실패" }
                            } else require(exportFolderScanned) { "이전 설치 파일 확인을 위해 Download/Popolog 폴더 접근을 허용하세요" }
                        } ?: error("파일 접근 확인 실패")
                    }
                }.onFailure {
                    // Access via a newly granted tree can delete a previous install's export.
                    val confirmedViaFolder = exportFolderScanned && (f.name !in foundNames ||
                        targets.values.any { it.name == f.name && isDoc(it.uri) })
                    if (!(f.uri.authority == "media" && confirmedViaFolder)) failures += f.name
                }
            }
            if (failures.isEmpty()) runCatching {
                c.admin.clearAfterSale()
                c.db.clearAll()
                c.db.close()
                require(context.deleteDatabase("chagok.db")) { "데이터베이스 파일 삭제 실패" }
                DbCrypto.forget(context)
                val dirs = listOfNotNull(context.filesDir, context.cacheDir, context.codeCacheDir, context.getExternalFilesDir(null), context.externalCacheDir)
                dirs.forEach { dir -> dir.listFiles()?.forEach { require(it.deleteRecursively()) { "앱 파일 삭제 실패" } } }
            }.onFailure { failures += "앱 내부 데이터 정리 실패" }
            if (failures.isEmpty() && !prefs.edit().clear().putString("sale", "done").commit()) {
                failures += "삭제 완료 상태 저장 실패"
                prefs.edit().putString("sale", "pending").putString("roots", allRoots.toString())
                    .putString("files", distinctFiles(discoveredFiles).toString()).commit()
            }
            val result = failures.distinct().joinToString("\n")
            if (failures.isNotEmpty()) prefs.edit().putString("failures", result).commit()
            main.post {
                operationRunning = false
                busy = false
                if (!closed) c.reload()
                update(if (failures.isEmpty()) "매각 삭제 완료" else "전체 삭제 미완료\n$result\n폴더 접근을 다시 허용한 뒤 재시도하세요")
            }
        }
    }

    fun beginNewUse() {
        if (busy || operationRunning || saleState != "done") return
        if (!prefs.edit().clear().commit()) { update("새 사용 상태 저장 실패. 다시 시도하세요"); return }
        observed = -1; saved = -1
        c.resumeRecords()
        update()
    }

    companion object {
        // Single queue across Activity instances: an old backup cannot race a new restore or sale deletion.
        private val io = Executors.newSingleThreadExecutor()
        /** 데모 테스트 경로: 현재 사용자의 공용 저장소 아래 고정 폴더 (앱을 지워도 남는다) */
        fun demoDir() = java.io.File(android.os.Environment.getExternalStorageDirectory(), "Popolog-demo-backup")
        @Volatile private var operationRunning = false
        private val BACKUP_NAME = Regex("popolog-backup-[0-9a-f-]{36}\\.json")
        private val EXPORT_NAME = Regex("(?:운행기록부|차량비용|검증파일)_\\d{4}_[^/]+\\.(?:pdf|csv|json)")
        private fun safeError(e: Throwable?) = when (e) {
            is SecurityException -> "폴더 접근 권한이 필요합니다"
            else -> e?.message?.take(160) ?: "저장소 작업 오류"
        }
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        require(out.size() + n <= limit) { "백업 용량 한도 초과" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
