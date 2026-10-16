package com.harijan.exiferaser

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build

/**
 * 편집 작업(모자이크·박스·얼굴 가림)을 캔버스에 그리는 도구
 * 화면 표시용과 저장용이 각자 인스턴스를 가지므로, 저장을 백그라운드에서 해도
 * 화면 그리기와 Paint·셰이더를 공유하지 않아 안전함
 */
class EditRenderer(private val base: Bitmap) {

    // 블록 크기별로 사진을 아주 작게 줄인 비트맵의 셰이더. 확대해서 그릴 때 필터링을 끄면 모자이크 블록이 됨
    // (원본 크기 픽셀화 비트맵을 따로 만들지 않아 메모리를 크게 아낌)
    private val mosaicShaders = mutableMapOf<Int, Pair<Bitmap, BitmapShader>>()

    private val mosaicStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isFilterBitmap = false
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 얼굴 모자이크용: 필터링 없이 확대해야 블록이 뭉개지지 않고 선명하게 유지됨
    private val faceBlockPaint = Paint().apply {
        isFilterBitmap = false
    }
    private val faceClipPath = Path()

    /** 블록 크기에 맞는 모자이크 셰이더 (블록 크기별로 한 번만 생성) */
    private fun shaderFor(block: Float): BitmapShader {
        val key = block.toInt().coerceAtLeast(1)
        mosaicShaders[key]?.let { return it.second }

        val w = (base.width / key.toFloat()).toInt().coerceAtLeast(1)
        val h = (base.height / key.toFloat()).toInt().coerceAtLeast(1)
        val lowRes = Bitmap.createScaledBitmap(base, w, h, true)
        // 작은 비트맵을 원본 크기로 늘려서 칠하는 셰이더 (최근접 보간 → 블록 모자이크)
        val shader = BitmapShader(lowRes, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(base.width / w.toFloat(), base.height / h.toFloat()) })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                filterMode = BitmapShader.FILTER_MODE_NEAREST
            }
        }
        mosaicShaders[key] = lowRes to shader
        return shader
    }

    /** 그리는 중인(아직 확정 전) 모자이크 획 */
    fun drawLiveStroke(canvas: Canvas, path: Path, width: Float, block: Float) {
        mosaicStrokePaint.shader = shaderFor(block)
        mosaicStrokePaint.strokeWidth = width
        canvas.drawPath(path, mosaicStrokePaint)
    }

    fun draw(canvas: Canvas, action: MosaicView.Action) {
        when (action) {
            is MosaicView.Action.Stroke -> drawLiveStroke(canvas, action.path, action.width, action.block)
            is MosaicView.Action.Box -> {
                boxPaint.color = action.color
                canvas.drawRect(action.rect, boxPaint)
            }
            is MosaicView.Action.FaceMosaic -> {
                faceClipPath.reset()
                faceClipPath.addOval(action.rect, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(faceClipPath)
                canvas.drawBitmap(action.pixels, null, action.rect, faceBlockPaint)
                canvas.restore()
            }
            is MosaicView.Action.Group -> action.actions.forEach { draw(canvas, it) }
        }
    }

    /** 사진과 작업 목록을 합쳐 저장용 비트맵 생성 (백그라운드 스레드에서 호출 가능) */
    fun render(actions: List<MosaicView.Action>): Bitmap {
        val result = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        actions.forEach { draw(canvas, it) }
        return result
    }

    fun release() {
        mosaicShaders.values.forEach { (bmp, _) -> if (!bmp.isRecycled) bmp.recycle() }
        mosaicShaders.clear()
    }
}
