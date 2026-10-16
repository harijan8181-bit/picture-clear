package com.harijan.exiferaser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

/**
 * 모자이크 캔버스 뷰
 * - 모자이크 브러시, 블랙바, 화이트바 지원
 * - 핀치 줌 / 팬 제스처 안정화 (단일 터치 오인식 방지 및 배율 제한)
 * - Undo (실행 취소) 및 Redo (다시 실행) 완벽 지원
 * - 자동 감지 결과 미리보기(점선 박스) 및 브러시 굵기 인디케이터
 */
class MosaicView(context: Context, isNightMode: Boolean) : View(context) {

    enum class Mode { MOSAIC, BLACK_BOX, WHITE_BOX }

    companion object {
        // 얼굴 긴 변을 몇 칸의 모자이크 블록으로 나눌지 (작을수록 강하게 가려짐)
        private const val FACE_BLOCKS = 8

        // 브러시 모자이크 블록 크기 = 브러시 굵기 ÷ 이 값
        // (굵은 브러시로 큰 글씨를 덮어도 블록이 같이 커져서 읽을 수 없게 됨)
        private const val BRUSH_BLOCKS = 4f
        private const val MIN_BLOCK_PX = 12f
    }

    sealed class Action {
        // points: x, y 좌표가 번갈아 들어간 배열 (상태 저장·복원용)
        // block: 이 획의 모자이크 블록 크기(이미지 픽셀)
        class Stroke(val points: FloatArray, val width: Float, val block: Float) : Action() {
            val path: Path by lazy {
                Path().apply {
                    if (points.size >= 2) moveTo(points[0], points[1])
                    var i = 2
                    while (i + 1 < points.size) {
                        lineTo(points[i], points[i + 1])
                        i += 2
                    }
                }
            }
        }
        data class Box(val rect: RectF, val color: Int) : Action()
        // pixels: 얼굴 영역을 축소한 저해상도 비트맵 (얼굴 크기 기준 블록 모자이크)
        data class FaceMosaic(val rect: RectF, val pixels: Bitmap) : Action()
        // 자동 가림처럼 한 번에 추가된 여러 작업 → 실행 취소 한 번에 함께 취소
        data class Group(val actions: List<Action>) : Action()
    }

    var mode: Mode = Mode.MOSAIC
    var brushScaleMultiplier = 1.0f
    var onHistoryChanged: (() -> Unit)? = null

    // 사진 비트맵은 직접 recycle하지 않음: 얼굴·개인정보 분석이나 저장이 백그라운드에서
    // 이전 사진을 아직 쓰고 있을 수 있으므로, 참조가 모두 끝나면 GC가 정리하도록 둠
    private var baseBitmap: Bitmap? = null

    // 화면 표시 전용 렌더러 (UI 스레드에서만 사용)
    private var renderer: EditRenderer? = null
    private val currentPoints = ArrayList<Float>()

    // 실행 취소 / 다시 실행 스택
    private val actionList = mutableListOf<Action>()
    private val redoList = mutableListOf<Action>()

