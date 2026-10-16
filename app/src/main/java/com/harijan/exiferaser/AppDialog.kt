package com.harijan.exiferaser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/** 팝업에 쓰는 색 (앱 테마와 동일하게 맞춤) */
data class DialogPalette(
    val surface: Int,
    val surfaceVariant: Int,
    val border: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val primary: Int,
    val primarySoft: Int,
    val ripple: Int,
    val danger: Int,
    val dangerSoft: Int,
    val success: Int,
    val successSoft: Int
)

/**
 * 앱 디자인에 맞춘 팝업 (둥근 카드 + 아이콘 + 제목 + 본문 + 버튼)
 * 시스템 AlertDialog 대신 사용
 *
 * 사용 예:
 * AppDialog(activity, palette)
 *     .icon(R.drawable.ic_check, AppDialog.Tone.SUCCESS)
 *     .title("저장 완료").message("...")
 *     .primary("공유하기") { ... }
 *     .dismissButton("닫기")
 *     .show()
 */
class AppDialog(private val activity: Activity, private val palette: DialogPalette) {

    enum class Tone { PRIMARY, SUCCESS, DANGER }

    private var iconRes: Int? = null
    private var tone = Tone.PRIMARY
    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var content: View? = null
    private var scrollContent = false
    private val options = mutableListOf<Option>()
    private var primary: Pair<CharSequence, () -> Unit>? = null
    private var primaryDanger = false
    private var secondary: Pair<CharSequence, () -> Unit>? = null
    private var dismissLabel: CharSequence? = null
    private var dismissAction: (() -> Unit)? = null
    private var cancelable = true
    private var onDismiss: (() -> Unit)? = null

    private class Option(val iconRes: Int, val title: CharSequence, val subtitle: CharSequence?, val onClick: () -> Unit)

    fun icon(res: Int, tone: Tone = Tone.PRIMARY) = apply { iconRes = res; this.tone = tone }
    fun title(text: CharSequence) = apply { title = text }
    fun message(text: CharSequence) = apply { message = text }

    /** 직접 만든 내용 (scroll = true면 화면 높이의 60%까지만 보이고 스크롤) */
    fun content(view: View, scroll: Boolean = false) = apply { content = view; scrollContent = scroll }

    /** 목록형 선택지 (아이콘 + 제목 + 설명), 누르면 팝업이 닫히고 실행 */
    fun option(iconRes: Int, title: CharSequence, subtitle: CharSequence? = null, onClick: () -> Unit) =
        apply { options.add(Option(iconRes, title, subtitle, onClick)) }

    fun primary(label: CharSequence, danger: Boolean = false, onClick: () -> Unit) =
        apply { primary = label to onClick; primaryDanger = danger }

    fun secondary(label: CharSequence, onClick: () -> Unit) = apply { secondary = label to onClick }

    /** 글자만 있는 닫기 버튼 */
    fun dismissButton(label: CharSequence, onClick: (() -> Unit)? = null) =
        apply { dismissLabel = label; dismissAction = onClick }

    fun cancelable(value: Boolean) = apply { cancelable = value }
    fun onDismiss(action: () -> Unit) = apply { onDismiss = action }

