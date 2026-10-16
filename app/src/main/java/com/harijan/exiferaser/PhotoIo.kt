package com.harijan.exiferaser

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 사진 읽기(회전 보정 디코딩) / 무손실 메타 제거 / 갤러리·지정 폴더 저장 공용 로직
 * 단일 편집 화면과 일괄 처리에서 함께 사용
 */
object PhotoIo {

    /** 위치정보가 가려지지 않은 원본 접근 (권한이 없으면 원래 URI 그대로) */
    fun resolveOriginal(uri: Uri): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == MediaStore.AUTHORITY) {
            return try { MediaStore.setRequireOriginal(uri) } catch (_: Exception) { uri }
        }
        return uri
    }

    fun openStream(context: Context, uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            try {
                val path = uri.path
                if (path != null && File(path).exists()) FileInputStream(File(path)) else null
            } catch (_: Exception) {
                null
            }
        }
    }

    fun readBytes(context: Context, uri: Uri): ByteArray? =
        openStream(context, uri)?.use { it.readBytes() }

    fun readOrientation(context: Context, uri: Uri): Int = try {
        openStream(context, uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** EXIF 방향값 8가지(회전 + 좌우/상하 반전) 모두에 대응하는 보정 행렬 */
    fun orientationMatrix(orientation: Int): Matrix? = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setScale(1f, -1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { setRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { setRotate(-90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f) }
        else -> null
    }

    /** 메모리 한도 내에서 디코딩하고 EXIF 방향대로 바로 세운 비트맵 */
    fun decodeUpright(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDim || bounds.outHeight / sampleSize > maxDim) {
            sampleSize *= 2
        }
        val raw = openStream(context, uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: throw IllegalStateException("이미지를 디코딩할 수 없습니다.")

        val matrix = orientationMatrix(readOrientation(context, uri)) ?: return raw
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (upright != raw) raw.recycle()
        return upright
    }

    fun maxDecodeDim(): Int {
        val maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        return if (maxMemoryMb >= 256) 6144 else 4096
    }

    /**
     * 원본 파일 바이트에서 메타데이터만 무손실로 제거
     * 결과를 다시 검사해 GPS가 남아 있으면 실패(null)로 처리 → 호출 측이 재인코딩으로 대체
     */
    fun stripLossless(bytes: ByteArray): ByteArray? {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val cleaned = MetadataStripper.strip(bytes, orientation) ?: return null
        return try {
            val check = ExifInterface(ByteArrayInputStream(cleaned))
            if (check.latLong != null || check.getAttribute(ExifInterface.TAG_MAKE) != null) null else cleaned
        } catch (_: Exception) {
            cleaned
        }
    }

    /**
     * 사용자가 지정한 SAF 폴더 → 실패 시 Pictures/<폴더명> 순서로 저장
     * @return 저장된 파일 URI, 실패 시 null
     */
    fun save(
        context: Context,
        filename: String,
        mime: String,
        folderName: String,
        safTreeUri: String?,
        write: (OutputStream) -> Unit
    ): Uri? {
        val resolver = context.contentResolver

        if (safTreeUri != null) {
            try {
                val treeUri = Uri.parse(safTreeUri)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                )
                val newFileUri = DocumentsContract.createDocument(resolver, docUri, mime, filename)
                if (newFileUri != null) {
                    resolver.openOutputStream(newFileUri)?.use(write)
                    return newFileUri
                }
            } catch (_: Exception) {
                // SAF 실패 시 MediaStore로 폴백
            }
        }

        val folder = folderName.ifEmpty { "PrivacyEraser" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/$folder")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            return try {
                resolver.openOutputStream(uri)?.use(write) ?: throw IllegalStateException()
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (_: Exception) {
                resolver.delete(uri, null, null)
                null
            }
        }

        // Android 9 이하 (SDK 26~28) 레거시 스토리지
        return try {
            @Suppress("DEPRECATION")
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val targetDir = File(picturesDir, folder)
            if (!targetDir.exists()) targetDir.mkdirs()
            val targetFile = File(targetDir, filename)
            FileOutputStream(targetFile).use(write)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, targetFile.absolutePath)
            }
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (_: Exception) {
            null
        }
    }

    fun extensionFor(mime: String) = if (mime == "image/png") "png" else "jpg"

    // ---------- 공유 전용 임시 파일 (갤러리에 사본을 남기지 않음) ----------

    private const val SHARE_DIR = "shared"
    private const val SHARE_FILE_MAX_AGE_MS = 60 * 60 * 1000L

    private fun shareDir(context: Context) = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }

    /** 앱 임시 폴더에 저장하고 다른 앱에 넘길 수 있는 FileProvider URI 반환 */
    fun saveForSharing(context: Context, filename: String, write: (OutputStream) -> Unit): Uri? = try {
        val file = File(shareDir(context), filename)
        FileOutputStream(file).use(write)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (_: Exception) {
        null
    }

    /** 받는 앱이 다 읽었을 만큼 시간이 지난 공유용 임시 파일 정리 */
    fun cleanOldSharedFiles(context: Context) {
        val now = System.currentTimeMillis()
        shareDir(context).listFiles()?.forEach {
            if (now - it.lastModified() > SHARE_FILE_MAX_AGE_MS) it.delete()
        }
    }
}
