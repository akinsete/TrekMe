package com.peterlaurence.trekadvisor.menu.mapview.components;


import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.HashSet;

/**
 * This is a modified version of {@link com.qozix.tileview.paths.CompositePathView}, using
 * {@code Canvas.drawLines} to draw a path. This method is much more efficient as it's
 * hardware accelerated, although the result is not as neat as the original implementation (which
 * uses {@link Path}.
 *
 * @author peterLaurence on 19/02/17
 */
public class PathView extends View {

    private static final int DEFAULT_STROKE_COLOR = 0xFF000000;
    private static final int DEFAULT_STROKE_WIDTH = 15;

    private float mScale = 1;

    private boolean mShouldDraw = true;

    private HashSet<DrawablePath> mDrawablePaths = new HashSet<>();

    private Paint mDefaultPaint = new Paint();

    {
        mDefaultPaint.setStyle(Paint.Style.STROKE);
        mDefaultPaint.setColor(DEFAULT_STROKE_COLOR);
        mDefaultPaint.setStrokeWidth(DEFAULT_STROKE_WIDTH);
        mDefaultPaint.setAntiAlias(true);
        mDefaultPaint.setStrokeJoin(Paint.Join.ROUND);
        mDefaultPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public PathView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    public float getScale() {
        return mScale;
    }

    public void setScale(float scale) {
        mScale = scale;
        invalidate();
    }

    public Paint getDefaultPaint() {
        return mDefaultPaint;
    }

    public void addPath(float[] path, Paint paint) {
        if (paint == null) {
            paint = mDefaultPaint;
        }
        DrawablePath DrawablePath = new DrawablePath();
        DrawablePath.path = path;
        DrawablePath.paint = paint;
        addPath(DrawablePath);
    }

    public void addPath(DrawablePath drawablePath) {
        mDrawablePaths.add(drawablePath);
        invalidate();
    }

    public void removePath(DrawablePath path) {
        mDrawablePaths.remove(path);
        invalidate();
    }

    public void clear() {
        mDrawablePaths.clear();
        invalidate();
    }

    public void setShouldDraw(boolean shouldDraw) {
        mShouldDraw = shouldDraw;
        invalidate();
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (mShouldDraw) {
            for (DrawablePath drawablePath : mDrawablePaths) {
                canvas.scale(mScale, mScale);
                drawablePath.paint.setStrokeWidth(drawablePath.width / mScale);
                canvas.drawLines(drawablePath.path, drawablePath.paint);
            }
        }
        super.onDraw(canvas);
    }

    public static class DrawablePath {

        /**
         * The path that this drawable will follow.
         */
        public float[] path;

        /**
         * The paint to be used for this path.
         */
        public Paint paint;

        /**
         * The width of the path
         */
        public float width = DEFAULT_STROKE_WIDTH;
    }
}