    fun show(): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(28).toFloat()
            }
        }

        val (accent, accentSoft) = when (tone) {
            Tone.PRIMARY -> palette.primary to palette.primarySoft
            Tone.SUCCESS -> palette.success to palette.successSoft
            Tone.DANGER -> palette.danger to palette.dangerSoft
        }

        iconRes?.let { res ->
            card.addView(ImageView(activity).apply {
                setImageDrawable(icon(res, accent, 28))
                scaleType = ImageView.ScaleType.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accentSoft)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(14)
                }
            })
        }

        title?.let {
            card.addView(TextView(activity).apply {
                text = it
                setTextColor(palette.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
        }

        message?.let {
            // 짧은 안내는 가운데, 여러 줄 설명은 읽기 쉽게 왼쪽 정렬
            val long = it.length > 70 || it.count { c -> c == '\n' } >= 2
            card.addView(TextView(activity).apply {
                text = it
                setTextColor(palette.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                setLineSpacing(0f, 1.35f)
                gravity = if (long) Gravity.START else Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            })
        }

        content?.let { view ->
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
            if (scrollContent) {
                card.addView(MaxHeightScrollView(activity, (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()).apply {
                    isVerticalScrollBarEnabled = false
                    addView(view)
                    layoutParams = lp
                })
            } else {
                view.layoutParams = lp
                card.addView(view)
            }
        }

        if (options.isNotEmpty()) {
            val list = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16) }
            }
            options.forEach { opt -> list.addView(optionRow(opt, dialog)) }
            card.addView(list)
        }

        val hasButtons = primary != null || secondary != null || dismissLabel != null
        if (hasButtons) {
            card.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(1, dp(if (options.isEmpty()) 20 else 8))
            })
        }

        primary?.let { (label, action) ->
            val bg = if (primaryDanger) {
                GradientDrawable().apply {
                    setColor(palette.danger)
                    cornerRadius = dp(16).toFloat()
                }
            } else {
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.parseColor("#5B5BF6"), Color.parseColor("#8B5CF6"))
                ).apply { cornerRadius = dp(16).toFloat() }
            }
            card.addView(button(label, Color.WHITE, ripple(bg, Color.parseColor("#44FFFFFF")), 52) {
                dialog.dismiss()
                action()
            })
        }

        secondary?.let { (label, action) ->
            val bg = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), palette.border)
            }
            card.addView(button(label, palette.textPrimary, ripple(bg), 50) {
                dialog.dismiss()
                action()
            }.apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (primary != null) 8 else 0)
            })
        }

        dismissLabel?.let { label ->
            card.addView(button(label, palette.textSecondary, ripple(GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(16).toFloat()
            }), 46) {
                dialog.dismiss()
                dismissAction?.invoke()
            }.apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(4)
            })
        }

        dialog.setContentView(FrameLayout(activity).apply { addView(card) })
        dialog.setOnDismissListener { onDismiss?.invoke() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // 화면 양옆 16dp 여백, 태블릿에서는 최대 420dp
            val width = minOf(activity.resources.displayMetrics.widthPixels - dp(32), dp(420))
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.55f)
        }
        dialog.show()
        return dialog
    }

    private fun optionRow(opt: Option, dialog: Dialog) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = ripple(GradientDrawable().apply {
            setColor(palette.surfaceVariant)
            cornerRadius = dp(18).toFloat()
        })
        isClickable = true
        isFocusable = true
        contentDescription = listOfNotNull(opt.title, opt.subtitle).joinToString(", ")
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }

        addView(ImageView(activity).apply {
            setImageDrawable(icon(opt.iconRes, palette.primary, 22))
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply {
                setColor(palette.primarySoft)
                cornerRadius = dp(14).toFloat()
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        })

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            }
            addView(TextView(activity).apply {
                text = opt.title
                setTextColor(palette.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                typeface = Typeface.DEFAULT_BOLD
            })
            opt.subtitle?.let { sub ->
                addView(TextView(activity).apply {
                    text = sub
                    setTextColor(palette.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setPadding(0, dp(2), 0, 0)
                })
            }
        })

        addPress()
        setOnClickListener {
            dialog.dismiss()
            opt.onClick()
        }
    }

    private fun button(label: CharSequence, textColor: Int, bg: Drawable, heightDp: Int, onClick: () -> Unit) =
        TextView(activity).apply {
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = bg
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
            addPress()
            setOnClickListener { onClick() }
        }

    private fun ripple(content: Drawable, color: Int = palette.ripple) =
        RippleDrawable(ColorStateList.valueOf(color), content, null)

    private fun icon(res: Int, color: Int, sizeDp: Int): Drawable =
        ContextCompat.getDrawable(activity, res)!!.mutate().apply {
            setTint(color)
            setBounds(0, 0, dp(sizeDp), dp(sizeDp))
        }

    // 눌림 효과만 주고 클릭 처리는 OnClickListener가 담당
    @SuppressLint("ClickableViewAccessibility")
    private fun View.addPress() {
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(70).start()
            }
            false
        }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    /** 내용이 길면 지정 높이까지만 늘어나고 그 이상은 스크롤 */
    private class MaxHeightScrollView(activity: Activity, private val maxHeight: Int) : ScrollView(activity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }
}
