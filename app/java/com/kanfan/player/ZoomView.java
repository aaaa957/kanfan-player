package com.kanfan.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * 看图的画布：双击/双指缩放、放大后拖动，缩放为 1 时左右滑翻页。
 */
public class ZoomView extends View {

    public interface Listener {
        /** direction: -1 上一张，+1 下一张 */
        void onSwipe(int direction);

        void onSingleTap();
    }

    private Drawable drawable;
    private Listener listener;

    private float baseScale = 1f;
    private float userScale = 1f;
    private float tx = 0, ty = 0;
    private float downX, downY, startTx, startTy;
    private boolean multiTouch;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;

    public ZoomView(Context c) {
        super(c);
        setBackgroundColor(0xFF000000);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float next = userScale * d.getScaleFactor();
                next = Math.max(1f, Math.min(5f, next));
                userScale = next;
                clamp();
                invalidate();
                return true;
            }
        });
        tapDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) listener.onSingleTap();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                userScale = userScale > 1.2f ? 1f : 2.5f;
                layoutCenter();
                invalidate();
                return true;
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setDrawable(Drawable d) {
        if (drawable instanceof Animatable) {
            try {
                ((Animatable) drawable).stop();
            } catch (Exception ignored) {
            }
        }
        drawable = d;
        userScale = 1f;
        if (drawable != null) {
            drawable.setCallback(this);
            if (drawable instanceof Animatable) {
                try {
                    ((Animatable) drawable).start();
                } catch (Exception ignored) {
                }
            }
        }
        layoutCenter();
        invalidate();
    }

    public boolean hasImage() {
        return drawable != null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutCenter();
    }

    /** 回到「适配屏幕并居中」 */
    private void layoutCenter() {
        if (drawable == null || getWidth() <= 0 || getHeight() <= 0) return;
        int dw = Math.max(1, drawable.getIntrinsicWidth());
        int dh = Math.max(1, drawable.getIntrinsicHeight());
        baseScale = Math.min(getWidth() / (float) dw, getHeight() / (float) dh);
        // 小图不放大，避免糊
        if (baseScale > 1f) baseScale = 1f;
        userScale = 1f;
        float s = baseScale;
        tx = (getWidth() - dw * s) / 2f;
        ty = (getHeight() - dh * s) / 2f;
    }

    /** 限制边界：比屏幕小就居中，比屏幕大就不许拖出空白 */
    private void clamp() {
        if (drawable == null) return;
        float s = baseScale * userScale;
        float dw = drawable.getIntrinsicWidth() * s;
        float dh = drawable.getIntrinsicHeight() * s;
        if (dw <= getWidth()) tx = (getWidth() - dw) / 2f;
        else tx = Math.max(getWidth() - dw, Math.min(0, tx));
        if (dh <= getHeight()) ty = (getHeight() - dh) / 2f;
        else ty = Math.max(getHeight() - dh, Math.min(0, ty));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (drawable == null) return;
        float s = baseScale * userScale;
        canvas.save();
        canvas.translate(tx, ty);
        canvas.scale(s, s);
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        drawable.draw(canvas);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        tapDetector.onTouchEvent(e);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                startTx = tx;
                startTy = ty;
                multiTouch = false;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() > 1) multiTouch = true;
                if (!multiTouch && userScale > 1.01f) {
                    tx = startTx + (e.getX() - downX);
                    ty = startTy + (e.getY() - downY);
                    clamp();
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (!multiTouch && userScale <= 1.01f
                        && Math.abs(dx) > Ui.dp(getContext(), 56)
                        && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    if (listener != null) listener.onSwipe(dx < 0 ? 1 : -1);
                }
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (drawable instanceof Animatable) {
            try {
                ((Animatable) drawable).stop();
            } catch (Exception ignored) {
            }
        }
        super.onDetachedFromWindow();
    }

    /** 供 Activity 判断当前是否处于放大状态 */
    public boolean isZoomed() {
        return userScale > 1.01f;
    }
}
