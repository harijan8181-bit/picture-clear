package com.example.myapplication009

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * 사진 속 개인정보(EXIF 메타데이터, GPS 위치 정보, 얼굴, 송장/텍스트 등)를
 * 완벽하게 제거하고 안전하게 공유할 수 있는 프라이버시 보호 앱
 */
class MainActivity : ComponentActivity() {

    // 사진 EXIF 메타데이터 데이터 클래스 (상세 분석용)
    data class PhotoMetadata(
        val hasGps: Boolean,
        val latitude: Double?,
        val longitude: Double?,
        val altitude: Double?,
        val gpsTimeStamp: String?,
        val dateTime: String?,
        val cameraMake: String?,
        val cameraModel: String?,
        val lensModel: String?,
        val software: String?,
        val artist: String?,
        val copyright: String?,
        val userComment: String?,
        val iso: String?,
        val fNumber: String?,
        val exposureTime: String?,
        val focalLength: String?,
        val orientationDesc: String?,
        val mimeType: String?,
        val width: Int,
        val height: Int,
        val totalTagsCount: Int,
        val isAlreadyClean: Boolean = false
    )

    private lateinit var mosaicView: MosaicView
    private lateinit var adView: AdView
    private lateinit var btnPickEmpty: TextView
    private lateinit var tvExifBadge: TextView
    private lateinit var bannerExifWarning: LinearLayout
    private lateinit var tvBannerText: TextView
    private lateinit var btnUndo: TextView
    private lateinit var btnRedo: TextView
    private lateinit var btnMosaic: TextView
    private lateinit var btnBlackBox: TextView
    private lateinit var btnWhiteBox: TextView
    private lateinit var btnBrushSize: TextView

    private var currentMetadata: PhotoMetadata? = null
    private var isNightMode: Boolean = true

    // 색상 팔레트
    private var colorBg = 0
    private var colorBar = 0
    private var colorBorder = 0
    private var colorTextPrimary = 0
    private var colorTextSecondary = 0
    private var colorSegmentBg = 0
    private var colorSegmentSelectedBg = 0
    private var colorSegmentSelectedText = 0
    private var colorRipple = 0
    private var colorWarningBg = 0
    private var colorWarningText = 0

    enum class BrushLevel(val label: String, val scale: Float) {
        SMALL("소", 0.5f),
        MEDIUM("중", 1.0f),
        LARGE("대", 2.0f)
    }
    private var currentBrushLevel = BrushLevel.MEDIUM

    private val prefs by lazy { getSharedPreferences("privacy_eraser_prefs", Context.MODE_PRIVATE) }
    private var targetFolderName: String
        get() = prefs.getString("folder_name", "PrivacyEraser") ?: "PrivacyEraser"
        set(value) = prefs.edit().putString("folder_name", value).apply()

    private var targetFolderUriString: String?
        get() = prefs.getString("folder_uri", null)
        set(value) = prefs.edit().putString("folder_uri", value).apply()

    private var currentImageUri: Uri? = null
    private var currentMimeType: String? = null

