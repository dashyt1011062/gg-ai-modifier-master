package com.yl.aigg.ai_gg666

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/** Lightweight equivalent of AGG's android.fix.WrapLayout used by result rows. */
class AggWrapLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams =
        if (p == null) generateDefaultLayoutParams() else MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else {
            (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)
        }

        var lineWidth = 0
        var lineHeight = 0
        var measuredWidth = 0
        var measuredHeight = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, measuredHeight)
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin

            if (lineWidth > 0 && lineWidth + childWidth > availableWidth) {
                measuredWidth = maxOf(measuredWidth, lineWidth)
                measuredHeight += lineHeight
                lineWidth = childWidth
                lineHeight = childHeight
            } else {
                lineWidth += childWidth
                lineHeight = maxOf(lineHeight, childHeight)
            }
        }
        measuredWidth = maxOf(measuredWidth, lineWidth)
        measuredHeight += lineHeight

        val desiredWidth = measuredWidth + paddingLeft + paddingRight
        val desiredHeight = measuredHeight + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val availableRight = right - left - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            val totalWidth = lp.leftMargin + childWidth + lp.rightMargin
            val totalHeight = lp.topMargin + childHeight + lp.bottomMargin

            if (x > paddingLeft && x + totalWidth > availableRight) {
                x = paddingLeft
                y += lineHeight
                lineHeight = 0
            }
            val childLeft = x + lp.leftMargin
            val childTop = y + lp.topMargin
            child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
            x += totalWidth
            lineHeight = maxOf(lineHeight, totalHeight)
        }
    }
}
