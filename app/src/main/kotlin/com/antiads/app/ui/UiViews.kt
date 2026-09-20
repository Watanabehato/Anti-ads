package com.antiads.app.ui

import android.content.Context
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 原生 View 构建助手：不使用 AndroidX/AppCompat，文案全部来自 strings.xml。 */
internal object UiViews {

    fun scrollColumn(context: Context): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(context)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16f), dp(context, 12f), dp(context, 16f), dp(context, 32f))
        }
        scroll.addView(column)
        return scroll to column
    }

    fun title(context: Context, textRes: Int): TextView = TextView(context).apply {
        text = context.getString(textRes)
        textSize = 19f
    }

    fun section(context: Context, textRes: Int): TextView = TextView(context).apply {
        text = context.getString(textRes)
        textSize = 16f
        setPadding(0, dp(context, 18f), 0, dp(context, 6f))
    }

    fun body(context: Context, textRes: Int): TextView = bodyText(context, context.getString(textRes))

    fun bodyText(context: Context, text: CharSequence): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setPadding(0, dp(context, 4f), 0, dp(context, 4f))
    }

    fun checkBox(context: Context, textRes: Int): CheckBox = CheckBox(context).apply {
        text = context.getString(textRes)
    }

    fun button(context: Context, textRes: Int): Button = Button(context).apply {
        text = context.getString(textRes)
    }

    fun dp(context: Context, value: Float): Int = (value * context.resources.displayMetrics.density).toInt()
}
