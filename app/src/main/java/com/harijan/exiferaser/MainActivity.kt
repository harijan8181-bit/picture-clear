package com.harijan.exiferaser

import android.app.AlertDialog
import android.content.ClipData
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
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
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
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 사진 속 개인정보(EXIF 메타데이터, GPS 위치 정보, 얼굴, 송장/텍스트 등)를
 * 완벽하게 제거하고 안전하게 공유할 수 있는 프라이버시 보호 앱
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val STATE_HAS_IMAGE = "has_image"
        private const val STATE_IMAGE_URI = "image_uri"
        private const val STATE_MIME = "mime"
    }

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

    // 여러 장 일괄 메타 제거용 다중 선택 런처
    private val pickMultipleMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { uris ->
        if (uris.isNotEmpty()) runBatchClean(uris)
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

    // 원본 삭제 시스템 확인창 결과
    private val deleteOriginalLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "원본 사진을 삭제했습니다.", Toast.LENGTH_SHORT).show()
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

        if (savedInstanceState == null) {
            // 새로 시작: 이전 실행의 임시 파일 정리 후 공유로 들어온 이미지 처리
            sourceCacheFile.delete()
            historyCacheFile.delete()
            thread { PhotoIo.cleanOldSharedFiles(this) }
            handleIncomingIntent(intent)
        } else {
            // 메모리 부족 등으로 앱이 종료됐다가 다시 열린 경우: 사진과 편집 내용 복원
            restoreEditSession(savedInstanceState)
        }
    }

    private val sourceCacheFile by lazy { File(cacheDir, "edit_source") }
    private val historyCacheFile by lazy { File(cacheDir, "edit_history.json") }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (mosaicView.getBaseBitmap() == null || !sourceCacheFile.exists()) return
        outState.putString(STATE_IMAGE_URI, currentImageUri?.toString())
        outState.putString(STATE_MIME, currentMimeType)
        outState.putBoolean(STATE_HAS_IMAGE, true)
        try {
            historyCacheFile.writeText(mosaicView.exportHistory().toString())
        } catch (_: Exception) {
        }
    }

    private fun restoreEditSession(state: Bundle) {
        if (!state.getBoolean(STATE_HAS_IMAGE) || !sourceCacheFile.exists()) return
        currentImageUri = state.getString(STATE_IMAGE_URI)?.let(Uri::parse)
        currentMimeType = state.getString(STATE_MIME)
        val history = try {
            JSONObject(historyCacheFile.readText())
        } catch (_: Exception) {
            JSONObject()
        }
        loadBitmapSafely(Uri.fromFile(sourceCacheFile), restoreHistory = history)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            // 사진 앱에서 "열기"로 들어오면 바로 편집 화면
            Intent.ACTION_VIEW -> intent.data?.let { loadBitmapSafely(it) }

            // 갤러리에서 "공유"로 들어오면 바로 정리해서 다시 공유할지 먼저 물어봄
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                } ?: intent.data
                uri?.let { showIncomingShareDialog(listOf(it)) }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                } ?: emptyList()
                if (uris.isNotEmpty()) showIncomingShareDialog(uris)
            }
        }
    }

    private fun showIncomingShareDialog(uris: List<Uri>) {
        val single = uris.size == 1
        val options = mutableListOf(
            "⚡ 정보 지우고 바로 공유 (화질 그대로)",
            "💾 정보 지우고 갤러리에 저장"
        )
        if (single) options.add("✏️ 얼굴·글자 가리기 (편집)")

        dialogBuilder()
            .setTitle(if (single) "공유받은 사진" else "공유받은 사진 ${uris.size}장")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> runBatchClean(uris, shareAfter = true)
                    1 -> runBatchClean(uris, shareAfter = false)
                    2 -> loadBitmapSafely(uris[0])
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 정리된 사진을 다른 앱(카톡, 당근 등)으로 공유 */
    private fun shareImages(uris: List<Uri>) {
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(uris[0]) ?: "image/*"
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }.apply {
            // 받는 앱이 임시 파일을 읽을 수 있도록 권한 부여
            clipData = ClipData.newRawUri(null, uris[0]).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "정보를 지운 사진 공유"))
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
        val btnAutoText = createPillToolButton("🔎 개인정보 자동감지") {
            detectSensitiveInfo()
        }
        val btnAutoFace = createPillToolButton("👤 얼굴 자동가림") {
            detectFacesAndMosaic()
        }
        val btnFastClean = createPillToolButton("⚡ 빠른 메타삭제") {
            quickCleanAndSave()
        }
        val btnBatch = createPillToolButton("🗂️ 여러 장 메타삭제") {
            startBatchClean()
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
        container.addView(btnAutoText)
        container.addView(btnAutoFace)
        container.addView(btnFastClean)
        container.addView(btnBatch)
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

    private fun openSafeInputStream(uri: Uri): java.io.InputStream? = PhotoIo.openStream(this, uri)

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
        // 현재 확대 배율 기준 실제 브러시 크기를 화면 중앙에 잠깐 표시
        mosaicView.showBrushIndicator()
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
        dialogBuilder()
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
    // restoreHistory가 있으면 앱 재시작 후 복원: 원본 대신 앱 내부에 복사해 둔 파일에서 다시 불러옴
    private fun loadBitmapSafely(uri: Uri, restoreHistory: JSONObject? = null) {
        val isRestore = restoreHistory != null
        if (!isRestore) {
            currentImageUri = uri
            currentMimeType = contentResolver.getType(uri) ?: when {
                uri.path?.endsWith(".png", true) == true -> "image/png"
                uri.path?.endsWith(".webp", true) == true -> "image/webp"
                else -> "image/jpeg"
            }
        }
        val mime = currentMimeType

        Toast.makeText(this, if (isRestore) "이전 작업을 복원하는 중..." else "사진 및 메타데이터 정밀 분석 중...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                // 원본 파일을 앱 내부 캐시에 그대로 복사해 두고 이후 작업은 복사본으로 진행
                // (앱이 종료됐다 복원되거나 원본 접근 권한이 사라져도 계속 작업 가능)
                val resolvedUri = if (isRestore) {
                    Uri.fromFile(sourceCacheFile)
                } else {
                    val bytes = PhotoIo.readBytes(this, PhotoIo.resolveOriginal(uri))
                        ?: PhotoIo.readBytes(this, uri)
                        ?: throw IllegalStateException("사진을 읽을 수 없습니다.")
                    sourceCacheFile.writeBytes(bytes)
                    Uri.fromFile(sourceCacheFile)
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
                        ExifInterface.ORIENTATION_TRANSPOSE -> "90° 회전 + 좌우 반전"
                        ExifInterface.ORIENTATION_TRANSVERSE -> "270° 회전 + 좌우 반전"
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

                // 2. 비트맵 디코딩 (메모리 한도 내 최대 해상도 + EXIF 회전/반전 보정)
                val finalBmp = PhotoIo.decodeUpright(this, resolvedUri, PhotoIo.maxDecodeDim())

                currentMetadata = metadata?.copy(
                    width = finalBmp.width,
                    height = finalBmp.height
                )

                runOnUiThread {
                    btnPickEmpty.visibility = View.GONE
                    mosaicView.setImage(finalBmp)
                    restoreHistory?.let { mosaicView.restoreHistory(it) }
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
            text = "\n🛡️ [사진정보 지우개 보호 안내]\n앱에서 저장한 새 사진에는 위의 위치, 기기 정보, 촬영 일시 메타데이터(EXIF)가 포함되지 않습니다. (원본 파일은 그대로 유지)"
            setTextColor(colorTextSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(0f, 1.25f)
        }
        layout.addView(tvNotice)
        scroll.addView(layout)

        dialogBuilder()
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
        val sourceUri = currentImageUri
        thread {
            // 1순위: 원본 파일에서 메타데이터만 잘라내기 (재압축 없음 → 화질·해상도 그대로)
            // 불러올 때 복사해 둔 파일을 먼저 사용 (원본이 삭제됐거나 권한이 만료돼도 동작)
            val sourceBytes = (if (sourceCacheFile.exists()) sourceCacheFile.readBytes() else null)
                ?: sourceUri?.let { PhotoIo.readBytes(this, it) }
            val cleaned = sourceBytes?.let { PhotoIo.stripLossless(it) }

            if (cleaned != null) {
                val mime = if (MetadataStripper.isPng(cleaned)) "image/png" else "image/jpeg"
                val filename = "CLEAN_${System.currentTimeMillis()}.${PhotoIo.extensionFor(mime)}"
                val savedUri = PhotoIo.save(this, filename, mime, targetFolderName, targetFolderUriString) {
                    it.write(cleaned)
                }
                runOnUiThread { onImageSaved(savedUri, isQuickClean = true, mimeType = mime) }
            } else {
                // HEIC·WebP 등 무손실 처리가 불가능한 형식은 재인코딩으로 메타데이터 제거
                executeSaveBitmap(originalBmp, isQuickClean = true)
            }
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
                // 단체 사진 뒤쪽의 작은 얼굴까지 잡도록 분석 해상도를 넉넉히 유지
                val maxDim = 1920f
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
                    .setMinFaceSize(0.04f)
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
                        Toast.makeText(this, "얼굴 인식 실패: ${PrivacyScanner.friendlyError(e)}", Toast.LENGTH_SHORT).show()
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

    // 앱 테마는 항상 어두운 Material이라, 라이트 모드에서는 다이얼로그를 밝은 테마로 맞춰 글자가 보이도록 함
    private fun dialogBuilder(): AlertDialog.Builder = AlertDialog.Builder(
        this,
        if (isNightMode) android.R.style.Theme_Material_Dialog_Alert
        else android.R.style.Theme_Material_Light_Dialog_Alert
    )

    // 글자(OCR)·바코드에서 개인정보 자동 감지 → 사용자 확인 후 블랙바로 가림
    private fun detectSensitiveInfo() {
        val bmp = mosaicView.getBaseBitmap() ?: run {
            Toast.makeText(this, "먼저 사진을 불러와주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "글자와 바코드에서 개인정보를 찾는 중...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val findings = PrivacyScanner.scan(bmp)
                runOnUiThread {
                    // 분석 도중 다른 사진을 불러왔다면 좌표가 맞지 않으므로 무시
                    if (mosaicView.getBaseBitmap() !== bmp) return@runOnUiThread
                    if (findings.isEmpty()) {
                        Toast.makeText(
                            this,
                            "자동으로 찾은 개인정보가 없습니다. 놓친 부분이 없는지 직접 확인해주세요.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        showSensitiveReviewDialog(findings)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "개인정보 감지 실패: ${PrivacyScanner.friendlyError(e)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showSensitiveReviewDialog(findings: List<PrivacyScanner.Finding>) {
        val grouped = findings.groupBy { it.category }
        val categories = SensitiveTextMatcher.Category.entries.filter { it in grouped }
        val checked = categories.associateWith { true }.toMutableMap()

        fun selectedRects() = categories
            .filter { checked[it] == true }
            .flatMap { cat -> grouped.getValue(cat).map { it.rect } }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        layout.addView(TextView(this).apply {
            text = "빨간 점선으로 표시된 곳을 블랙바로 가립니다.\n자동 감지는 일부를 놓칠 수 있으니, 가린 뒤 사진 전체를 직접 확인해주세요."
            setTextColor(colorTextSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, dp(8))
        })
        for (cat in categories) {
            layout.addView(CheckBox(this).apply {
                text = "${cat.label} (${grouped.getValue(cat).size}곳)"
                isChecked = true
                setTextColor(colorTextPrimary)
                setOnCheckedChangeListener { _, isOn ->
                    checked[cat] = isOn
                    mosaicView.setPreview(selectedRects())
                }
            })
        }

        mosaicView.setPreview(selectedRects())
        dialogBuilder()
            .setTitle("개인정보 ${findings.size}곳 감지")
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("선택 항목 가리기") { _, _ ->
                val rects = selectedRects()
                mosaicView.clearPreview()
                mosaicView.addBoxes(rects)
                if (rects.isNotEmpty()) {
                    Toast.makeText(this, "${rects.size}곳을 가렸습니다. 놓친 부분이 없는지 확인해주세요.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("취소", null)
            .setOnDismissListener { mosaicView.clearPreview() }
            .show()
    }

    // 여러 장 일괄 메타데이터 제거
    private fun startBatchClean() {
        pickMultipleMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /**
     * 여러 장의 메타데이터를 지워 저장
     * @param shareAfter true면 갤러리에 남기지 않고 앱 임시 폴더에 만든 뒤 바로 공유 창을 띄움
     */
    private fun runBatchClean(uris: List<Uri>, shareAfter: Boolean = false) {
        val cancelled = AtomicBoolean(false)
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = uris.size
            progress = 0
        }
        val tvProgress = TextView(this).apply {
            text = "0 / ${uris.size}"
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, 0)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(TextView(this@MainActivity).apply {
                text = "위치·기기·촬영일시 정보만 지웁니다. 얼굴이나 글자는 가려지지 않습니다."
                setTextColor(colorTextSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, 0, 0, dp(10))
            })
            addView(progressBar)
            addView(tvProgress)
        }
        val progressDialog = dialogBuilder()
            .setTitle(if (shareAfter) "공유할 사진의 정보 지우는 중" else "여러 장 메타데이터 제거 중")
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton("중지") { _, _ -> cancelled.set(true) }
            .show()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val folderName = targetFolderName
        val safUri = targetFolderUriString
        val batchId = System.currentTimeMillis()
        val prefix = if (shareAfter) "IMG" else "CLEAN"

        fun store(name: String, mime: String, write: (OutputStream) -> Unit): Uri? =
            if (shareAfter) PhotoIo.saveForSharing(this, name, write)
            else PhotoIo.save(this, name, mime, folderName, safUri, write)

        thread {
            var lossless = 0
            var reencoded = 0
            var failed = 0
            val outputs = ArrayList<Uri>()
            for ((index, uri) in uris.withIndex()) {
                if (cancelled.get()) break
                try {
                    val cleaned = PhotoIo.readBytes(this, uri)?.let { PhotoIo.stripLossless(it) }
                    val saved = if (cleaned != null) {
                        val mime = if (MetadataStripper.isPng(cleaned)) "image/png" else "image/jpeg"
                        val name = "${prefix}_${batchId}_${index + 1}.${PhotoIo.extensionFor(mime)}"
                        store(name, mime) { it.write(cleaned) }
                            ?.also { lossless++ }
                    } else {
                        // HEIC·WebP 등: 다시 인코딩하면서 메타데이터 제거
                        val bmp = PhotoIo.decodeUpright(this, uri, PhotoIo.maxDecodeDim())
                        try {
                            val isPng = bmp.hasAlpha()
                            val mime = if (isPng) "image/png" else "image/jpeg"
                            val name = "${prefix}_${batchId}_${index + 1}.${PhotoIo.extensionFor(mime)}"
                            store(name, mime) { out ->
                                bmp.compress(
                                    if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                                    if (isPng) 100 else 95,
                                    out
                                )
                            }?.also { reencoded++ }
                        } finally {
                            bmp.recycle()
                        }
                    }
                    if (saved == null) failed++ else outputs.add(saved)
                } catch (_: Throwable) {
                    failed++
                }
                runOnUiThread {
                    progressBar.progress = index + 1
                    tvProgress.text = "${index + 1} / ${uris.size}"
                }
            }

            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (progressDialog.isShowing) progressDialog.dismiss()
                if (shareAfter) {
                    if (failed > 0) {
                        Toast.makeText(this, "${failed}장은 처리하지 못해 공유에서 빠졌습니다.", Toast.LENGTH_LONG).show()
                    }
                    if (outputs.isNotEmpty() && !cancelled.get()) shareImages(outputs)
                    return@runOnUiThread
                }
                val done = lossless + reencoded
                val location = if (safUri != null) "지정한 폴더" else "Pictures/$folderName"
                val summary = buildString {
                    append("${done}장의 메타데이터를 지워 새로 저장했습니다.\n")
                    append("• 화질 그대로(무손실): ${lossless}장\n")
                    if (reencoded > 0) append("• 다시 인코딩(HEIC 등): ${reencoded}장\n")
                    if (failed > 0) append("• 실패: ${failed}장\n")
                    if (cancelled.get()) append("• 중지됨: ${uris.size - done - failed}장 처리 안 함\n")
                    append("\n저장 위치: $location\n원본 사진은 그대로 남아 있습니다.")
                }
                dialogBuilder()
                    .setTitle("일괄 처리 완료")
                    .setMessage(summary)
                    .setPositiveButton("확인", null)
                    .show()
            }
        }
    }

    private fun confirmClearAll() {
        if (!mosaicView.hasActions()) {
            Toast.makeText(this, "가려진 편집 내용이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        dialogBuilder()
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

        val dialog = dialogBuilder()
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

    // 비트맵을 새로 인코딩해 저장 (EXIF는 인코딩 과정에서 전부 사라짐)
    private fun executeSaveBitmap(bitmapToSave: Bitmap, isQuickClean: Boolean) {
        val isPng = currentMimeType == "image/png" || bitmapToSave.hasAlpha()
        val mime = if (isPng) "image/png" else "image/jpeg"
        val compressFormat = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val quality = if (isPng) 100 else 98
        val filename = "CLEAN_${System.currentTimeMillis()}.${PhotoIo.extensionFor(mime)}"

        val savedUri = try {
            PhotoIo.save(this, filename, mime, targetFolderName, targetFolderUriString) { out ->
                bitmapToSave.compress(compressFormat, quality, out)
            }
        } finally {
            // 메모리 누수 방지: 내보내기용 임시 비트맵 안전 정리
            if (!isQuickClean && !bitmapToSave.isRecycled) {
                bitmapToSave.recycle()
            }
        }

        runOnUiThread { onImageSaved(savedUri, isQuickClean, mime) }
    }

    private fun onImageSaved(savedUri: Uri?, isQuickClean: Boolean, mimeType: String) {
        if (savedUri != null) {
            // UI를 메타데이터 제거 완료 상태로 갱신
            currentMetadata = currentMetadata?.copy(isAlreadyClean = true)
            updateExifUi(currentMetadata)
            showSaveSuccessAndShareDialog(savedUri, isQuickClean, mimeType)
        } else {
            Toast.makeText(this, "저장에 실패했습니다. 저장소 권한을 확인해주세요.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSaveSuccessAndShareDialog(savedUri: Uri, isQuickClean: Boolean, mimeType: String) {
        val title = if (isQuickClean) "메타데이터 삭제 완료" else "개인정보 삭제 및 저장 완료"
        val originalUri = currentImageUri
        val canDelete = canDeleteOriginal(originalUri)
        val message = "개인정보를 지운 새 사진이 저장되었습니다.\n\n" +
                "🛡️ [새 사진에서 제거된 정보]\n" +
                "• GPS 위치정보\n" +
                "• 카메라·스마트폰 기종 정보\n" +
                "• 촬영 일시 및 소프트웨어 정보\n\n" +
                "⚠️ 원본 사진은 갤러리에 그대로 남아 있습니다." +
                (if (canDelete) " 필요하면 '원본 삭제'로 지울 수 있어요." else " 필요하면 갤러리에서 직접 삭제해주세요.")

        val builder = dialogBuilder()
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

        if (canDelete && originalUri != null) {
            builder.setNegativeButton("원본 삭제") { _, _ -> deleteOriginal(originalUri) }
        } else {
            builder.setNegativeButton("확인", null)
        }
        builder.show()
    }

    // 원본 삭제 가능 여부: 갤러리(MediaStore) 사진 또는 삭제를 지원하는 문서 URI
    // (사진 선택기 URI는 임시 사본이라 삭제 불가)
    private fun canDeleteOriginal(uri: Uri?): Boolean {
        if (uri == null) return false
        if (DocumentsContract.isDocumentUri(this, uri)) {
            return try {
                contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
                    c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
                } ?: false
            } catch (_: Exception) {
                false
            }
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                uri.authority == MediaStore.AUTHORITY &&
                uri.path?.contains("picker") != true
    }

    private fun deleteOriginal(uri: Uri) {
        if (DocumentsContract.isDocumentUri(this, uri)) {
            dialogBuilder()
                .setTitle("원본 사진 삭제")
                .setMessage("원본 사진을 삭제할까요? 삭제하면 되돌릴 수 없습니다.")
                .setPositiveButton("삭제") { _, _ ->
                    val ok = try { DocumentsContract.deleteDocument(contentResolver, uri) } catch (_: Exception) { false }
                    Toast.makeText(this, if (ok) "원본 사진을 삭제했습니다." else "원본을 삭제하지 못했습니다.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                // 시스템이 직접 사용자에게 삭제 확인을 받음
                val request = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                deleteOriginalLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (_: Exception) {
                Toast.makeText(this, "원본을 삭제하지 못했습니다. 갤러리에서 직접 삭제해주세요.", Toast.LENGTH_SHORT).show()
            }
        }
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
        if (isFinishing) {
            // 사용자가 앱을 완전히 닫으면 원본 복사본(위치정보 포함)을 바로 삭제
            sourceCacheFile.delete()
            historyCacheFile.delete()
        }
        super.onDestroy()
    }
}
