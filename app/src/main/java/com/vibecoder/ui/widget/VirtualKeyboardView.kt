package com.vibecoder.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.setPadding
import com.google.android.material.button.MaterialButton

/**
 * 模拟键盘视图 - 用于终端输入
 */
class VirtualKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var onKeyPressed: ((key: String, isModifier: Boolean) -> Unit)? = null
    var onEnterPressed: (() -> Unit)? = null
    var onBackspacePressed: (() -> Unit)? = null

    // 修饰键状态
    private var ctrlPressed = false
    private var altPressed = false
    private var shiftPressed = false

    private val modifierButtons = mutableMapOf<String, MaterialButton>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#1E1E1E"))
        setPadding(4)
        setupKeyboard()
    }

    private fun setupKeyboard() {
        // 第一行：修饰键
        addView(createRow(listOf(
            "Esc" to "\u001B",
            "Tab" to "\t",
            "Ctrl" to "CTRL",
            "Alt" to "ALT",
            "Shift" to "SHIFT"
        ), isModifierRow = true))

        // 第二行：功能键
        addView(createRow(listOf(
            "F1" to "\u001BOP",
            "F2" to "\u001BOQ",
            "F3" to "\u001BOR",
            "F4" to "\u001BOS",
            "F5" to "\u001B[15~",
            "F6" to "\u001B[17~"
        )))

        // 第三行：数字行
        addView(createRow(listOf(
            "`" to "`", "1" to "1", "2" to "2", "3" to "3",
            "4" to "4", "5" to "5", "6" to "6", "7" to "7",
            "8" to "8", "9" to "9", "0" to "0", "-" to "-",
            "=" to "=", "⌫" to "BACKSPACE"
        )))

        // 第四行：QWERTY第一行
        addView(createRow(listOf(
            "q" to "q", "w" to "w", "e" to "e", "r" to "r",
            "t" to "t", "y" to "y", "u" to "u", "i" to "i",
            "o" to "o", "p" to "p", "[" to "[", "]" to "]",
            "\\" to "\\"
        )))

        // 第五行：QWERTY第二行
        addView(createRow(listOf(
            "a" to "a", "s" to "s", "d" to "d", "f" to "f",
            "g" to "g", "h" to "h", "j" to "j", "k" to "k",
            "l" to "l", ";" to ";", "'" to "'", "↵" to "ENTER"
        ), lastKeyWidth = 1.5f))

        // 第六行：QWERTY第三行
        addView(createRow(listOf(
            "z" to "z", "x" to "x", "c" to "c", "v" to "v",
            "b" to "b", "n" to "n", "m" to "m", "," to ",",
            "." to ".", "/" to "/", "↑" to "UP"
        )))

        // 第七行：空格行
        addView(createRow(listOf(
            "←" to "LEFT", "↓" to "DOWN", "→" to "RIGHT",
            " " to " ", "Space" to " ", "Del" to "DEL"
        ), spaceKeyIndex = 3))
    }

    private fun createRow(
        keys: List<Pair<String, String>>,
        isModifierRow: Boolean = false,
        lastKeyWidth: Float = 1f,
        spaceKeyIndex: Int = -1
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
        }

        keys.forEachIndexed { index, (label, code) ->
            val isLast = index == keys.size - 1
            val isSpace = index == spaceKeyIndex

            val button = createButton(label, code, isModifierRow, isLast || isSpace)

            if (isSpace) {
                button.layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 2f)
            } else if (isLast && lastKeyWidth != 1f) {
                button.layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, lastKeyWidth)
            }

            row.addView(button)

            if (isModifierRow && (code == "CTRL" || code == "ALT" || code == "SHIFT")) {
                modifierButtons[code] = button
            }
        }

        return row
    }

    private fun createButton(
        label: String,
        code: String,
        isModifierRow: Boolean,
        isWide: Boolean = false
    ): MaterialButton {
        return MaterialButton(context).apply {
            text = label
            textSize = if (label.length > 2) 9f else 12f
            setPadding(2, 2, 2, 2)
            minHeight = 36
            minWidth = if (isWide) 60 else 44
            insetTop = 0
            insetBottom = 0
            strokeColor = ColorStateList.valueOf(Color.parseColor("#555555"))
            strokeWidth = 1
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2D2D2D"))

            setOnClickListener {
                handleKeyPress(code)
            }

            // 长按处理修饰键
            if (isModifierRow && (code == "CTRL" || code == "ALT" || code == "SHIFT")) {
                setOnLongClickListener {
                    toggleModifier(code)
                    true
                }
            }
        }
    }

    private fun handleKeyPress(code: String) {
        when (code) {
            "CTRL", "ALT", "SHIFT" -> {
                toggleModifier(code)
            }
            "BACKSPACE" -> {
                onBackspacePressed?.invoke()
            }
            "ENTER" -> {
                onEnterPressed?.invoke()
            }
            "UP" -> {
                onKeyPressed?.invoke("\u001B[A", false)
            }
            "DOWN" -> {
                onKeyPressed?.invoke("\u001B[B", false)
            }
            "LEFT" -> {
                onKeyPressed?.invoke("\u001B[D", false)
            }
            "RIGHT" -> {
                onKeyPressed?.invoke("\u001B[C", false)
            }
            "DEL" -> {
                onKeyPressed?.invoke("\u007F", false)
            }
            else -> {
                var output = code

                // 处理修饰键组合
                if (ctrlPressed && code.length == 1 && code[0].isLetter()) {
                    // Ctrl+A -> \u0001, Ctrl+B -> \u0002, etc.
                    val char = code[0].lowercaseChar()
                    output = (char.code - 'a'.code + 1).toChar().toString()
                }

                if (shiftPressed && code.length == 1 && code[0].isLetter()) {
                    output = code.uppercase()
                }

                onKeyPressed?.invoke(output, ctrlPressed || altPressed || shiftPressed)

                // 非锁定模式：按完后重置修饰键
                if (ctrlPressed || altPressed || shiftPressed) {
                    resetModifiers()
                }
            }
        }
    }

    private fun toggleModifier(modifier: String) {
        when (modifier) {
            "CTRL" -> {
                ctrlPressed = !ctrlPressed
                updateModifierButton("CTRL", ctrlPressed)
            }
            "ALT" -> {
                altPressed = !altPressed
                updateModifierButton("ALT", altPressed)
            }
            "SHIFT" -> {
                shiftPressed = !shiftPressed
                updateModifierButton("SHIFT", shiftPressed)
            }
        }
    }

    private fun updateModifierButton(modifier: String, active: Boolean) {
        modifierButtons[modifier]?.apply {
            if (active) {
                setBackgroundColor(Color.parseColor("#4CAF50"))
                strokeColor = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            } else {
                setBackgroundColor(Color.parseColor("#2D2D2D"))
                strokeColor = ColorStateList.valueOf(Color.parseColor("#555555"))
            }
        }
    }

    private fun resetModifiers() {
        ctrlPressed = false
        altPressed = false
        shiftPressed = false
        updateModifierButton("CTRL", false)
        updateModifierButton("ALT", false)
        updateModifierButton("SHIFT", false)
    }

    fun show() {
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
        resetModifiers()
    }

    fun toggle() {
        if (visibility == View.VISIBLE) {
            hide()
        } else {
            show()
        }
    }
}