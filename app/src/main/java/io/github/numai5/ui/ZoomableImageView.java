package io.github.numai5.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * 支持双指缩放 / 单指拖动 / 双击复位的 ImageView（全屏预览用）。
 * 参考常见 Matrix 手势实现：足够稳定，不引入第三方库。
 */
public class ZoomableImageView extends ImageView {

    private final Matrix matrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final PointF lastTouch = new PointF();
    private long lastDoubleTap;
    private boolean dragging;
    private float maxScale = 6f;
    private float minScale = 1f;

    public ZoomableImageView(Context context) {
        this(context, null);
    }

    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        reset();
    }

    /** 重置为恰好容纳的初始状态 */
    public void reset() {
        float viewW = getWidth();
        float viewH = getHeight();
        float drawW = getDrawable() == null ? 1 : getDrawable().getIntrinsicWidth();
        float drawH = getDrawable() == null ? 1 : getDrawable().getIntrinsicHeight();
        if (drawW <= 0 || drawH <= 0 || viewW <= 0 || viewH <= 0) return;
        float scale = Math.min(viewW / drawW, viewH / drawH);
        minScale = scale;
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate((viewW - drawW * scale) / 2f, (viewH - drawH * scale) / 2f);
        setImageMatrix(matrix);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                lastTouch.set(ev.getX(), ev.getY());
                dragging = true;
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging && !scaleDetector.isInProgress()) {
                    float dx = ev.getX() - lastTouch.x;
                    float dy = ev.getY() - lastTouch.y;
                    matrix.postTranslate(dx, dy);
                    lastTouch.set(ev.getX(), ev.getY());
                    setImageMatrix(matrix);
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                long now = System.currentTimeMillis();
                if (now - lastDoubleTap < 350) {
                    toggleZoom(ev.getX(), ev.getY());
                    lastDoubleTap = 0;
                } else {
                    lastDoubleTap = now;
                }
                dragging = false;
                break;
            }
        }
        return true;
    }

    /** 双击放大 / 缩小 */
    private void toggleZoom(float focusX, float focusY) {
        float[] values = new float[9];
        matrix.getValues(values);
        float current = values[Matrix.MSCALE_X];
        if (current > minScale * 1.5f) {
            zoomTo(minScale, focusX, focusY);
        } else {
            zoomTo(Math.min(maxScale, minScale * 2.5f), focusX, focusY);
        }
    }

    private void zoomTo(float target, float focusX, float focusY) {
        float[] values = new float[9];
        matrix.getValues(values);
        float current = values[Matrix.MSCALE_X];
        float ratio = target / current;
        matrix.postScale(ratio, ratio, focusX, focusY);
        setImageMatrix(matrix);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float[] values = new float[9];
            matrix.getValues(values);
            float current = values[Matrix.MSCALE_X];
            float next = current * detector.getScaleFactor();
            if (next < minScale || next > maxScale) return true;
            matrix.postScale(detector.getScaleFactor(), detector.getScaleFactor(),
                    detector.getFocusX(), detector.getFocusY());
            setImageMatrix(matrix);
            return true;
        }
    }
}
