package com.kanfan.player;

import android.os.Build;
import android.text.Layout;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ImageSpan;
import android.widget.TextView;

/**
 * 分页：给定起始偏移，算出「这一页最多能显示到哪个字符」。
 * 用 StaticLayout 按真实排版测量，因此字号、行距、插图都算在内。
 */
public final class Pager {

    private Pager() {
    }

    /**
     * @param view    用于取当前 TextPaint（字号、字体）
     * @param spacing 行距倍数，需与 TextView 的 setLineSpacing 保持一致
     * @return 本页能放下的**字符数**（相对 start 的长度），至少为 1
     */
    public static int pageChars(TextView view, CharSequence text, int start,
                                int width, int height, float spacing) {
        if (text == null) return 0;
        int len = text.length();
        if (start >= len) return len;
        if (width <= 0 || height <= 0) return len;

        CharSequence sub = text.subSequence(start, len);
        TextPaint paint = view.getPaint();
        StaticLayout sl;
        if (Build.VERSION.SDK_INT >= 23) {
            sl = StaticLayout.Builder.obtain(sub, 0, sub.length(), paint, width)
                    .setIncludePad(false)
                    .setLineSpacing(0f, spacing)
                    .build();
        } else {
            sl = new StaticLayout(sub, paint, width, Layout.Alignment.ALIGN_NORMAL,
                    1f, spacing, true);
        }

        int end;
        if (sl.getHeight() <= height) {
            end = sub.length();
        } else {
            int line = sl.getLineForVertical(height);
            while (line > 0 && sl.getLineBottom(line) > height) line--;
            end = sl.getLineEnd(Math.max(0, line));
            if (end <= 0) end = 1;
        }

        // 不要把插图切成两半：如果断点在图片中间，就整张挪到下一页
        if (text instanceof Spanned) {
            Spanned sp = (Spanned) text;
            ImageSpan[] imgs = sp.getSpans(start, start + end, ImageSpan.class);
            for (ImageSpan im : imgs) {
                int s = sp.getSpanStart(im);
                int e = sp.getSpanEnd(im);
                if (s > start && s < start + end && e > start + end) {
                    end = s - start;
                    break;
                }
            }
        }
        if (end <= 0) end = 1;
        return Math.min(len - start, end);
    }
}