    // 갤러리 사진 선택 런처 (Android Photo Picker)
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { loadBitmapSafely(it) }
    }

    // 원본 파일/문서 선택 런처 (GPS 및 전체 EXIF 메타데이터 보존)
    private val pickDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadBitmapSafely(it) }
    }

    // SAF 폴더 선택 런처
    private val openFolderTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                targetFolderUriString = uri.toString()
                Toast.makeText(this, "저장 폴더가 지정되었습니다.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                targetFolderUriString = uri.toString()
                Toast.makeText(this, "폴더가 선택되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else {
            // Android 9 이하는 Pictures 폴더에 직접 파일을 쓰므로 쓰기 권한 필요
            permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        requestPermissionsLauncher.launch(permissions.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        MobileAds.initialize(this) {}
        checkAndRequestPermissions()

        initThemeColors()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(colorBg)
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // 1. 상단 1열: 브랜드 헤더 & 핵심 액션 (타이틀, EXIF 상태 뱃지, 정보, 실행취소, 다시실행, 저장)
        root.addView(createTopHeaderBar())

        // 2. 상단 2열: 작업 도구 바 (가로 스크롤 가능한 도구들)
        root.addView(createSubToolBar())

        // 3. 메인 작업 캔버스 프레임
        val canvasFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        mosaicView = MosaicView(this, isNightMode).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            onHistoryChanged = { updateUndoRedoButtons() }
        }
        canvasFrame.addView(mosaicView)

        // EXIF 위험 경고 플로팅 배너 (사진 로드 시 상단에 표시)
        bannerExifWarning = createExifWarningBanner()
        canvasFrame.addView(bannerExifWarning)

        // 사진이 없을 때 표시되는 중앙 버튼
        btnPickEmpty = TextView(this).apply {
            text = "+ 사진 불러오기"
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val shape = GradientDrawable().apply {
                setColor(colorBar)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), colorBorder)
            }
            background = RippleDrawable(ColorStateList.valueOf(colorRipple), shape, null)
            val lp = FrameLayout.LayoutParams(dp(160), dp(48)).apply {
                gravity = Gravity.CENTER
            }
            layoutParams = lp
            addPressAnimation()
            setOnClickListener {
                showPhotoSourceDialog()
            }
        }
        canvasFrame.addView(btnPickEmpty)
        root.addView(canvasFrame)

        // 4. 하단 세그먼트 도구 바
        root.addView(createBottomToolBar())

        // 5. 하단 애드몹 배너
        root.addView(createAdContainer())

        setContentView(root)

        // 외부 앱(갤러리 등)에서 공유로 들어온 이미지 처리
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action == Intent.ACTION_SEND || action == Intent.ACTION_VIEW) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            } ?: intent.data

            uri?.let { loadBitmapSafely(it) }
        }
    }

    private fun initThemeColors() {
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        isNightMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES

        if (isNightMode) {
            colorBg = Color.parseColor("#090D16")
            colorBar = Color.parseColor("#131B2E")
            colorBorder = Color.parseColor("#1E293B")
            colorTextPrimary = Color.parseColor("#F8FAFC")
            colorTextSecondary = Color.parseColor("#94A3B8")
            colorSegmentBg = Color.parseColor("#1E293B")
            colorSegmentSelectedBg = Color.parseColor("#6366F1")
            colorSegmentSelectedText = Color.parseColor("#FFFFFF")
            colorRipple = Color.parseColor("#22FFFFFF")
            colorWarningBg = Color.parseColor("#3B1D22")
            colorWarningText = Color.parseColor("#F87171")
        } else {
            colorBg = Color.parseColor("#F8FAFC")
            colorBar = Color.parseColor("#FFFFFF")
            colorBorder = Color.parseColor("#E2E8F0")
            colorTextPrimary = Color.parseColor("#0F172A")
            colorTextSecondary = Color.parseColor("#64748B")
            colorSegmentBg = Color.parseColor("#F1F5F9")
            colorSegmentSelectedBg = Color.parseColor("#6366F1")
            colorSegmentSelectedText = Color.parseColor("#FFFFFF")
            colorRipple = Color.parseColor("#22000000")
            colorWarningBg = Color.parseColor("#FEF2F2")
            colorWarningText = Color.parseColor("#DC2626")
        }
    }

    // 1층 상단 헤더 바
    private fun createTopHeaderBar(): View {
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            setPadding(dp(12), 0, dp(12), 0)
            setBackgroundColor(colorBar)
        }

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        val tvAppTitle = TextView(this).apply {
            text = "사진정보 지우개"
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        titleContainer.addView(tvAppTitle)

        // 메타데이터 요약 뱃지
        tvExifBadge = TextView(this).apply {
            text = ""
            visibility = View.GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), dp(3), dp(8), dp(3))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
            layoutParams = lp
            addPressAnimation()
            setOnClickListener { showExifDetailsDialog() }
        }
        titleContainer.addView(tvExifBadge)
        topBar.addView(titleContainer)

        // 우측 액션 버튼들 (정보, 취소, 다시실행, 저장)
        val actionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnExifInfo = createHeaderActionButton("ℹ️ 정보") {
            if (mosaicView.getBaseBitmap() == null) {
                Toast.makeText(this, "먼저 사진을 불러와주세요.", Toast.LENGTH_SHORT).show()
            } else {
                showExifDetailsDialog()
            }
        }
        actionsContainer.addView(btnExifInfo)

        btnUndo = createHeaderActionButton("취소") {
            val undone = mosaicView.undo()
            if (!undone) {
                Toast.makeText(this, "되돌릴 작업이 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        actionsContainer.addView(btnUndo)

        btnRedo = createHeaderActionButton("복원") {
            val redone = mosaicView.redo()
            if (!redone) {
                Toast.makeText(this, "다시 실행할 작업이 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        actionsContainer.addView(btnRedo)

        val btnSave = TextView(this).apply {
            text = "저장"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(6))
            val shape = GradientDrawable().apply {
                setColor(Color.parseColor("#4F46E5"))
                cornerRadius = dp(14).toFloat()
            }
            background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#44FFFFFF")), shape, null)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                marginStart = dp(6)
            }
            layoutParams = lp
            addPressAnimation()
            setOnClickListener { saveImageAsync() }
        }
        actionsContainer.addView(btnSave)

        topBar.addView(actionsContainer)
        return topBar
    }

    // 2층 도구 스크롤 바
    private fun createSubToolBar(): View {
        val scroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42))
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(colorBar)
            setPadding(dp(8), dp(3), dp(8), dp(5))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnPick = createPillToolButton("📂 사진 열기") {
            showPhotoSourceDialog()
        }
        val btnAutoFace = createPillToolButton("👤 얼굴 자동가림") {
            detectFacesAndMosaic()
        }
        val btnFastClean = createPillToolButton("⚡ 빠른 메타삭제") {
            quickCleanAndSave()
        }
        val btnResetZoom = createPillToolButton("⟲ 줌 리셋") {
            mosaicView.resetZoom()
            Toast.makeText(this, "화면 배율이 100%로 리셋되었습니다.", Toast.LENGTH_SHORT).show()
        }
        val btnClearAll = createPillToolButton("🗑️ 전체초기화") {
            confirmClearAll()
        }
        val btnFolder = createPillToolButton("📁 저장위치") {
            showFolderSettingDialog()
        }

        container.addView(btnPick)
        container.addView(btnAutoFace)
        container.addView(btnFastClean)
        container.addView(btnResetZoom)
        container.addView(btnClearAll)
        container.addView(btnFolder)

        scroll.addView(container)
        return scroll
    }

    // EXIF 위험 경고 플로팅 배너
    private fun createExifWarningBanner(): LinearLayout {
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            val shape = GradientDrawable().apply {
                setColor(colorWarningBg)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#EF4444"))
            }
            background = shape
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
                marginStart = dp(12)
                marginEnd = dp(12)
                topMargin = dp(8)
            }
            layoutParams = lp
            addPressAnimation()
            setOnClickListener { showExifDetailsDialog() }
        }

        tvBannerText = TextView(this).apply {
            setTextColor(colorWarningText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        banner.addView(tvBannerText)

        val tvBannerAction = TextView(this).apply {
            text = "상세보기 >"
            setTextColor(colorWarningText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        banner.addView(tvBannerAction)

        return banner
    }

    // 하단 세그먼트 도구 바
    private fun createBottomToolBar(): View {
        val bottomBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54))
            setBackgroundColor(colorBar)
            setPadding(dp(8), 0, dp(8), 0)
        }

        val segmentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(colorSegmentBg)
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }

        btnMosaic = createSegmentItem("모자이크", isSelected = true) {
            mosaicView.mode = MosaicView.Mode.MOSAIC
            updateSegment(MosaicView.Mode.MOSAIC)
            Toast.makeText(this, "모자이크 브러시 모드", Toast.LENGTH_SHORT).show()
        }

        btnBlackBox = createSegmentItem("블랙바", isSelected = false) {
            mosaicView.mode = MosaicView.Mode.BLACK_BOX
            updateSegment(MosaicView.Mode.BLACK_BOX)
            Toast.makeText(this, "블랙박스 영역 가림 모드", Toast.LENGTH_SHORT).show()
        }

        btnWhiteBox = createSegmentItem("화이트바", isSelected = false) {
            mosaicView.mode = MosaicView.Mode.WHITE_BOX
            updateSegment(MosaicView.Mode.WHITE_BOX)
            Toast.makeText(this, "화이트박스 영역 가림 모드", Toast.LENGTH_SHORT).show()
        }

        segmentContainer.addView(btnMosaic)
        segmentContainer.addView(btnBlackBox)
        segmentContainer.addView(btnWhiteBox)
        bottomBar.addView(segmentContainer)

        btnBrushSize = TextView(this).apply {
            text = "● 굵기: ${currentBrushLevel.label}"
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val shape = GradientDrawable().apply {
                setColor(colorSegmentBg)
                cornerRadius = dp(16).toFloat()
            }
            background = RippleDrawable(ColorStateList.valueOf(colorRipple), shape, null)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                marginStart = dp(10)
            }
            layoutParams = lp
            addPressAnimation()
            setOnClickListener { cycleBrushSize() }
        }
        bottomBar.addView(btnBrushSize)

        return bottomBar
    }

    // 애드몹 컨테이너
    private fun createAdContainer(): View {
        val adaptiveSize = getAdaptiveAdSize()
        val adHeightPx = adaptiveSize.getHeightInPixels(this)

        val adContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                adHeightPx
            )
            setBackgroundColor(colorBar)
        }

        adView = AdView(this).apply {
            adUnitId = "ca-app-pub-3940256099942544/6300978111"
            setAdSize(adaptiveSize)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        adContainer.addView(adView)
        adView.loadAd(AdRequest.Builder().build())
        return adContainer
    }

    private fun createHeaderActionButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            val shape = GradientDrawable().apply {
                setColor(colorSegmentBg)
                cornerRadius = dp(12).toFloat()
            }
            background = RippleDrawable(ColorStateList.valueOf(colorRipple), shape, null)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28)).apply {
                marginEnd = dp(4)
            }
            layoutParams = lp
            addPressAnimation()
            setOnClickListener { onClick() }
        }
    }

    private fun openSafeInputStream(uri: Uri): java.io.InputStream? {
        return try {
            contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            try {
                if (uri.scheme == "file" || uri.path != null) {
                    val path = uri.path ?: ""
                    val file = File(path)
                    if (file.exists()) java.io.FileInputStream(file) else null
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun createPillToolButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            val shape = GradientDrawable().apply {
                setColor(colorSegmentBg)
                cornerRadius = dp(12).toFloat()
            }
            background = RippleDrawable(ColorStateList.valueOf(colorRipple), shape, null)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28)).apply {
                marginEnd = dp(6)
            }
            layoutParams = lp
            addPressAnimation()
            setOnClickListener { onClick() }
        }
    }

    private fun createSegmentItem(text: String, isSelected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(dp(68), dp(30))
            this.layoutParams = lp
            addPressAnimation()
            this.setOnClickListener { onClick() }
            if (isSelected) {
                setTextColor(colorSegmentSelectedText)
                val base = GradientDrawable().apply {
                    setColor(colorSegmentSelectedBg)
                    cornerRadius = dp(13).toFloat()
                }
                background = RippleDrawable(ColorStateList.valueOf(colorRipple), base, null)
            } else {
                setTextColor(colorTextSecondary)
                background = null
            }
        }
    }

    private fun updateSegment(selectedMode: MosaicView.Mode) {
        val buttons = listOf(
            btnMosaic to (selectedMode == MosaicView.Mode.MOSAIC),
            btnBlackBox to (selectedMode == MosaicView.Mode.BLACK_BOX),
            btnWhiteBox to (selectedMode == MosaicView.Mode.WHITE_BOX)
        )

        for ((btn, isSelected) in buttons) {
            if (isSelected) {
                btn.setTextColor(colorSegmentSelectedText)
                val base = GradientDrawable().apply {
                    setColor(colorSegmentSelectedBg)
                    cornerRadius = dp(13).toFloat()
                }
                btn.background = RippleDrawable(ColorStateList.valueOf(colorRipple), base, null)
            } else {
                btn.setTextColor(colorTextSecondary)
                btn.background = null
            }
        }
    }

    private fun updateUndoRedoButtons() {
        btnUndo.alpha = if (mosaicView.canUndo()) 1.0f else 0.35f
        btnRedo.alpha = if (mosaicView.canRedo()) 1.0f else 0.35f
    }

    private fun cycleBrushSize() {
        currentBrushLevel = when (currentBrushLevel) {
            BrushLevel.SMALL -> BrushLevel.MEDIUM
            BrushLevel.MEDIUM -> BrushLevel.LARGE
            BrushLevel.LARGE -> BrushLevel.SMALL
        }
        btnBrushSize.text = "● 굵기: ${currentBrushLevel.label}"
        mosaicView.brushScaleMultiplier = currentBrushLevel.scale
        Toast.makeText(this, "브러시 굵기: ${currentBrushLevel.label}", Toast.LENGTH_SHORT).show()
    }

    private fun View.addPressAnimation() {
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(70).start()
                }
            }
            false
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun showPhotoSourceDialog() {
        val options = arrayOf(
            "🖼️ 갤러리 사진 선택기 (간편 선택)",
            "📁 원본 파일 선택 (GPS 및 모든 메타데이터 보존 분석)"
        )
        AlertDialog.Builder(this)
            .setTitle("사진 불러오기 방식 선택")
            .setItems(options) { _, which ->
                if (which == 0) {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } else {
                    pickDocument.launch(arrayOf("image/*"))
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // 사진 비트맵 로드 및 EXIF 메타데이터 정밀 분석
    private fun loadBitmapSafely(uri: Uri) {
        currentImageUri = uri
        val mime = contentResolver.getType(uri) ?: when {
            uri.path?.endsWith(".png", true) == true -> "image/png"
            uri.path?.endsWith(".webp", true) == true -> "image/webp"
            else -> "image/jpeg"
        }
        currentMimeType = mime

        Toast.makeText(this, "사진 및 메타데이터 정밀 분석 중...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val resolvedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    uri.authority == MediaStore.AUTHORITY
                ) {
                    try {
                        MediaStore.setRequireOriginal(uri)
                    } catch (_: Exception) {
                        uri
                    }
                } else {
                    uri
                }

                // 1. EXIF 메타데이터 정밀 분석
                var metadata: PhotoMetadata? = null
                openSafeInputStream(resolvedUri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    val latLong = exif.latLong
                    val hasGps = latLong != null
                    val lat = latLong?.get(0)
                    val lng = latLong?.get(1)
                    val altitude = if (hasGps) exif.getAltitude(0.0).takeIf { it != 0.0 } else null
                    val gpsDate = exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP)
                    val gpsTime = exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP)
                    val gpsTimeStamp = if (gpsDate != null || gpsTime != null) listOfNotNull(gpsDate, gpsTime).joinToString(" ") else null

                    val dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                    val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                    val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                    val lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)
                        ?: exif.getAttribute(ExifInterface.TAG_LENS_MAKE)
                    val software = exif.getAttribute(ExifInterface.TAG_SOFTWARE)
                    val artist = exif.getAttribute(ExifInterface.TAG_ARTIST)
                    val copyright = exif.getAttribute(ExifInterface.TAG_COPYRIGHT)
                    val userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                    val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                        ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                    val fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" }
                    val exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { "${it}s" }
                    val focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { "${it}mm" }
                    val orientationInt = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

                    var tagCount = 0
                    if (hasGps) tagCount += 2
                    if (altitude != null) tagCount++
                    if (gpsTimeStamp != null) tagCount++
                    if (dateTime != null) tagCount++
                    if (make != null) tagCount++
                    if (model != null) tagCount++
                    if (lensModel != null) tagCount++
                    if (software != null) tagCount++
                    if (artist != null) tagCount++
                    if (copyright != null) tagCount++
                    if (userComment != null) tagCount++
                    if (iso != null) tagCount++
                    if (fNumber != null) tagCount++
                    if (exposureTime != null) tagCount++
                    if (focalLength != null) tagCount++

                    val orientationDesc = when (orientationInt) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> "90° 회전"
                        ExifInterface.ORIENTATION_ROTATE_180 -> "180° 회전"
                        ExifInterface.ORIENTATION_ROTATE_270 -> "270° 회전"
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "좌우 반전"
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> "상하 반전"
                        else -> "정방향 (0°)"
                    }

                    metadata = PhotoMetadata(
                        hasGps = hasGps,
                        latitude = lat,
                        longitude = lng,
                        altitude = altitude,
                        gpsTimeStamp = gpsTimeStamp,
                        dateTime = dateTime,
                        cameraMake = make,
                        cameraModel = model,
                        lensModel = lensModel,
                        software = software,
                        artist = artist,
                        copyright = copyright,
                        userComment = userComment,
                        iso = iso,
                        fNumber = fNumber,
                        exposureTime = exposureTime,
                        focalLength = focalLength,
                        orientationDesc = orientationDesc,
                        mimeType = mime,
                        width = 0,
                        height = 0,
                        totalTagsCount = tagCount,
                        isAlreadyClean = false
                    )
                }

                // 2. 비트맵 디코딩 (메모리 한도 기반 최적 해상도 유지 및 OOM 방지)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openSafeInputStream(resolvedUri)?.use { BitmapFactory.decodeStream(it, null, options) }

                val maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
                val maxDim = if (maxMemoryMb >= 256) 6144 else 4096

                var sampleSize = 1
                while (options.outWidth / sampleSize > maxDim || options.outHeight / sampleSize > maxDim) {
                    sampleSize *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val rawBmp = openSafeInputStream(resolvedUri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOptions)
                } ?: throw Exception("이미지를 디코딩할 수 없습니다.")

                // EXIF 회전 및 반전 보정
                val rotation = openSafeInputStream(resolvedUri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f

                val finalBmp = if (rotation != 0f) {
                    val mat = Matrix().apply { postRotate(rotation) }
                    val rotated = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, mat, true)
                    rawBmp.recycle()
                    rotated
                } else {
                    rawBmp
                }

                currentMetadata = metadata?.copy(
                    width = finalBmp.width,
                    height = finalBmp.height
                )

                runOnUiThread {
                    btnPickEmpty.visibility = View.GONE
                    mosaicView.setImage(finalBmp)
                    updateExifUi(currentMetadata)
                    updateUndoRedoButtons()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "사진 로드 실패: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // EXIF 분석 결과에 따른 뱃지 및 배너 업데이트
    private fun updateExifUi(meta: PhotoMetadata?) {
        if (meta == null) {
            tvExifBadge.visibility = View.GONE
            bannerExifWarning.visibility = View.GONE
            return
        }

        tvExifBadge.visibility = View.VISIBLE

        if (meta.isAlreadyClean) {
            tvExifBadge.text = "✅ 메타데이터 제거됨"
            tvExifBadge.setTextColor(Color.WHITE)
            tvExifBadge.background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = dp(10).toFloat()
            }
            bannerExifWarning.visibility = View.GONE
        } else if (meta.hasGps) {
            // 위치 정보 포함: 최고 경고 단계
            tvExifBadge.text = "⚠️ GPS 위치 노출!"
            tvExifBadge.setTextColor(Color.WHITE)
            tvExifBadge.background = GradientDrawable().apply {
                setColor(Color.parseColor("#EF4444"))
                cornerRadius = dp(10).toFloat()
            }

            bannerExifWarning.visibility = View.VISIBLE
            tvBannerText.text = "🚨 사진에 정밀 GPS 위치정보가 포함되어 있습니다! 집/직장 위치 유출 위험"
        } else if (meta.totalTagsCount > 0) {
            // 촬영 기기/시간 정보 포함
            tvExifBadge.text = "ℹ️ ${meta.totalTagsCount}개 메타정보"
            tvExifBadge.setTextColor(colorTextPrimary)
            tvExifBadge.background = GradientDrawable().apply {
                setColor(colorSegmentBg)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), colorBorder)
            }
            bannerExifWarning.visibility = View.GONE
        } else {
            // 메타데이터 없음 (안전)
            tvExifBadge.text = "🛡️ 안전 (메타 없음)"
            tvExifBadge.setTextColor(Color.parseColor("#10B981"))
            tvExifBadge.background = GradientDrawable().apply {
                setColor(if (isNightMode) Color.parseColor("#064E3B") else Color.parseColor("#ECFDF5"))
                cornerRadius = dp(10).toFloat()
            }
            bannerExifWarning.visibility = View.GONE
        }
    }

    // EXIF 상세 정보 다이얼로그 (카테고리별 정밀 분류)
    private fun showExifDetailsDialog() {
        val meta = currentMetadata
        if (meta == null) {
            Toast.makeText(this, "분석된 메타데이터가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        fun addSectionHeader(title: String) {
            val tvSection = TextView(this).apply {
                text = title
                setTextColor(Color.parseColor("#6366F1"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(10), 0, dp(4))
            }
            layout.addView(tvSection)
        }

        fun addItem(title: String, value: String, isWarning: Boolean = false) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val tvTitle = TextView(this).apply {
                text = title
                setTextColor(colorTextSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            }
            val tvValue = TextView(this).apply {
                text = value
                setTextColor(if (isWarning) Color.parseColor("#EF4444") else colorTextPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = if (isWarning) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            itemLayout.addView(tvTitle)
            itemLayout.addView(tvValue)
            layout.addView(itemLayout)
        }

        // 1. 위치 정보
        addSectionHeader("📍 GPS 위치 정보")
        if (meta.hasGps && meta.latitude != null && meta.longitude != null) {
            addItem("위도 및 경도 (정밀 좌표)", "%.6f, %.6f (집/현재 위치 노출 위험!)".format(meta.latitude, meta.longitude), isWarning = true)
            meta.altitude?.let { addItem("해발 고도", "%.1f m".format(it)) }
            meta.gpsTimeStamp?.let { addItem("GPS 수신 시각", it) }
        } else {
            addItem("GPS 위치 정보", "포함되어 있지 않음 (안전)")
        }

        // 2. 기기 및 카메라
        addSectionHeader("📱 기기 및 촬영 장비")
        val device = listOfNotNull(meta.cameraMake, meta.cameraModel).joinToString(" ").ifEmpty { "기기 정보 없음" }
        addItem("스마트폰 / 카메라 모델", device)
        meta.lensModel?.let { addItem("렌즈 모델", it) }

        // 3. 일시 및 소프트웨어
        addSectionHeader("🕒 일시 및 소프트웨어")
        addItem("촬영 일시", meta.dateTime ?: "기록 없음")
        meta.software?.let { addItem("사용 소프트웨어 / 편집 툴", it) }
        meta.artist?.let { addItem("작성자 / 아티스트", it, isWarning = true) }
        meta.copyright?.let { addItem("저작권 정보", it) }
        meta.userComment?.let { addItem("사용자 메모(User Comment)", it) }

        // 4. 촬영 파라미터
        val cameraParams = listOfNotNull(
            meta.iso?.let { "ISO $it" },
            meta.fNumber,
            meta.exposureTime,
            meta.focalLength
        ).joinToString(" · ")
        if (cameraParams.isNotEmpty()) {
            addSectionHeader("⚙️ 카메라 촬영 설정값")
            addItem("촬영 상세값", cameraParams)
        }

        // 5. 이미지 규격
        addSectionHeader("📐 이미지 규격")
        addItem("해상도", "${meta.width} × ${meta.height} px")
        meta.orientationDesc?.let { addItem("방향(회전)", it) }
        meta.mimeType?.let { addItem("파일 포맷", it) }

        val tvNotice = TextView(this).apply {
            text = "\n🛡️ [사진정보 지우개 완벽 보호]\n앱에서 사진을 저장하면 위의 모든 위치, 기기 고유값, 촬영 일시 메타데이터(EXIF)가 100% 영구 파기됩니다."
            setTextColor(colorTextSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(0f, 1.25f)
        }
        layout.addView(tvNotice)
        scroll.addView(layout)

        AlertDialog.Builder(this)
            .setTitle("사진 메타데이터 정밀 분석")
            .setView(scroll)
            .setPositiveButton("확인", null)
            .setNeutralButton("⚡ 메타데이터만 즉시 삭제") { _, _ ->
                quickCleanAndSave()
            }
            .show()
    }

    // 모자이크 없이 메타데이터만 초고속 삭제 및 저장
    private fun quickCleanAndSave() {
        val originalBmp = mosaicView.getBaseBitmap()
        if (originalBmp == null) {
            Toast.makeText(this, "먼저 사진을 불러와주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        // 가림 편집이 있으면 원본 대신 편집본을 저장 (가린 부분이 노출된 채 저장되는 것 방지)
        if (mosaicView.hasActions()) {
            saveImageAsync()
            return
        }

        Toast.makeText(this, "메타데이터 완전 삭제 처리 중...", Toast.LENGTH_SHORT).show()
        thread {
            executeSaveBitmap(originalBmp, isQuickClean = true)
        }
    }

    // AI 얼굴인식 및 자동 모자이크
    private fun detectFacesAndMosaic() {
        val originalBmp = mosaicView.getBaseBitmap() ?: run {
            Toast.makeText(this, "먼저 사진을 불러와주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "얼굴을 감지하는 중입니다...", Toast.LENGTH_SHORT).show()

        thread {
            try {
                val maxDim = 1280f
                val origW = originalBmp.width.toFloat()
                val origH = originalBmp.height.toFloat()
                val maxSide = maxOf(origW, origH)
                val scaleFactor = if (maxSide > maxDim) maxDim / maxSide else 1.0f

                val analysisBmp = if (scaleFactor < 1.0f) {
                    Bitmap.createScaledBitmap(
                        originalBmp,
                        (origW * scaleFactor).toInt().coerceAtLeast(1),
                        (origH * scaleFactor).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    originalBmp
                }

                val image = InputImage.fromBitmap(analysisBmp, 0)
                val options = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setMinFaceSize(0.08f)
                    .build()

                val detector = FaceDetection.getClient(options)
                detector.process(image)
                    .addOnSuccessListener { faces ->
                        // 분석 도중 다른 사진을 불러왔다면 좌표가 맞지 않으므로 무시
                        if (mosaicView.getBaseBitmap() !== originalBmp) {
                            return@addOnSuccessListener
                        }
                        if (faces.isEmpty()) {
                            Toast.makeText(this, "감지된 얼굴이 없습니다.", Toast.LENGTH_SHORT).show()
                            return@addOnSuccessListener
                        }

                        val invScale = 1.0f / scaleFactor
                        val expandedFaceRects = faces.map { face ->
                            val box = face.boundingBox
                            val rect = RectF(
                                box.left * invScale,
                                box.top * invScale,
                                box.right * invScale,
                                box.bottom * invScale
                            )
                            val dx = rect.width() * 0.15f
                            val dy = rect.height() * 0.20f
                            RectF(
                                (rect.left - dx).coerceAtLeast(0f),
                                (rect.top - dy).coerceAtLeast(0f),
                                (rect.right + dx).coerceAtMost(origW),
                                (rect.bottom + dy * 0.5f).coerceAtMost(origH)
                            )
                        }

                        mosaicView.addFaceMosaics(expandedFaceRects)
                        Toast.makeText(this, "${faces.size}개의 얼굴에 모자이크를 적용했습니다.", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "얼굴 인식 실패: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                    .addOnCompleteListener {
                        detector.close()
                        if (analysisBmp != originalBmp && !analysisBmp.isRecycled) {
                            analysisBmp.recycle()
                        }
                    }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "분석 중 오류 발생: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmClearAll() {
        if (!mosaicView.hasActions()) {
            Toast.makeText(this, "가려진 편집 내용이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("편집 초기화")
            .setMessage("작업한 모든 모자이크와 가림 박스를 지우고 원본으로 되돌리시겠습니까?")
            .setPositiveButton("초기화") { _, _ ->
                mosaicView.clearAll()
                Toast.makeText(this, "모든 작업이 초기화되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showFolderSettingDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val tvInfo = TextView(this).apply {
            val currentLoc = if (targetFolderUriString != null) "지정된 시스템 폴더" else "Pictures/$targetFolderName"
            text = "현재 저장 위치: $currentLoc\n저장할 갤러리 앨범 폴더명을 입력하거나 시스템 폴더를 선택하세요."
            setTextColor(colorTextSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, 0, 0, dp(10))
        }
        dialogView.addView(tvInfo)

        val editText = EditText(this).apply {
            setText(targetFolderName)
            hint = "앨범 폴더명 입력 (예: 당근마켓, 송장가림)"
            setSingleLine(true)
            setTextColor(colorTextPrimary)
            setHintTextColor(colorTextSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        dialogView.addView(editText)

        val btnSelectSaf = TextView(this).apply {
            text = "📂 시스템 탐색기로 직접 폴더 지정하기"
            setTextColor(Color.parseColor("#4F46E5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(6))
            addPressAnimation()
        }
        dialogView.addView(btnSelectSaf)

        val dialog = AlertDialog.Builder(this)
            .setTitle("저장 폴더 설정")
            .setView(dialogView)
            .setPositiveButton("저장") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    targetFolderName = newName
                    targetFolderUriString = null
                    Toast.makeText(this, "저장 앨범: Pictures/$newName", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("기본값 복원") { _, _ ->
                targetFolderName = "PrivacyEraser"
                targetFolderUriString = null
                Toast.makeText(this, "기본 폴더로 복원되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .create()

        btnSelectSaf.setOnClickListener {
            dialog.dismiss()
            openFolderTree.launch(null)
        }

        dialog.show()
    }

    private fun getAdaptiveAdSize(): AdSize {
        val displayMetrics = resources.displayMetrics
        val widthPixels = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars()
            )
            windowMetrics.bounds.width() - insets.left - insets.right
        } else {
            displayMetrics.widthPixels
        }
        val adWidthInDp = (widthPixels / displayMetrics.density).toInt()
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidthInDp)
    }

    // 일반 편집 이미지 저장
    private fun saveImageAsync() {
        val bitmapToSave = mosaicView.exportBitmap() ?: run {
            Toast.makeText(this, "저장할 이미지가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "개인정보 삭제 및 고화질 저장 중...", Toast.LENGTH_SHORT).show()
        thread {
            executeSaveBitmap(bitmapToSave, isQuickClean = false)
        }
    }

    // 실제 비트맵 저장 및 EXIF 완전 파기 로직
    private fun executeSaveBitmap(bitmapToSave: Bitmap, isQuickClean: Boolean) {
        val isPng = currentMimeType == "image/png" || bitmapToSave.hasAlpha()
        val ext = if (isPng) "png" else "jpg"
        val mime = if (isPng) "image/png" else "image/jpeg"
        val compressFormat = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val quality = if (isPng) 100 else 98
        val filename = "CLEAN_${System.currentTimeMillis()}.$ext"
        val safUriStr = targetFolderUriString
        var savedUri: Uri? = null

        try {
            // 1. SAF 사용자 지정 폴더 우선 시도
            if (safUriStr != null) {
                try {
                    val treeUri = Uri.parse(safUriStr)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri)
                    )
                    val newFileUri = DocumentsContract.createDocument(
                        contentResolver,
                        docUri,
                        mime,
                        filename
                    )

                    if (newFileUri != null) {
                        contentResolver.openOutputStream(newFileUri)?.use { out ->
                            bitmapToSave.compress(compressFormat, quality, out)
                        }
                        savedUri = newFileUri
                    }
                } catch (_: Exception) {
                    // SAF 실패 시 MediaStore로 폴백
                }
            }

            // 2. MediaStore / Scoped Storage 공용 갤러리 저장
            if (savedUri == null) {
                val folderName = targetFolderName.ifEmpty { "PrivacyEraser" }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Images.Media.MIME_TYPE, mime)
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/$folderName")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }

                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        try {
                            contentResolver.openOutputStream(uri)?.use { out ->
                                bitmapToSave.compress(compressFormat, quality, out)
                            }
                            values.clear()
                            values.put(MediaStore.Images.Media.IS_PENDING, 0)
                            contentResolver.update(uri, values, null, null)
                            savedUri = uri
                        } catch (e: Exception) {
                            contentResolver.delete(uri, null, null)
                        }
                    }
                } else {
                    // Android 9 이하 (SDK 26~28) 레거시 스토리지 처리
                    try {
                        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        val targetDir = File(picturesDir, folderName)
                        if (!targetDir.exists()) targetDir.mkdirs()

                        val targetFile = File(targetDir, filename)
                        FileOutputStream(targetFile).use { out ->
                            bitmapToSave.compress(compressFormat, quality, out)
                        }

                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                            put(MediaStore.Images.Media.MIME_TYPE, mime)
                            put(MediaStore.Images.Media.DATA, targetFile.absolutePath)
                        }
                        savedUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    } catch (_: Exception) {}
                }
            }
        } finally {
            // 메모리 누수 방지: 내보내기용 임시 비트맵 안전 정리
            if (!isQuickClean && !bitmapToSave.isRecycled) {
                bitmapToSave.recycle()
            }
        }

        runOnUiThread {
            if (savedUri != null) {
                // UI를 메타데이터 제거 완료 상태로 갱신
                currentMetadata = currentMetadata?.copy(isAlreadyClean = true)
                updateExifUi(currentMetadata)

                showSaveSuccessAndShareDialog(savedUri, isQuickClean, mime)
            } else {
                Toast.makeText(this, "저장에 실패했습니다. 저장소 권한을 확인해주세요.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSaveSuccessAndShareDialog(savedUri: Uri, isQuickClean: Boolean, mimeType: String) {
        val title = if (isQuickClean) "메타데이터 완전 삭제 완료" else "개인정보 삭제 및 저장 완료"
        val message = "사진이 갤러리에 안전하게 저장되었습니다.\n\n" +
                "🛡️ [영구 파기된 개인정보 목록]\n" +
                "• 집 주소 및 GPS 정밀 위치정보 제거\n" +
                "• 카메라 및 스마트폰 고유 기종 식별값 제거\n" +
                "• 촬영 시간 및 소프트웨어 메타데이터 제거\n\n" +
                "이제 SNS, 중고거래, 커뮤니티에 안심하고 공유하세요!"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("바로 공유하기") { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, savedUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "안전한 사진 공유"))
            }
            .setNeutralButton("갤러리에서 보기") { _, _ ->
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(savedUri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(viewIntent)
                } catch (_: Exception) {
                    Toast.makeText(this, "사진을 열 수 있는 갤러리 앱이 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("확인", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (::adView.isInitialized) adView.resume()
    }

    override fun onPause() {
        if (::adView.isInitialized) adView.pause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::adView.isInitialized) adView.destroy()
        super.onDestroy()
    }
}

/**
 * 모자이크 캔버스 뷰
 * - 모자이크 브러시, 블랙바, 화이트바 지원
 * - 핀치 줌 / 팬 제스처 안정화 (단일 터치 오인식 방지 및 배율 제한)
 * - Undo (실행 취소) 및 Redo (다시 실행) 완벽 지원
 */
class MosaicView(context: Context, isNightMode: Boolean) : View(context) {

    enum class Mode { MOSAIC, BLACK_BOX, WHITE_BOX }

    companion object {
        // 얼굴 긴 변을 몇 칸의 모자이크 블록으로 나눌지 (작을수록 강하게 가려짐)
        private const val FACE_BLOCKS = 8
    }

    sealed class Action {
        data class Stroke(val path: Path, val width: Float) : Action()
        data class Box(val rect: RectF, val color: Int) : Action()
        // pixels: 얼굴 영역을 축소한 저해상도 비트맵 (얼굴 크기 기준 블록 모자이크)
        data class FaceMosaic(val rect: RectF, val pixels: Bitmap) : Action()
    }

    var mode: Mode = Mode.MOSAIC
    var brushScaleMultiplier = 1.0f
    var onHistoryChanged: (() -> Unit)? = null

    private var baseBitmap: Bitmap? = null
    private var pixelatedBitmap: Bitmap? = null

    // 실행 취소 / 다시 실행 스택
    private val actionList = mutableListOf<Action>()
    private val redoList = mutableListOf<Action>()

    private val mosaicStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 얼굴 모자이크용: 필터링 없이 확대해야 블록이 뭉개지지 않고 선명하게 유지됨
    private val faceBlockPaint = Paint().apply {
        isFilterBitmap = false
    }
    private val faceClipPath = Path()

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
        val oldBase = this.baseBitmap
        if (oldBase != null && oldBase != bitmap && !oldBase.isRecycled) {
            oldBase.recycle()
        }
        this.baseBitmap = bitmap
        actionList.clear()
        redoList.clear()
        userTransformMatrix.reset()
        currentCumulativeScale = 1.0f

        // 이전 픽셀화 비트맵 안전 정리
        pixelatedBitmap?.let { if (!it.isRecycled) it.recycle() }

        val scale = 0.035f
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val lowRes = Bitmap.createScaledBitmap(bitmap, w, h, false)
        val pixelated = Bitmap.createScaledBitmap(lowRes, bitmap.width, bitmap.height, false)
        lowRes.recycle()
        this.pixelatedBitmap = pixelated

        val shader = BitmapShader(pixelated, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        mosaicStrokePaint.shader = shader
        baseBrushWidth = (bitmap.width * 0.06f).coerceAtLeast(35f)

        updateBaseMatrix()
        invalidate()
        onHistoryChanged?.invoke()
    }

    fun addFaceMosaics(rects: List<RectF>) {
        val bmp = baseBitmap ?: return
        if (rects.isEmpty()) return
        redoList.clear()
        for (rect in rects) {
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

            val cropRect = RectF(left.toFloat(), top.toFloat(), (left + w).toFloat(), (top + h).toFloat())
            actionList.add(Action.FaceMosaic(cropRect, pixels))
        }
        invalidate()
        onHistoryChanged?.invoke()
    }

    private fun drawFaceMosaic(canvas: Canvas, action: Action.FaceMosaic) {
        faceClipPath.reset()
        faceClipPath.addOval(action.rect, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(faceClipPath)
        canvas.drawBitmap(action.pixels, null, action.rect, faceBlockPaint)
        canvas.restore()
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBaseMatrix()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), checkerboardPaint)

        val bmp = baseBitmap ?: return
        canvas.save()
        canvas.concat(totalDisplayMatrix)
        canvas.drawBitmap(bmp, 0f, 0f, null)

        val currentEffectiveBrushWidth = baseBrushWidth * brushScaleMultiplier

        for (action in actionList) {
            when (action) {
                is Action.Stroke -> {
                    mosaicStrokePaint.strokeWidth = action.width
                    canvas.drawPath(action.path, mosaicStrokePaint)
                }
                is Action.Box -> {
                    boxPaint.color = action.color
                    canvas.drawRect(action.rect, boxPaint)
                }
                is Action.FaceMosaic -> {
                    drawFaceMosaic(canvas, action)
                }
            }
        }

        currentPath?.let {
            mosaicStrokePaint.strokeWidth = currentEffectiveBrushWidth
            canvas.drawPath(it, mosaicStrokePaint)
        }
        currentRect?.let {
            boxPaint.color = if (mode == Mode.WHITE_BOX) Color.WHITE else Color.BLACK
            canvas.drawRect(it, boxPaint)
        }

        canvas.restore()
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
                } else {
                    currentRect = RectF(x, y, x, y)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.MOSAIC) {
                    currentPath?.lineTo(x, y)
                } else {
                    currentRect?.set(minOf(startX, x), minOf(startY, y), maxOf(startX, x), maxOf(startY, y))
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                redoList.clear()
                if (mode == Mode.MOSAIC) {
                    currentPath?.let { path ->
                        if (startX == x && startY == y) {
                            path.lineTo(x + 0.1f, y + 0.1f)
                        }
                        actionList.add(Action.Stroke(path, currentEffectiveBrushWidth))
                    }
                    currentPath = null
                } else {
                    val boxColor = if (mode == Mode.WHITE_BOX) Color.WHITE else Color.BLACK
                    currentRect?.let { r ->
                        if (r.width() >= 6f && r.height() >= 6f) {
                            actionList.add(Action.Box(RectF(r), boxColor))
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

    fun exportBitmap(): Bitmap? {
        val bmp = baseBitmap ?: return null
        val result = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        for (action in actionList) {
            when (action) {
                is Action.Stroke -> {
                    mosaicStrokePaint.strokeWidth = action.width
                    canvas.drawPath(action.path, mosaicStrokePaint)
                }
                is Action.Box -> {
                    boxPaint.color = action.color
                    canvas.drawRect(action.rect, boxPaint)
                }
                is Action.FaceMosaic -> {
                    drawFaceMosaic(canvas, action)
                }
            }
        }
        return result
    }
}