    // 블랙바·화이트바를 그리는 중일 때 미리보기용
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 자동 감지 미리보기 (가리기 전 확인용)
    private var previewRects: List<RectF> = emptyList()
    private var previewDashScale = 0f
    private val previewFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(70, 239, 68, 68)
    }
    private val previewStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(239, 68, 68)
    }

    // 브러시 굵기 인디케이터
    private var brushIndicatorAlpha = 0f
    private var brushIndicatorAnimator: ValueAnimator? = null
    private val indicatorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 255, 255, 255)
    }
    private val indicatorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.5f * context.resources.displayMetrics.density
    }
    private val indicatorShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(140, 0, 0, 0)
        strokeWidth = 5f * context.resources.displayMetrics.density
    }

    private val checkerboardPaint = Paint().apply {
        val checkSize = (12 * context.resources.displayMetrics.density).toInt()
        val pattern = Bitmap.createBitmap(checkSize * 2, checkSize * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(pattern)
        val p1 = Paint()
        val p2 = Paint()

        if (isNightMode) {
            p1.color = Color.parseColor("#090D16")
            p2.color = Color.parseColor("#131B2E")
        } else {
            p1.color = Color.parseColor("#ECEFF3")
            p2.color = Color.parseColor("#DEE2E8")
        }

        canvas.drawRect(0f, 0f, checkSize.toFloat(), checkSize.toFloat(), p1)
        canvas.drawRect(checkSize.toFloat(), 0f, (checkSize * 2).toFloat(), checkSize.toFloat(), p2)
        canvas.drawRect(0f, checkSize.toFloat(), checkSize.toFloat(), (checkSize * 2).toFloat(), p2)
        canvas.drawRect(checkSize.toFloat(), checkSize.toFloat(), (checkSize * 2).toFloat(), (checkSize * 2).toFloat(), p1)

        shader = BitmapShader(pattern, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private val baseFitMatrix = Matrix()
    private val userTransformMatrix = Matrix()
    private val totalDisplayMatrix = Matrix()
    private val totalInverseMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private var currentPath: Path? = null
    private var startX = 0f
    private var startY = 0f
    private var currentRect: RectF? = null
    private var baseBrushWidth = 60f

    // 제스처 안정화 플래그
    private var isMultiTouchActive = false
    private var lastPanFocusX = 0f
    private var lastPanFocusY = 0f
    private var currentCumulativeScale = 1.0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val targetScale = currentCumulativeScale * factor

            // 줌 배율 범위 제한: 0.6배 ~ 8.0배
            if (targetScale in 0.6f..8.0f) {
                currentCumulativeScale = targetScale
                userTransformMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                updateCombinedMatrices()
                invalidate()
            }
            return true
        }
    })

    fun getBaseBitmap(): Bitmap? = baseBitmap
    fun hasActions(): Boolean = actionList.isNotEmpty()
    fun canUndo(): Boolean = actionList.isNotEmpty()
    fun canRedo(): Boolean = redoList.isNotEmpty()

    fun setImage(bitmap: Bitmap) {
        this.baseBitmap = bitmap
        actionList.clear()
        redoList.clear()
        previewRects = emptyList()
        userTransformMatrix.reset()
        currentCumulativeScale = 1.0f

        // 이전 사진의 화면용 저해상도 비트맵 정리 (화면 렌더러는 UI 스레드 전용이라 바로 해제해도 안전)
        renderer?.release()
        renderer = EditRenderer(bitmap)

        baseBrushWidth = (bitmap.width * 0.06f).coerceAtLeast(35f)

        updateBaseMatrix()
        invalidate()
        onHistoryChanged?.invoke()
    }

    private fun blockFor(brushWidth: Float) = maxOf(brushWidth / BRUSH_BLOCKS, MIN_BLOCK_PX)

    private fun makeFaceMosaic(rect: RectF): Action.FaceMosaic? {
        val bmp = baseBitmap ?: return null
        val left = rect.left.toInt().coerceIn(0, bmp.width - 1)
        val top = rect.top.toInt().coerceIn(0, bmp.height - 1)
        val w = (rect.right.toInt() - left).coerceIn(1, bmp.width - left)
        val h = (rect.bottom.toInt() - top).coerceIn(1, bmp.height - top)

        // 얼굴 긴 변 기준 약 FACE_BLOCKS칸으로 나눔 → 얼굴이 아무리 커도 알아볼 수 없음
        val blockSize = (maxOf(w, h) / FACE_BLOCKS.toFloat()).coerceAtLeast(1f)
        val lowW = (w / blockSize).toInt().coerceAtLeast(1)
        val lowH = (h / blockSize).toInt().coerceAtLeast(1)

        val crop = Bitmap.createBitmap(bmp, left, top, w, h)
        val pixels = Bitmap.createScaledBitmap(crop, lowW, lowH, true)
        if (crop != pixels && crop != bmp) crop.recycle()

        return Action.FaceMosaic(RectF(left.toFloat(), top.toFloat(), (left + w).toFloat(), (top + h).toFloat()), pixels)
    }

    fun addFaceMosaics(rects: List<RectF>) {
        if (baseBitmap == null || rects.isEmpty()) return
        val faces = rects.mapNotNull { makeFaceMosaic(it) }
        if (faces.isEmpty()) return
        pushAction(if (faces.size == 1) faces[0] else Action.Group(faces))
    }

    // ---------- 편집 기록 저장/복원 (앱이 메모리 부족으로 종료돼도 작업 유지) ----------

    fun exportHistory(): JSONObject = JSONObject().apply {
        put("actions", JSONArray().apply { actionList.forEach { put(actionToJson(it)) } })
        put("redo", JSONArray().apply { redoList.forEach { put(actionToJson(it)) } })
    }

    /** setImage 이후에 호출 */
    fun restoreHistory(json: JSONObject) {
        actionList.clear()
        redoList.clear()
        json.optJSONArray("actions")?.let { arr -> for (i in 0 until arr.length()) actionFromJson(arr.getJSONObject(i))?.let(actionList::add) }
        json.optJSONArray("redo")?.let { arr -> for (i in 0 until arr.length()) actionFromJson(arr.getJSONObject(i))?.let(redoList::add) }
        invalidate()
        onHistoryChanged?.invoke()
    }

    private fun rectToJson(r: RectF) = JSONArray().apply { put(r.left.toDouble()); put(r.top.toDouble()); put(r.right.toDouble()); put(r.bottom.toDouble()) }

    private fun rectFromJson(a: JSONArray) =
        RectF(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())

    private fun actionToJson(action: Action): JSONObject = when (action) {
        is Action.Stroke -> JSONObject().apply {
            put("t", "stroke")
            put("w", action.width.toDouble())
            put("b", action.block.toDouble())
            put("p", JSONArray().apply { action.points.forEach { put(it.toDouble()) } })
        }
        is Action.Box -> JSONObject().apply { put("t", "box"); put("r", rectToJson(action.rect)); put("c", action.color) }
        is Action.FaceMosaic -> JSONObject().apply { put("t", "face"); put("r", rectToJson(action.rect)) }
        is Action.Group -> JSONObject().apply {
            put("t", "group")
            put("a", JSONArray().apply { action.actions.forEach { put(actionToJson(it)) } })
        }
    }

    private fun actionFromJson(o: JSONObject): Action? = when (o.optString("t")) {
        "stroke" -> {
            val p = o.getJSONArray("p")
            Action.Stroke(FloatArray(p.length()) { p.getDouble(it).toFloat() }, o.getDouble("w").toFloat(), o.getDouble("b").toFloat())
        }
        "box" -> Action.Box(rectFromJson(o.getJSONArray("r")), o.getInt("c"))
        "face" -> makeFaceMosaic(rectFromJson(o.getJSONArray("r")))
        "group" -> {
            val a = o.getJSONArray("a")
            Action.Group((0 until a.length()).mapNotNull { actionFromJson(a.getJSONObject(it)) })
        }
        else -> null
    }

    /** 자동 감지된 영역들을 한 번에 박스로 가림 (실행 취소 한 번에 전부 취소) */
    fun addBoxes(rects: List<RectF>, color: Int = Color.BLACK) {
        if (rects.isEmpty()) return
        val boxes = rects.map { Action.Box(RectF(it), color) }
        pushAction(if (boxes.size == 1) boxes[0] else Action.Group(boxes))
    }

    private fun pushAction(action: Action) {
        redoList.clear()
        actionList.add(action)
        invalidate()
        onHistoryChanged?.invoke()
    }

    /** 가리기 전에 감지 영역을 점선 박스로 미리 보여줌 (이미지 좌표) */
    fun setPreview(rects: List<RectF>) {
        previewRects = rects.map { RectF(it) }
        invalidate()
    }

    fun clearPreview() {
        if (previewRects.isEmpty()) return
        previewRects = emptyList()
        invalidate()
    }

    /** 현재 확대 배율이 반영된 실제 브러시 크기를 화면 중앙에 잠깐 보여줌 */
    fun showBrushIndicator() {
        brushIndicatorAnimator?.cancel()
        brushIndicatorAnimator = ValueAnimator.ofFloat(1f, 1f, 0f).apply {
            duration = 900
            addUpdateListener {
                brushIndicatorAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun undo(): Boolean {
        if (actionList.isNotEmpty()) {
            val lastAction = actionList.removeAt(actionList.size - 1)
            redoList.add(lastAction)
            invalidate()
            onHistoryChanged?.invoke()
            return true
        }
        return false
    }

    fun redo(): Boolean {
        if (redoList.isNotEmpty()) {
            val actionToRestore = redoList.removeAt(redoList.size - 1)
            actionList.add(actionToRestore)
            invalidate()
            onHistoryChanged?.invoke()
            return true
        }
        return false
    }

    fun clearAll() {
        if (actionList.isNotEmpty()) {
            actionList.clear()
            redoList.clear()
            invalidate()
            onHistoryChanged?.invoke()
        }
    }

    fun resetZoom() {
        userTransformMatrix.reset()
        currentCumulativeScale = 1.0f
        updateCombinedMatrices()
        invalidate()
    }

    private fun updateBaseMatrix() {
        val bmp = baseBitmap ?: return
        if (width == 0 || height == 0) return
        baseFitMatrix.setRectToRect(
            RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()),
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            Matrix.ScaleToFit.CENTER
        )
        updateCombinedMatrices()
    }

    private fun updateCombinedMatrices() {
        totalDisplayMatrix.set(baseFitMatrix)
        totalDisplayMatrix.postConcat(userTransformMatrix)
        totalDisplayMatrix.invert(totalInverseMatrix)
    }

    private fun displayScale(): Float {
        totalDisplayMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X].takeIf { it > 0f } ?: 1f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBaseMatrix()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), checkerboardPaint)

        val bmp = baseBitmap ?: return
        val r = renderer ?: return
        canvas.save()
        canvas.concat(totalDisplayMatrix)
        canvas.drawBitmap(bmp, 0f, 0f, null)

        for (action in actionList) r.draw(canvas, action)

        currentPath?.let {
            val brushWidth = baseBrushWidth * brushScaleMultiplier
            r.drawLiveStroke(canvas, it, brushWidth, blockFor(brushWidth))
        }
        currentRect?.let {
            boxPaint.color = if (mode == Mode.WHITE_BOX) Color.WHITE else Color.BLACK
            canvas.drawRect(it, boxPaint)
        }

        if (previewRects.isNotEmpty()) {
            // 화면 배율과 무관하게 일정한 두께로 보이도록 보정
            val scale = displayScale()
            val density = resources.displayMetrics.density
            if (scale != previewDashScale) {
                // 배율이 바뀔 때만 점선 효과를 새로 만듦 (매 프레임 객체 생성 방지)
                previewDashScale = scale
                previewStrokePaint.strokeWidth = 2f * density / scale
                previewStrokePaint.pathEffect = DashPathEffect(floatArrayOf(6f * density / scale, 4f * density / scale), 0f)
            }
            for (r in previewRects) {
                canvas.drawRect(r, previewFillPaint)
                canvas.drawRect(r, previewStrokePaint)
            }
        }

        canvas.restore()

        if (brushIndicatorAlpha > 0f) {
            val radius = baseBrushWidth * brushScaleMultiplier * displayScale() / 2f
            val cx = width / 2f
            val cy = height / 2f
            val a = (brushIndicatorAlpha * 255).toInt()
            indicatorFillPaint.alpha = (brushIndicatorAlpha * 60).toInt()
            indicatorShadowPaint.alpha = (brushIndicatorAlpha * 140).toInt()
            indicatorStrokePaint.alpha = a
            canvas.drawCircle(cx, cy, radius, indicatorFillPaint)
            canvas.drawCircle(cx, cy, radius, indicatorShadowPaint)
            canvas.drawCircle(cx, cy, radius, indicatorStrokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (baseBitmap == null) return false

        scaleDetector.onTouchEvent(event)

        // 다중 터치 (핀치 줌 / 팬 이동)
        if (event.pointerCount >= 2) {
            isMultiTouchActive = true
            currentPath = null
            currentRect = null

            val focusX = (event.getX(0) + event.getX(1)) / 2f
            val focusY = (event.getY(0) + event.getY(1)) / 2f

            if (event.actionMasked == MotionEvent.ACTION_MOVE && lastPanFocusX != 0f) {
                val dx = focusX - lastPanFocusX
                val dy = focusY - lastPanFocusY
                userTransformMatrix.postTranslate(dx, dy)
                updateCombinedMatrices()
                invalidate()
            }
            lastPanFocusX = focusX
            lastPanFocusY = focusY
            return true
        }

        // 손가락 하나를 뗐을 때 단일 터치로 오인식하여 선이 그려지는 버그 방지
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            isMultiTouchActive = true
            lastPanFocusX = 0f
            lastPanFocusY = 0f
            return true
        }

        // 마지막 손가락까지 완전히 떼어졌을 때만 다중터치 플래그 해제
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            lastPanFocusX = 0f
            lastPanFocusY = 0f
            if (isMultiTouchActive) {
                isMultiTouchActive = false
                currentPath = null
                currentRect = null
                invalidate()
                return true
            }
        }

        if (isMultiTouchActive) {
            return true
        }

        lastPanFocusX = 0f
        lastPanFocusY = 0f

        val pts = floatArrayOf(event.x, event.y)
        totalInverseMatrix.mapPoints(pts)
        val x = pts[0]
        val y = pts[1]

        val currentEffectiveBrushWidth = baseBrushWidth * brushScaleMultiplier

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = x
                startY = y
                if (mode == Mode.MOSAIC) {
                    currentPath = Path().apply { moveTo(x, y) }
                    currentPoints.clear()
                    currentPoints.add(x)
                    currentPoints.add(y)
                } else {
                    currentRect = RectF(x, y, x, y)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.MOSAIC) {
                    currentPath?.lineTo(x, y)
                    currentPoints.add(x)
                    currentPoints.add(y)
                } else {
                    currentRect?.set(minOf(startX, x), minOf(startY, y), maxOf(startX, x), maxOf(startY, y))
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.MOSAIC) {
                    if (currentPath != null) {
                        currentPoints.add(x)
                        currentPoints.add(y)
                        if (startX == x && startY == y) {
                            // 탭 한 번에도 점이 찍히도록 아주 짧은 선을 추가
                            currentPoints.add(x + 0.1f)
                            currentPoints.add(y + 0.1f)
                        }
                        pushAction(
                            Action.Stroke(
                                currentPoints.toFloatArray(),
                                currentEffectiveBrushWidth,
                                blockFor(currentEffectiveBrushWidth)
                            )
                        )
                    }
                    currentPath = null
                    currentPoints.clear()
                } else {
                    val boxColor = if (mode == Mode.WHITE_BOX) Color.WHITE else Color.BLACK
                    currentRect?.let { r ->
                        if (r.width() >= 6f && r.height() >= 6f) {
                            pushAction(Action.Box(RectF(r), boxColor))
                        }
                    }
                    currentRect = null
                }
                invalidate()
                onHistoryChanged?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 저장용 작업 준비 (UI 스레드에서 호출)
     * 지금 시점의 사진과 작업 목록을 복사해 두고, 실제 합성은 반환된 함수를 백그라운드에서 실행
     * → 큰 사진도 저장 중에 화면이 멈추지 않음
     */
    fun prepareExport(): (() -> Bitmap)? {
        val bmp = baseBitmap ?: return null
        val actions = actionList.toList()
        return {
            val exportRenderer = EditRenderer(bmp)
            try {
                exportRenderer.render(actions)
            } finally {
                exportRenderer.release()
            }
        }
    }
}
