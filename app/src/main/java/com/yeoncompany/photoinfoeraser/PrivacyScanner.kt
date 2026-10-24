package com.yeoncompany.photoinfoeraser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.text.Text

/**
 * 사진 속 개인정보(주민번호·전화번호·주소·계좌번호·송장 바코드 등) 자동 감지
 * - ML Kit 한글 OCR로 글자를 읽고 SensitiveTextMatcher로 개인정보 단어 위치를 찾음
 * - 송장의 바코드·QR코드에도 같은 정보가 들어 있으므로 함께 감지
 * 모든 처리는 기기 안에서만 이루어짐 (모델이 앱에 포함되어 네트워크 사용 없음)
 */
object PrivacyScanner {

    data class Finding(val category: SensitiveTextMatcher.Category, val rect: RectF)

    // 작은 글씨도 읽히도록 OCR은 비교적 큰 해상도로 분석
    private const val MAX_ANALYSIS_DIM = 3072f

    /**
     * AI 모델은 Google Play 서비스가 앱 설치 직후 내려받음 → 아직 받는 중이면 안내 문구로 변환
     * (얼굴 인식 등 다른 ML Kit 기능에서도 공용 사용)
     */
    fun friendlyError(context: Context, e: Throwable?): String {
        val mlKit = generateSequence(e) { it.cause }.filterIsInstance<MlKitException>().firstOrNull()
        return if (mlKit?.errorCode == MlKitException.UNAVAILABLE) {
            context.getString(R.string.error_model_downloading)
        } else {
            e?.localizedMessage ?: context.getString(R.string.error_unknown)
        }
    }

    /**
     * 백그라운드 스레드에서 호출해야 함 (결과가 나올 때까지 대기)
     * @return 원본 비트맵 좌표 기준 감지 영역 목록
     */
    fun scan(source: Bitmap): List<Finding> {
        val maxSide = maxOf(source.width, source.height).toFloat()
        val scale = if (maxSide > MAX_ANALYSIS_DIM) MAX_ANALYSIS_DIM / maxSide else 1f
        val analysis = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            source
        }

        val image = InputImage.fromBitmap(analysis, 0)
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        val barcodeScanner = BarcodeScanning.getClient()

        try {
            val textTask = recognizer.process(image)
            val barcodeTask = barcodeScanner.process(image)
            Tasks.await(Tasks.whenAllComplete(textTask, barcodeTask))

            val inv = 1f / scale
            val findings = mutableListOf<Finding>()

            if (textTask.isSuccessful) {
                findings += findTextFindings(textTask.result, inv)
            }
            if (barcodeTask.isSuccessful) {
                findings += barcodeTask.result.mapNotNull { barcode: Barcode ->
                    barcode.boundingBox?.let {
                        Finding(SensitiveTextMatcher.Category.BARCODE, padded(it, inv, 0.08f, source))
                    }
                }
            }
            if (!textTask.isSuccessful && !barcodeTask.isSuccessful) {
                throw textTask.exception ?: barcodeTask.exception ?: IllegalStateException("analysis failed")
            }
            return findings
        } finally {
            recognizer.close()
            barcodeScanner.close()
            if (analysis != source && !analysis.isRecycled) analysis.recycle()
        }
    }

    private fun findTextFindings(text: Text, inv: Float): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val elements = line.elements
                if (elements.isEmpty()) continue
                val matches = SensitiveTextMatcher.findMatches(elements.map { it.text })
                for (match in matches) {
                    val union = Rect()
                    for (idx in match.tokenIndices) {
                        elements.getOrNull(idx)?.boundingBox?.let { union.union(it) }
                    }
                    if (union.isEmpty) continue
                    findings += Finding(match.category, padded(union, inv, 0.15f, null))
                }
            }
        }
        return findings
    }

    /** 분석 좌표 → 원본 좌표 변환 + 글자 가장자리가 삐져나오지 않도록 여유를 줌 */
    private fun padded(box: Rect, inv: Float, ratio: Float, bounds: Bitmap?): RectF {
        val r = RectF(box.left * inv, box.top * inv, box.right * inv, box.bottom * inv)
        val pad = r.height() * ratio + 2f
        r.inset(-pad, -pad)
        if (bounds != null) {
            r.intersect(0f, 0f, bounds.width.toFloat(), bounds.height.toFloat())
        }
        return r
    }
}
