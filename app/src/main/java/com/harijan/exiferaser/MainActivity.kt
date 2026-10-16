package com.harijan.exiferaser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
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

        // 저장소의 docs/privacy-policy.md (GitHub Pages를 켜면 그 주소로 바꿔도 됨)
        private const val PRIVACY_POLICY_URL =
            "https://github.com/harijan8181-bit/picture-clear/blob/main/docs/privacy-policy.md"
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
    private lateinit var emptyState: View
    private lateinit var tvExifBadge: TextView
    private lateinit var bannerExifWarning: LinearLayout
    private lateinit var tvBannerText: TextView
    private lateinit var btnUndo: View
    private lateinit var btnRedo: View
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
    private var colorPrimary = 0
    private var colorPrimaryEnd = 0
    private var colorPrimarySoft = 0

    enum class BrushLevel(val labelRes: Int, val scale: Float) {
        SMALL(R.string.brush_small, 0.5f),
        MEDIUM(R.string.brush_medium, 1.0f),
        LARGE(R.string.brush_large, 2.0f)
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
    private var isSaving = false

    // 광고 개인정보 동의(UMP) 상태
    private var consentInformation: ConsentInformation? = null
    private val adsInitialized = AtomicBoolean(false)
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
                Toast.makeText(this, getString(R.string.save_folder_set), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                targetFolderUriString = uri.toString()
                Toast.makeText(this, getString(R.string.save_folder_selected), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 원본 삭제 시스템 확인창 결과
    private val deleteOriginalLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, getString(R.string.original_deleted), Toast.LENGTH_SHORT).show()
        }
    }

    // 권한 요청이 끝나면(허용·거부와 무관하게) 이어서 실행할 작업
    private var afterPermissionAction: (() -> Unit)? = null

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        afterPermissionAction?.invoke()
        afterPermissionAction = null
    }

    private fun isGranted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 원본 파일의 GPS 위치까지 분석하는 데 필요한 사진 권한
     * - Android 14+: "선택한 사진만 허용"(READ_MEDIA_VISUAL_USER_SELECTED)도 정상 허용으로 처리
     * - 사진 선택기·공유 기능은 권한 없이 동작하므로, 거부해도 앱은 계속 사용 가능
     */
    private fun mediaPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            android.Manifest.permission.ACCESS_MEDIA_LOCATION
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.ACCESS_MEDIA_LOCATION
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> listOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.ACCESS_MEDIA_LOCATION
        )
        else -> listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasMediaAccess(): Boolean {
        val read = when {
            // 전체 허용이든 "선택한 사진만 허용"이든 읽기 가능
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                isGranted(android.Manifest.permission.READ_MEDIA_IMAGES) ||
                        isGranted(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                isGranted(android.Manifest.permission.READ_MEDIA_IMAGES)
            else -> isGranted(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val location = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                isGranted(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        return read && location
    }

    /** 필요한 권한이 없을 때만 요청하고, 요청이 끝나면 action 실행 */
    private fun withMediaPermission(action: () -> Unit) {
        if (hasMediaAccess()) {
            action()
            return
        }
        afterPermissionAction = action
        requestPermissionsLauncher.launch(mediaPermissions().toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Android 9 이하는 Pictures 폴더에 직접 파일을 쓰므로 쓰기 권한이 미리 필요
        // (그 외 사진 권한은 앱 시작 시 묻지 않고, 원본 파일 분석을 고를 때만 요청)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            !isGranted(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            requestPermissionsLauncher.launch(
                arrayOf(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                )
            )
        }

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
            canvasColor = colorBg
            contentDescription = getString(R.string.on_create_1)
        }
        canvasFrame.addView(mosaicView)

        // EXIF 위험 경고 플로팅 배너 (사진 로드 시 상단에 표시)
        bannerExifWarning = createExifWarningBanner()
        canvasFrame.addView(bannerExifWarning)

        // 사진이 없을 때 보여주는 시작 화면 (앱 소개 + 사진 선택)
        emptyState = createEmptyState()
        canvasFrame.addView(emptyState)
        root.addView(canvasFrame)

        // 4. 하단 세그먼트 도구 바
        root.addView(createBottomToolBar())

        // 5. 하단 애드몹 배너
        root.addView(createAdContainer())

        setContentView(root)
        requestAdConsent()

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
            getString(R.string.show_incoming_share_dialog_1),
            getString(R.string.show_incoming_share_dialog_2)
        )
        if (single) options.add(getString(R.string.show_incoming_share_dialog_3))

        dialogBuilder()
            .setTitle(if (single) getString(R.string.show_incoming_share_dialog_4) else getString(R.string.show_incoming_share_dialog_5, uris.size))
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> runBatchClean(uris, shareAfter = true)
                    1 -> runBatchClean(uris, shareAfter = false)
                    2 -> loadBitmapSafely(uris[0])
                }
            }
            .setNegativeButton(getString(R.string.show_incoming_share_dialog_6), null)
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
        startActivity(Intent.createChooser(intent, getString(R.string.share_images_1)))
    }

    private fun initThemeColors() {
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        isNightMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES

        // 앱 아이콘의 남보라 계열과 맞춘 색 구성
        if (isNightMode) {
            colorBg = Color.parseColor("#0B0E17")
            colorBar = Color.parseColor("#121826")
            colorBorder = Color.parseColor("#232B3E")
            colorTextPrimary = Color.parseColor("#F3F4F8")
            colorTextSecondary = Color.parseColor("#98A2B3")
            colorSegmentBg = Color.parseColor("#1B2233")
            colorPrimary = Color.parseColor("#7C7CFF")
            colorPrimaryEnd = Color.parseColor("#A78BFA")
            colorPrimarySoft = Color.parseColor("#252A52")
            colorRipple = Color.parseColor("#33FFFFFF")
            colorWarningBg = Color.parseColor("#3A1A20")
            colorWarningText = Color.parseColor("#F87171")
        } else {
            colorBg = Color.parseColor("#F5F6FB")
            colorBar = Color.parseColor("#FFFFFF")
            colorBorder = Color.parseColor("#E6E8F0")
            colorTextPrimary = Color.parseColor("#111827")
            colorTextSecondary = Color.parseColor("#6B7280")
            colorSegmentBg = Color.parseColor("#EEF0F6")
            colorPrimary = Color.parseColor("#5B5BF6")
            colorPrimaryEnd = Color.parseColor("#8B5CF6")
            colorPrimarySoft = Color.parseColor("#ECEBFE")
            colorRipple = Color.parseColor("#225B5BF6")
            colorWarningBg = Color.parseColor("#FEF2F2")
            colorWarningText = Color.parseColor("#DC2626")
        }
        colorSegmentSelectedBg = colorPrimary
        colorSegmentSelectedText = Color.WHITE
    }

    // ---------- 공통 디자인 요소 ----------

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    /** 앱 아이콘과 같은 남색→보라 그라데이션 (주요 버튼용, 흰 글자가 잘 보이도록 다크 모드에서도 진한 색 유지) */
    private fun primaryGradient(radiusDp: Int) = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(Color.parseColor("#5B5BF6"), Color.parseColor("#8B5CF6"))
    ).apply { cornerRadius = dp(radiusDp).toFloat() }

    /** 아이콘과 글자가 가운데에 나란히 붙어 있는 버튼 */
    private fun createIconTextButton(
        iconRes: Int,
        label: String,
        textColor: Int,
        iconColor: Int,
        bg: Drawable,
        heightDp: Int,
        onClick: () -> Unit
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = bg
        isClickable = true
        isFocusable = true
        contentDescription = label
        layoutParams = LinearLayout.LayoutParams(dp(300), dp(heightDp))
        addView(ImageView(this@MainActivity).apply {
            setImageDrawable(tintedIcon(iconRes, iconColor, 22))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        addView(TextView(this@MainActivity).apply {
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        })
        addPressAnimation()
        setOnClickListener { onClick() }
    }

    private fun withRipple(content: Drawable, rippleColor: Int = colorRipple) =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)

    private fun tintedIcon(resId: Int, color: Int, sizeDp: Int = 18): Drawable =
        ContextCompat.getDrawable(this, resId)!!.mutate().apply {
            setTint(color)
            setBounds(0, 0, dp(sizeDp), dp(sizeDp))
        }

    /** 앱 아이콘을 둥근 사각형으로 잘라 보여주는 로고 */
    private fun createLogo(sizeDp: Int, cornerDp: Int) = ImageView(this).apply {
        // 런처 아이콘은 기기마다 원·물방울 등 모양이 달라 그림자와 어긋나므로,
        // 배경·전경 레이어를 직접 겹쳐 어느 기기에서나 같은 둥근 사각형으로 보이게 함
        val layers = LayerDrawable(
            arrayOf(
                ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_launcher_background)!!,
                ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_launcher_foreground)!!
            )
        )
        // 전경의 여백을 줄여 그림이 조금 더 크게 보이도록
        val zoom = -dp(sizeDp) * 15 / 100
        layers.setLayerInset(1, zoom, zoom, zoom, zoom)
        setImageDrawable(layers)
        scaleType = ImageView.ScaleType.FIT_XY
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(cornerDp).toFloat())
            }
        }
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // 1층 상단 헤더 바: 로고 + 앱 이름(아래에 메타데이터 상태 뱃지) / 아이콘 버튼 + 저장
    private fun createTopHeaderBar(): View {
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64))
            setPadding(dp(16), 0, dp(12), 0)
            setBackgroundColor(colorBar)
        }

        topBar.addView(createLogo(sizeDp = 36, cornerDp = 11))

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(10)
            }
        }
        titleColumn.addView(TextView(this).apply {
            text = getString(R.string.create_top_header_bar_1)
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })

        // 메타데이터 요약 뱃지 (사진을 불러오면 앱 이름 아래에 표시)
        tvExifBadge = TextView(this).apply {
            text = ""
            visibility = View.GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), dp(2), dp(8), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
            addPressAnimation()
            setOnClickListener { showExifDetailsDialog() }
        }
        titleColumn.addView(tvExifBadge)
        topBar.addView(titleColumn)

        topBar.addView(createIconButton(R.drawable.ic_info, getString(R.string.create_top_header_bar_2)) {
            if (mosaicView.getBaseBitmap() == null) {
                Toast.makeText(this, getString(R.string.create_top_header_bar_3), Toast.LENGTH_SHORT).show()
            } else {
                showExifDetailsDialog()
            }
        })

        btnUndo = createIconButton(R.drawable.ic_undo, getString(R.string.show_incoming_share_dialog_6)) {
            if (!mosaicView.undo()) {
                Toast.makeText(this, getString(R.string.create_top_header_bar_4), Toast.LENGTH_SHORT).show()
            }
        }
        topBar.addView(btnUndo)

        btnRedo = createIconButton(R.drawable.ic_redo, getString(R.string.create_top_header_bar_5)) {
            if (!mosaicView.redo()) {
                Toast.makeText(this, getString(R.string.create_top_header_bar_6), Toast.LENGTH_SHORT).show()
            }
        }
        topBar.addView(btnRedo)

        val btnSave = TextView(this).apply {
            text = getString(R.string.create_top_header_bar_7)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setCompoundDrawablesRelative(tintedIcon(R.drawable.ic_check, Color.WHITE, 18), null, null, null)
            compoundDrawablePadding = dp(4)
            setPadding(dp(12), 0, dp(16), 0)
            background = withRipple(primaryGradient(20), Color.parseColor("#44FFFFFF"))
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                marginStart = dp(6)
            }
            addPressAnimation()
            setOnClickListener { saveImageAsync() }
        }
        topBar.addView(btnSave)
        return topBar
    }

    /** 아이콘만 있는 둥근 버튼 (화면 낭독기는 label을 읽음) */
    private fun createIconButton(iconRes: Int, label: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageDrawable(tintedIcon(iconRes, colorTextPrimary, 22))
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = label
        background = withRipple(rounded(Color.TRANSPARENT, 20))
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        addPressAnimation()
        setOnClickListener { onClick() }
    }

    // 2층 도구 바: 사진이 있어야 쓸 수 있는 도구는 사진을 불러오기 전까지 흐리게 표시
    private fun createSubToolBar(): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBar)
        }
        val scroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54))
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(12), dp(4), dp(12), dp(10))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        container.addView(createToolChip(R.drawable.ic_photo_add, R.string.create_sub_tool_bar_1, needsImage = false) {
            showPhotoSourceDialog()
        })
        container.addView(createToolChip(R.drawable.ic_search, R.string.create_sub_tool_bar_2, needsImage = true) {
            detectSensitiveInfo()
        })
        container.addView(createToolChip(R.drawable.ic_face, R.string.create_sub_tool_bar_3, needsImage = true) {
            detectFacesAndMosaic()
        })
        container.addView(createToolChip(R.drawable.ic_bolt, R.string.create_sub_tool_bar_4, needsImage = true) {
            quickCleanAndSave()
        })
        container.addView(createToolChip(R.drawable.ic_photo_library, R.string.create_sub_tool_bar_5, needsImage = false) {
            startBatchClean()
        })
        container.addView(createToolChip(R.drawable.ic_fit_screen, R.string.create_sub_tool_bar_6, needsImage = true) {
            mosaicView.resetZoom()
            Toast.makeText(this, getString(R.string.create_sub_tool_bar_7), Toast.LENGTH_SHORT).show()
        })
        container.addView(createToolChip(R.drawable.ic_delete, R.string.create_sub_tool_bar_8, needsImage = true) {
            confirmClearAll()
        })
        container.addView(createToolChip(R.drawable.ic_folder, R.string.create_sub_tool_bar_9, needsImage = false) {
            showFolderSettingDialog()
        })
        container.addView(createToolChip(R.drawable.ic_shield, R.string.create_sub_tool_bar_10, needsImage = false) {
            showPrivacyInfoDialog()
        })

        scroll.addView(container)
        wrapper.addView(scroll)
        // 도구 바와 작업 화면 사이 구분선
        wrapper.addView(View(this).apply {
            setBackgroundColor(colorBorder)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })
        setImageToolsEnabled(false)
        return wrapper
    }

    private val imageToolChips = mutableListOf<View>()

    private fun createToolChip(iconRes: Int, labelRes: Int, needsImage: Boolean, onClick: () -> Unit): TextView {
        val label = accessibleLabel(getString(labelRes))
        return TextView(this).apply {
            text = label
            contentDescription = label
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setCompoundDrawablesRelative(tintedIcon(iconRes, colorPrimary, 18), null, null, null)
            compoundDrawablePadding = dp(6)
            setPadding(dp(12), 0, dp(14), 0)
            background = withRipple(rounded(colorSegmentBg, 20))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                marginEnd = dp(8)
            }
            addPressAnimation()
            setOnClickListener { onClick() }
            if (needsImage) imageToolChips.add(this)
        }
    }

    /** 사진이 없을 때는 사진이 필요한 도구를 흐리게 (눌러도 안내 메시지가 나오므로 비활성화하지는 않음) */
    private fun setImageToolsEnabled(enabled: Boolean) {
        imageToolChips.forEach { it.alpha = if (enabled) 1f else 0.4f }
    }

    // 시작 화면: 로고, 한 줄 소개, 사진 선택 버튼, 주요 기능, 개인정보 안내
    private fun createEmptyState(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        content.addView(createLogo(sizeDp = 84, cornerDp = 24).apply {
            elevation = dp(6).toFloat()
        })

        content.addView(TextView(this).apply {
            text = getString(R.string.empty_title)
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(22) }
        })

        content.addView(TextView(this).apply {
            text = getString(R.string.empty_subtitle)
            setTextColor(colorTextSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        })

        // 주요 버튼: 사진 선택
        content.addView(
            createIconTextButton(
                R.drawable.ic_photo_add, getString(R.string.on_create_2),
                textColor = Color.WHITE, iconColor = Color.WHITE,
                bg = withRipple(primaryGradient(18), Color.parseColor("#44FFFFFF")),
                heightDp = 56
            ) { showPhotoSourceDialog() }.apply {
                elevation = dp(4).toFloat()
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(30)
            }
        )

        // 보조 버튼: 여러 장 한 번에
        content.addView(
            createIconTextButton(
                R.drawable.ic_photo_library, getString(R.string.empty_batch),
                textColor = colorTextPrimary, iconColor = colorPrimary,
                bg = withRipple(rounded(colorBar, 18, colorBorder)),
                heightDp = 52
            ) { startBatchClean() }.apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
            }
        )

        // 주요 기능 3가지
        val features = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(30)
            }
        }
        listOf(
            R.drawable.ic_location_off to R.string.feature_location,
            R.drawable.ic_face to R.string.feature_face,
            R.drawable.ic_search to R.string.feature_text
        ).forEach { (iconRes, labelRes) ->
            features.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(tintedIcon(iconRes, colorPrimary, 22))
                    scaleType = ImageView.ScaleType.CENTER
                    background = rounded(colorPrimarySoft, 16)
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                addView(TextView(this@MainActivity).apply {
                    text = getString(labelRes)
                    setTextColor(colorTextSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                })
            })
        }
        content.addView(features)

        // 개인정보 안내 (누르면 자세한 설명)
        content.addView(TextView(this).apply {
            text = getString(R.string.on_create_3)
            setTextColor(colorTextSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            setCompoundDrawablesRelative(tintedIcon(R.drawable.ic_lock, colorTextSecondary, 14), null, null, null)
            compoundDrawablePadding = dp(5)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = withRipple(rounded(Color.TRANSPARENT, 14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
            setOnClickListener { showPrivacyInfoDialog() }
        })

        // 작은 화면에서도 잘리지 않도록 스크롤 가능하게
        return ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(colorBg)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(FrameLayout(this@MainActivity).apply {
                addView(content)
            })
        }
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
            text = getString(R.string.create_exif_warning_banner_1)
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64))
            setBackgroundColor(colorBar)
            elevation = dp(8).toFloat()
            setPadding(dp(12), 0, dp(12), 0)
        }

        val segmentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(colorSegmentBg, 17)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }

        btnMosaic = createSegmentItem(getString(R.string.create_bottom_tool_bar_1), isSelected = true) {
            mosaicView.mode = MosaicView.Mode.MOSAIC
            updateSegment(MosaicView.Mode.MOSAIC)
            Toast.makeText(this, getString(R.string.create_bottom_tool_bar_2), Toast.LENGTH_SHORT).show()
        }

        btnBlackBox = createSegmentItem(getString(R.string.create_bottom_tool_bar_3), isSelected = false) {
            mosaicView.mode = MosaicView.Mode.BLACK_BOX
            updateSegment(MosaicView.Mode.BLACK_BOX)
            Toast.makeText(this, getString(R.string.create_bottom_tool_bar_4), Toast.LENGTH_SHORT).show()
        }

        btnWhiteBox = createSegmentItem(getString(R.string.create_bottom_tool_bar_5), isSelected = false) {
            mosaicView.mode = MosaicView.Mode.WHITE_BOX
            updateSegment(MosaicView.Mode.WHITE_BOX)
            Toast.makeText(this, getString(R.string.create_bottom_tool_bar_6), Toast.LENGTH_SHORT).show()
        }

        segmentContainer.addView(btnMosaic)
        segmentContainer.addView(btnBlackBox)
        segmentContainer.addView(btnWhiteBox)
        bottomBar.addView(segmentContainer)

        btnBrushSize = TextView(this).apply {
            text = getString(R.string.create_bottom_tool_bar_7, getString(currentBrushLevel.labelRes))
            contentDescription = getString(R.string.create_bottom_tool_bar_8, getString(currentBrushLevel.labelRes))
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setCompoundDrawablesRelative(tintedIcon(R.drawable.ic_brush, colorPrimary, 16), null, null, null)
            compoundDrawablePadding = dp(5)
            background = withRipple(rounded(colorSegmentBg, 20))
            setPadding(dp(12), 0, dp(14), 0)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply {
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
            adUnitId = BuildConfig.ADMOB_BANNER_ID
            setAdSize(adaptiveSize)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        adContainer.addView(adView)
        // 광고 요청은 개인정보 동의 확인(UMP) 이후 initializeAdsIfAllowed()에서 시작
        return adContainer
    }

    /**
     * 광고 개인정보 동의 확인 (Google UMP)
     * - 동의가 필요한 지역(유럽 등) 사용자에게만 동의 창이 표시됨
     * - 동의 결과에 따라 광고를 요청할 수 있을 때만 광고 SDK 초기화
     */
    private fun requestAdConsent() {
        val consentInfo = UserMessagingPlatform.getConsentInformation(this)
        consentInformation = consentInfo
        consentInfo.requestConsentInfoUpdate(
            this,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) { _ ->
                    initializeAdsIfAllowed()
                }
            },
            { _ -> initializeAdsIfAllowed() }
        )
        // 이전 실행에서 이미 동의했다면 확인 결과를 기다리지 않고 바로 시작
        initializeAdsIfAllowed()
    }

    private fun initializeAdsIfAllowed() {
        if (consentInformation?.canRequestAds() != true) return
        if (adsInitialized.getAndSet(true)) return
        thread {
            MobileAds.initialize(this) {}
            runOnUiThread {
                if (!isDestroyed) adView.loadAd(AdRequest.Builder().build())
            }
        }
    }

    /** 사진이 기기 밖으로 나가지 않는다는 안내 + 개인정보처리방침 + (필요 지역) 광고 동의 변경 */
    private fun showPrivacyInfoDialog() {
        val adOptionsRequired = consentInformation?.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

        val builder = dialogBuilder()
            .setTitle(getString(R.string.show_privacy_info_dialog_1))
            .setMessage(
                getString(R.string.show_privacy_info_dialog_2) +
                        getString(R.string.show_privacy_info_dialog_3) +
                        getString(R.string.show_privacy_info_dialog_4)
            )
            .setPositiveButton(getString(R.string.show_privacy_info_dialog_5), null)
            .setNeutralButton(getString(R.string.show_privacy_info_dialog_6)) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
                } catch (_: Exception) {
                    Toast.makeText(this, getString(R.string.show_privacy_info_dialog_7), Toast.LENGTH_SHORT).show()
                }
            }
        if (adOptionsRequired) {
            builder.setNegativeButton(getString(R.string.show_privacy_info_dialog_8)) { _, _ ->
                UserMessagingPlatform.showPrivacyOptionsForm(this) { _ -> }
            }
        }
        builder.show()
    }

    private fun openSafeInputStream(uri: Uri): java.io.InputStream? = PhotoIo.openStream(this, uri)

    private fun createSegmentItem(text: String, isSelected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            this.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            this.typeface = Typeface.DEFAULT_BOLD
            this.gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(dp(72), dp(40))
            this.layoutParams = lp
            addPressAnimation()
            this.setOnClickListener { onClick() }
            if (isSelected) {
                setTextColor(colorSegmentSelectedText)
                background = withRipple(primaryGradient(14))
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
                btn.background = withRipple(primaryGradient(14))
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
        btnBrushSize.text = getString(R.string.create_bottom_tool_bar_7, getString(currentBrushLevel.labelRes))
        btnBrushSize.contentDescription = getString(R.string.create_bottom_tool_bar_8, getString(currentBrushLevel.labelRes))
        mosaicView.brushScaleMultiplier = currentBrushLevel.scale
        // 현재 확대 배율 기준 실제 브러시 크기를 화면 중앙에 잠깐 표시
        mosaicView.showBrushIndicator()
    }

    // 화면 낭독기(TalkBack)용 이름: "📂 사진 열기" → "사진 열기"
    private fun accessibleLabel(text: String): String =
        text.trimStart { !it.isLetterOrDigit() }.trim().ifEmpty { text }

    // 터치 리스너는 눌림 효과만 주고 false를 반환하므로 클릭·접근성 동작은 OnClickListener가 그대로 처리
    @SuppressLint("ClickableViewAccessibility")
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
            getString(R.string.show_photo_source_dialog_1),
            getString(R.string.show_photo_source_dialog_2)
        )
        dialogBuilder()
            .setTitle(getString(R.string.show_photo_source_dialog_3))
            .setItems(options) { _, which ->
                if (which == 0) {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } else {
                    // 위치정보가 가려지지 않은 원본을 분석하려면 사진 권한 필요 (거부해도 파일 선택은 진행)
                    withMediaPermission { pickDocument.launch(arrayOf("image/*")) }
                }
            }
            .setNegativeButton(getString(R.string.show_incoming_share_dialog_6), null)
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

        Toast.makeText(this, if (isRestore) getString(R.string.load_bitmap_safely_1) else getString(R.string.load_bitmap_safely_2), Toast.LENGTH_SHORT).show()
        thread {
            try {
                // 원본 파일을 앱 내부 캐시에 그대로 복사해 두고 이후 작업은 복사본으로 진행
                // (앱이 종료됐다 복원되거나 원본 접근 권한이 사라져도 계속 작업 가능)
                val resolvedUri = if (isRestore) {
                    Uri.fromFile(sourceCacheFile)
                } else {
                    val bytes = PhotoIo.readBytes(this, PhotoIo.resolveOriginal(uri))
                        ?: PhotoIo.readBytes(this, uri)
                        ?: throw IllegalStateException(getString(R.string.load_bitmap_safely_3))
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
                        ExifInterface.ORIENTATION_ROTATE_90 -> getString(R.string.load_bitmap_safely_4)
                        ExifInterface.ORIENTATION_ROTATE_180 -> getString(R.string.load_bitmap_safely_5)
                        ExifInterface.ORIENTATION_ROTATE_270 -> getString(R.string.load_bitmap_safely_6)
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> getString(R.string.load_bitmap_safely_7)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> getString(R.string.load_bitmap_safely_8)
                        ExifInterface.ORIENTATION_TRANSPOSE -> getString(R.string.load_bitmap_safely_9)
                        ExifInterface.ORIENTATION_TRANSVERSE -> getString(R.string.load_bitmap_safely_10)
                        else -> getString(R.string.load_bitmap_safely_11)
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
                    emptyState.visibility = View.GONE
                    setImageToolsEnabled(true)
                    mosaicView.setImage(finalBmp)
                    restoreHistory?.let { mosaicView.restoreHistory(it) }
                    updateExifUi(currentMetadata)
                    updateUndoRedoButtons()
                }
            } catch (_: OutOfMemoryError) {
                runOnUiThread { showOutOfMemoryMessage() }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.load_bitmap_safely_12, e.localizedMessage), Toast.LENGTH_SHORT).show()
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
            tvExifBadge.text = getString(R.string.update_exif_ui_1)
            tvExifBadge.setTextColor(Color.WHITE)
            tvExifBadge.background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = dp(10).toFloat()
            }
            bannerExifWarning.visibility = View.GONE
        } else if (meta.hasGps) {
            // 위치 정보 포함: 최고 경고 단계
            tvExifBadge.text = getString(R.string.update_exif_ui_2)
            tvExifBadge.setTextColor(Color.WHITE)
            tvExifBadge.background = GradientDrawable().apply {
                setColor(Color.parseColor("#EF4444"))
                cornerRadius = dp(10).toFloat()
            }

            bannerExifWarning.visibility = View.VISIBLE
            tvBannerText.text = getString(R.string.update_exif_ui_3)
        } else if (meta.totalTagsCount > 0) {
            // 촬영 기기/시간 정보 포함
            tvExifBadge.text = getString(R.string.update_exif_ui_4, meta.totalTagsCount)
            tvExifBadge.setTextColor(colorTextPrimary)
            tvExifBadge.background = GradientDrawable().apply {
                setColor(colorSegmentBg)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), colorBorder)
            }
            bannerExifWarning.visibility = View.GONE
        } else {
            // 메타데이터 없음 (안전)
            tvExifBadge.text = getString(R.string.update_exif_ui_5)
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
            Toast.makeText(this, getString(R.string.show_exif_details_dialog_1), Toast.LENGTH_SHORT).show()
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
        addSectionHeader(getString(R.string.show_exif_details_dialog_2))
        if (meta.hasGps && meta.latitude != null && meta.longitude != null) {
            addItem(getString(R.string.show_exif_details_dialog_3), getString(R.string.show_exif_details_dialog_4).format(meta.latitude, meta.longitude), isWarning = true)
            meta.altitude?.let { addItem(getString(R.string.show_exif_details_dialog_5), "%.1f m".format(it)) }
            meta.gpsTimeStamp?.let { addItem(getString(R.string.show_exif_details_dialog_6), it) }
        } else {
            addItem(getString(R.string.show_exif_details_dialog_7), getString(R.string.show_exif_details_dialog_8))
        }

        // 2. 기기 및 카메라
        addSectionHeader(getString(R.string.show_exif_details_dialog_9))
        val device = listOfNotNull(meta.cameraMake, meta.cameraModel).joinToString(" ").ifEmpty { getString(R.string.show_exif_details_dialog_10) }
        addItem(getString(R.string.show_exif_details_dialog_11), device)
        meta.lensModel?.let { addItem(getString(R.string.show_exif_details_dialog_12), it) }

        // 3. 일시 및 소프트웨어
        addSectionHeader(getString(R.string.show_exif_details_dialog_13))
        addItem(getString(R.string.show_exif_details_dialog_14), meta.dateTime ?: getString(R.string.show_exif_details_dialog_15))
        meta.software?.let { addItem(getString(R.string.show_exif_details_dialog_16), it) }
        meta.artist?.let { addItem(getString(R.string.show_exif_details_dialog_17), it, isWarning = true) }
        meta.copyright?.let { addItem(getString(R.string.show_exif_details_dialog_18), it) }
        meta.userComment?.let { addItem(getString(R.string.show_exif_details_dialog_19), it) }

        // 4. 촬영 파라미터
        val cameraParams = listOfNotNull(
            meta.iso?.let { "ISO $it" },
            meta.fNumber,
            meta.exposureTime,
            meta.focalLength
        ).joinToString(" · ")
        if (cameraParams.isNotEmpty()) {
            addSectionHeader(getString(R.string.show_exif_details_dialog_20))
            addItem(getString(R.string.show_exif_details_dialog_21), cameraParams)
        }

        // 5. 이미지 규격
        addSectionHeader(getString(R.string.show_exif_details_dialog_22))
        addItem(getString(R.string.show_exif_details_dialog_23), "${meta.width} × ${meta.height} px")
        meta.orientationDesc?.let { addItem(getString(R.string.show_exif_details_dialog_24), it) }
        meta.mimeType?.let { addItem(getString(R.string.show_exif_details_dialog_25), it) }

        val tvNotice = TextView(this).apply {
            text = getString(R.string.show_exif_details_dialog_26)
            setTextColor(colorTextSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(0f, 1.25f)
        }
        layout.addView(tvNotice)
        scroll.addView(layout)

        dialogBuilder()
            .setTitle(getString(R.string.show_exif_details_dialog_27))
            .setView(scroll)
            .setPositiveButton(getString(R.string.show_privacy_info_dialog_5), null)
            .setNeutralButton(getString(R.string.show_exif_details_dialog_28)) { _, _ ->
                quickCleanAndSave()
            }
            .show()
    }

    // 모자이크 없이 메타데이터만 초고속 삭제 및 저장
    private fun quickCleanAndSave() {
        val originalBmp = mosaicView.getBaseBitmap()
        if (originalBmp == null) {
            Toast.makeText(this, getString(R.string.create_top_header_bar_3), Toast.LENGTH_SHORT).show()
            return
        }

        // 가림 편집이 있으면 원본 대신 편집본을 저장 (가린 부분이 노출된 채 저장되는 것 방지)
        if (mosaicView.hasActions()) {
            saveImageAsync()
            return
        }

        Toast.makeText(this, getString(R.string.quick_clean_and_save_1), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.create_top_header_bar_3), Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, getString(R.string.detect_faces_and_mosaic_1), Toast.LENGTH_SHORT).show()

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
                            Toast.makeText(this, getString(R.string.detect_faces_and_mosaic_2), Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this, getString(R.string.detect_faces_and_mosaic_3, faces.size), Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, getString(R.string.detect_faces_and_mosaic_4, PrivacyScanner.friendlyError(this, e)), Toast.LENGTH_SHORT).show()
                    }
                    .addOnCompleteListener {
                        detector.close()
                        if (analysisBmp != originalBmp && !analysisBmp.isRecycled) {
                            analysisBmp.recycle()
                        }
                    }
            } catch (_: OutOfMemoryError) {
                runOnUiThread { showOutOfMemoryMessage() }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.detect_faces_and_mosaic_5, e.localizedMessage), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.create_top_header_bar_3), Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, getString(R.string.detect_sensitive_info_1), Toast.LENGTH_SHORT).show()
        thread {
            try {
                val findings = PrivacyScanner.scan(bmp)
                runOnUiThread {
                    // 분석 도중 다른 사진을 불러왔다면 좌표가 맞지 않으므로 무시
                    if (mosaicView.getBaseBitmap() !== bmp) return@runOnUiThread
                    if (findings.isEmpty()) {
                        Toast.makeText(
                            this,
                            getString(R.string.detect_sensitive_info_2),
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        showSensitiveReviewDialog(findings)
                    }
                }
            } catch (_: OutOfMemoryError) {
                runOnUiThread { showOutOfMemoryMessage() }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.detect_sensitive_info_3, PrivacyScanner.friendlyError(this, e)), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun categoryLabelRes(category: SensitiveTextMatcher.Category): Int = when (category) {
        SensitiveTextMatcher.Category.RRN -> R.string.category_rrn
        SensitiveTextMatcher.Category.PHONE -> R.string.category_phone
        SensitiveTextMatcher.Category.EMAIL -> R.string.category_email
        SensitiveTextMatcher.Category.ACCOUNT -> R.string.category_account
        SensitiveTextMatcher.Category.LONG_NUMBER -> R.string.category_long_number
        SensitiveTextMatcher.Category.ADDRESS -> R.string.category_address
        SensitiveTextMatcher.Category.LABELED_LINE -> R.string.category_labeled_line
        SensitiveTextMatcher.Category.BARCODE -> R.string.category_barcode
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
            text = getString(R.string.show_sensitive_review_dialog_1)
            setTextColor(colorTextSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, dp(8))
        })
        for (cat in categories) {
            layout.addView(CheckBox(this).apply {
                text = getString(R.string.show_sensitive_review_dialog_2, getString(categoryLabelRes(cat)), grouped.getValue(cat).size)
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
            .setTitle(getString(R.string.show_sensitive_review_dialog_3, findings.size))
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton(getString(R.string.show_sensitive_review_dialog_4)) { _, _ ->
                val rects = selectedRects()
                mosaicView.clearPreview()
                mosaicView.addBoxes(rects)
                if (rects.isNotEmpty()) {
                    Toast.makeText(this, getString(R.string.show_sensitive_review_dialog_5, rects.size), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(getString(R.string.show_incoming_share_dialog_6), null)
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
                text = getString(R.string.run_batch_clean_1)
                setTextColor(colorTextSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, 0, 0, dp(10))
            })
            addView(progressBar)
            addView(tvProgress)
        }
        val progressDialog = dialogBuilder()
            .setTitle(if (shareAfter) getString(R.string.run_batch_clean_2) else getString(R.string.run_batch_clean_3))
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton(getString(R.string.run_batch_clean_4)) { _, _ -> cancelled.set(true) }
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
                        Toast.makeText(this, getString(R.string.run_batch_clean_5, failed), Toast.LENGTH_LONG).show()
                    }
                    if (outputs.isNotEmpty() && !cancelled.get()) shareImages(outputs)
                    return@runOnUiThread
                }
                val done = lossless + reencoded
                val location = if (safUri != null) getString(R.string.run_batch_clean_6) else "Pictures/$folderName"
                val summary = buildString {
                    append(getString(R.string.run_batch_clean_7, done))
                    append(getString(R.string.run_batch_clean_8, lossless))
                    if (reencoded > 0) append(getString(R.string.run_batch_clean_9, reencoded))
                    if (failed > 0) append(getString(R.string.run_batch_clean_10, failed))
                    if (cancelled.get()) append(getString(R.string.run_batch_clean_11, uris.size - done - failed))
                    append(getString(R.string.run_batch_clean_12, location))
                }
                dialogBuilder()
                    .setTitle(getString(R.string.run_batch_clean_13))
                    .setMessage(summary)
                    .setPositiveButton(getString(R.string.show_privacy_info_dialog_5), null)
                    .show()
            }
        }
    }

    private fun confirmClearAll() {
        if (!mosaicView.hasActions()) {
            Toast.makeText(this, getString(R.string.confirm_clear_all_1), Toast.LENGTH_SHORT).show()
            return
        }

        dialogBuilder()
            .setTitle(getString(R.string.confirm_clear_all_2))
            .setMessage(getString(R.string.confirm_clear_all_3))
            .setPositiveButton(getString(R.string.confirm_clear_all_4)) { _, _ ->
                mosaicView.clearAll()
                Toast.makeText(this, getString(R.string.confirm_clear_all_5), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.show_incoming_share_dialog_6), null)
            .show()
    }

    private fun showFolderSettingDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val tvInfo = TextView(this).apply {
            val currentLoc = if (targetFolderUriString != null) getString(R.string.show_folder_setting_dialog_1) else "Pictures/$targetFolderName"
            text = getString(R.string.show_folder_setting_dialog_2, currentLoc)
            setTextColor(colorTextSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, 0, 0, dp(10))
        }
        dialogView.addView(tvInfo)

        val editText = EditText(this).apply {
            setText(targetFolderName)
            hint = getString(R.string.show_folder_setting_dialog_3)
            setSingleLine(true)
            setTextColor(colorTextPrimary)
            setHintTextColor(colorTextSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        dialogView.addView(editText)

        val btnSelectSaf = TextView(this).apply {
            text = getString(R.string.show_folder_setting_dialog_4)
            setTextColor(Color.parseColor("#4F46E5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(6))
            addPressAnimation()
        }
        dialogView.addView(btnSelectSaf)

        val dialog = dialogBuilder()
            .setTitle(getString(R.string.show_folder_setting_dialog_5))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.create_top_header_bar_7)) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    targetFolderName = newName
                    targetFolderUriString = null
                    Toast.makeText(this, getString(R.string.show_folder_setting_dialog_6, newName), Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(getString(R.string.show_folder_setting_dialog_7)) { _, _ ->
                targetFolderName = "PrivacyEraser"
                targetFolderUriString = null
                Toast.makeText(this, getString(R.string.show_folder_setting_dialog_8), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.show_incoming_share_dialog_6), null)
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
        if (isSaving) {
            Toast.makeText(this, getString(R.string.save_image_async_1), Toast.LENGTH_SHORT).show()
            return
        }
        val export = mosaicView.prepareExport() ?: run {
            Toast.makeText(this, getString(R.string.save_image_async_2), Toast.LENGTH_SHORT).show()
            return
        }

        isSaving = true
        Toast.makeText(this, getString(R.string.save_image_async_3), Toast.LENGTH_SHORT).show()
        thread {
            // 원본 크기 합성은 메모리를 많이 쓰므로 백그라운드에서 처리하고 메모리 부족에 대비
            val bitmapToSave = try {
                export()
            } catch (_: OutOfMemoryError) {
                null
            }
            if (bitmapToSave == null) {
                runOnUiThread {
                    isSaving = false
                    showOutOfMemoryMessage()
                }
                return@thread
            }
            executeSaveBitmap(bitmapToSave, isQuickClean = false)
        }
    }

    private fun showOutOfMemoryMessage() {
        dialogBuilder()
            .setTitle(getString(R.string.show_out_of_memory_message_1))
            .setMessage(getString(R.string.show_out_of_memory_message_2))
            .setPositiveButton(getString(R.string.show_privacy_info_dialog_5), null)
            .show()
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
        isSaving = false
        if (savedUri != null) {
            // UI를 메타데이터 제거 완료 상태로 갱신
            currentMetadata = currentMetadata?.copy(isAlreadyClean = true)
            updateExifUi(currentMetadata)
            showSaveSuccessAndShareDialog(savedUri, isQuickClean, mimeType)
        } else {
            Toast.makeText(this, getString(R.string.on_image_saved_1), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSaveSuccessAndShareDialog(savedUri: Uri, isQuickClean: Boolean, mimeType: String) {
        val title = if (isQuickClean) getString(R.string.show_save_success_and_share_dialog_1) else getString(R.string.show_save_success_and_share_dialog_2)
        val originalUri = currentImageUri
        val canDelete = canDeleteOriginal(originalUri)
        val message = getString(R.string.show_save_success_and_share_dialog_3) +
                getString(R.string.show_save_success_and_share_dialog_4) +
                getString(R.string.show_save_success_and_share_dialog_5) +
                getString(R.string.show_save_success_and_share_dialog_6) +
                getString(R.string.show_save_success_and_share_dialog_7) +
                getString(R.string.show_save_success_and_share_dialog_8) +
                (if (canDelete) getString(R.string.show_save_success_and_share_dialog_9) else getString(R.string.show_save_success_and_share_dialog_10))

        val builder = dialogBuilder()
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.show_save_success_and_share_dialog_11)) { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, savedUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.show_save_success_and_share_dialog_12)))
            }
            .setNeutralButton(getString(R.string.show_save_success_and_share_dialog_13)) { _, _ ->
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(savedUri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(viewIntent)
                } catch (_: Exception) {
                    Toast.makeText(this, getString(R.string.show_save_success_and_share_dialog_14), Toast.LENGTH_SHORT).show()
                }
            }

        if (canDelete && originalUri != null) {
            builder.setNegativeButton(getString(R.string.show_save_success_and_share_dialog_15)) { _, _ -> deleteOriginal(originalUri) }
        } else {
            builder.setNegativeButton(getString(R.string.show_privacy_info_dialog_5), null)
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
                .setTitle(getString(R.string.delete_original_1))
                .setMessage(getString(R.string.delete_original_2))
                .setPositiveButton(getString(R.string.delete_original_3)) { _, _ ->
                    val ok = try { DocumentsContract.deleteDocument(contentResolver, uri) } catch (_: Exception) { false }
                    Toast.makeText(this, if (ok) getString(R.string.original_deleted) else getString(R.string.delete_original_4), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.show_incoming_share_dialog_6), null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                // 시스템이 직접 사용자에게 삭제 확인을 받음
                val request = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                deleteOriginalLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.delete_original_5), Toast.LENGTH_SHORT).show()
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
