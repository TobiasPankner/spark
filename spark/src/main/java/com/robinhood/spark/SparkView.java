/**
 * Copyright (C) 2016 Robinhood Markets, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.robinhood.spark;

import android.animation.Animator;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.robinhood.spark.animation.LineSparkAnimator;
import com.robinhood.spark.animation.SparkAnimator;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A {@link SparkView} is a simplified line chart with no axes.
 */
public class SparkView extends View implements ScrubGestureDetector.ScrubListener {
    private static final String TAG = "Spark";

    /**
     * Holds the fill type constants to be used with {@linkplain #getFillType()} and
     * {@linkplain #setFillType(int)}
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            FillType.NONE,
            FillType.UP,
            FillType.DOWN,
            FillType.TOWARD_ZERO,
    })
    public @interface FillType {
        /**
         * Fill type constant for having no fill on the graph
         */
        int NONE = 0;

        /**
         * Fill type constant for always filling the area above the sparkline.
         */
        int UP = 1;

        /**
         * Fill type constant for always filling the area below the sparkline
         */
        int DOWN = 2;

        /**
         * Fill type constant for filling toward zero. This will fill downward if your sparkline is
         * positive, or upward if your sparkline is negative. If your sparkline intersects zero,
         * each segment will still color toward zero.
         */
        int TOWARD_ZERO = 3;
    }

    // styleable values
    @ColorInt private int lineColor;
    @ColorInt private int fillColor;
    private float lineWidth;
    private float cornerRadius;
    @FillType private int fillType = FillType.NONE;
    @ColorInt private int baseLineColor;
    private float baseLineWidth;
    @ColorInt private int scrubLineColor;
    private float scrubLineWidth;
    private boolean scrubEnabled;
    private boolean scrubActive = false;
    private @Nullable SparkAnimator sparkAnimator;

    // the onDraw data
    private final Path renderPath = new Path();
    private final Path sparkPath = new Path();
    private final Path baseLinePath = new Path();
    private final Path scrubLinePath = new Path();
    private final Path greyLinePath = new Path();

    // adapter
    private @Nullable SparkAdapter adapter;

    // misc fields
    private ScaleHelper scaleHelper;
    private Paint sparkLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint sparkFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint baseLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint scrubLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint greyLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private @Nullable OnScrubListener scrubListener;
    private @NonNull ScrubGestureDetector scrubGestureDetector;
    private @Nullable Animator pathAnimator;
    private final RectF contentRect = new RectF();
    private Rect textBounds = new Rect();
    DecimalFormat df = new DecimalFormat("#.#");

    private List<Float> xPoints;
    private List<Float> yPoints;

    public SparkView(Context context) {
        super(context);
        init(context, null, R.attr.spark_SparkViewStyle, R.style.spark_SparkView);
    }

    public SparkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, R.attr.spark_SparkViewStyle, R.style.spark_SparkView);
    }

    public SparkView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr, R.style.spark_SparkView);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public SparkView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs, defStyleAttr, defStyleRes);
    }

    private void init(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SparkView,
                defStyleAttr, defStyleRes);

        lineColor = a.getColor(R.styleable.SparkView_spark_lineColor, 0);
        fillColor = a.getColor(R.styleable.SparkView_spark_fillColor, 0);
        lineWidth = a.getDimension(R.styleable.SparkView_spark_lineWidth, 0);
        cornerRadius = a.getDimension(R.styleable.SparkView_spark_cornerRadius, 0);

        // for backwards compatibility, set fill type based on spark_fill first, then overwrite if
        // new spark_fillType attribute is set
        int legacyFill = a.getBoolean(R.styleable.SparkView_spark_fill, false)
                ? FillType.DOWN
                : FillType.NONE;
        int fillType = a.getInt(R.styleable.SparkView_spark_fillType, legacyFill);
        setFillType(fillType);

        baseLineColor = a.getColor(R.styleable.SparkView_spark_baseLineColor, 0);
        baseLineWidth = a.getDimension(R.styleable.SparkView_spark_baseLineWidth, 0);
        scrubEnabled = a.getBoolean(R.styleable.SparkView_spark_scrubEnabled, true);
        scrubLineColor = a.getColor(R.styleable.SparkView_spark_scrubLineColor, baseLineColor);
        scrubLineWidth = a.getDimension(R.styleable.SparkView_spark_scrubLineWidth, lineWidth);
        boolean animateChanges = a.getBoolean(R.styleable.SparkView_spark_animateChanges, false);
        a.recycle();

        sparkLinePaint.setStyle(Paint.Style.STROKE);
        sparkLinePaint.setColor(lineColor);
        sparkLinePaint.setStrokeWidth(lineWidth);
        sparkLinePaint.setStrokeCap(Paint.Cap.ROUND);
        if (cornerRadius != 0) {
            sparkLinePaint.setPathEffect(new CornerPathEffect(cornerRadius));
        }

        greyLinePaint.setStyle(Paint.Style.STROKE);
        greyLinePaint.setColor(Color.argb(255, 110, 110, 120));
        greyLinePaint.setStrokeWidth(lineWidth);
        greyLinePaint.setStrokeCap(Paint.Cap.ROUND);
        if (cornerRadius != 0) {
            greyLinePaint.setPathEffect(new CornerPathEffect(cornerRadius));
        }

        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(scrubLineColor);
        textPaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setTextSize(40);
        textPaint.setTextAlign(Paint.Align.LEFT);

        sparkFillPaint.set(sparkLinePaint);
        sparkFillPaint.setColor(fillColor);
        sparkFillPaint.setStyle(Paint.Style.FILL);
        sparkFillPaint.setStrokeWidth(0);

        baseLinePaint.setStyle(Paint.Style.STROKE);
        baseLinePaint.setColor(baseLineColor);
        baseLinePaint.setStrokeWidth(baseLineWidth);

        scrubLinePaint.setStyle(Paint.Style.STROKE);
        scrubLinePaint.setStrokeWidth(scrubLineWidth);
        scrubLinePaint.setColor(scrubLineColor);
        scrubLinePaint.setStrokeCap(Paint.Cap.ROUND);

        df.setMaximumFractionDigits(6);

        final Handler handler = new Handler();
        final float touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        scrubGestureDetector = new ScrubGestureDetector(this, handler, touchSlop);
        scrubGestureDetector.setEnabled(scrubEnabled);
        setOnTouchListener(scrubGestureDetector);

        xPoints = new ArrayList<>();
        yPoints = new ArrayList<>();

        if (isInEditMode()) {
            this.setAdapter(new SparkAdapter() {
                private final float[] yData = new float[] {68,22,31,57,35,79,86,47,34,55,80,72,99,66,47,42,56,64,66,80,97,10,43,12,25,71,47,73,49,36};
                @Override public int getCount() { return yData.length; }
                @NonNull @Override public Object getItem(int index) { return yData[index]; }
                @Override public float getY(int index) { return yData[index]; }
                @Override public List<Float> getAll() {
                    List<Float> result = new ArrayList<>(yData.length);
                    for (float f : yData) {
                        result.add(f);
                    }
                    return result;
                }
            });
        }

        // for backward support
        if (animateChanges) {
            sparkAnimator = new LineSparkAnimator();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        updateContentRect();
        populatePath();
    }

    /**
     * Populates the {@linkplain #sparkPath} with points
     */
    private void populatePath() {
        if (adapter == null) return;
        if (getWidth() == 0 || getHeight() == 0) return;

        final int adapterCount = adapter.getCount();

        // to draw anything, we need 2 or more points
        if (adapterCount < 2) {
            clearData();
            return;
        }

        scaleHelper = new ScaleHelper(adapter, contentRect, lineWidth, isFillInternal());

        xPoints.clear();
        yPoints.clear();

        // make our main graph path
        sparkPath.reset();
        for (int i = 0; i < adapterCount; i++) {
            final float x = scaleHelper.getX(adapter.getX(i));
            final float y = scaleHelper.getY(adapter.getY(i));

            // points to render graphic
            // get points to animate
            xPoints.add(x);
            yPoints.add(y);

            if (i == 0) {
                sparkPath.moveTo(x, y);
            } else {
                sparkPath.lineTo(x, y);
            }

        }

        // if we're filling the graph in, close the path's circuit
        final Float fillEdge = getFillEdge();
        if (fillEdge != null) {
            final float lastX = scaleHelper.getX(adapter.getX(adapter.getCount() - 1));
            // line up or down to the fill edge
            sparkPath.lineTo(lastX, fillEdge);
            // line straight left to far edge of the view
            sparkPath.lineTo(getPaddingStart(), fillEdge);
            // closes line back on the first point
            sparkPath.close();
        }

        // make our base line path
        baseLinePath.reset();
        if (adapter.hasBaseLine()) {
            float scaledBaseLine = scaleHelper.getY(adapter.getBaseLine());
            baseLinePath.moveTo(0, scaledBaseLine);
            baseLinePath.lineTo(getWidth(), scaledBaseLine);
        }

        renderPath.reset();
        renderPath.addPath(sparkPath);

        invalidate();
    }

    private float lineFunction(float x1, float y1, float x2, float y2, float x){
        return y1 + ((y2 - y1)/(x2 - x1))*(x - x1);
    }


    private void createGreyLine(float xOfScrape){
        if (adapter == null) return;
        if (getWidth() == 0 || getHeight() == 0) return;

        final int adapterCount = adapter.getCount();

        // make our main graph path
        scaleHelper = new ScaleHelper(adapter, contentRect, lineWidth, isFillInternal());

        greyLinePath.reset();
        int cnt = 0;
        for (int i = 0; i < adapterCount; i++) {
            final float x = scaleHelper.getX(adapter.getX(i));
            final float y = scaleHelper.getY(adapter.getY(i));

            if(x < xOfScrape)
                continue;

            if(cnt == 0) {
                try {
                    final float prevX = scaleHelper.getX(adapter.getX(i-1));
                    final float prevY = scaleHelper.getY(adapter.getY(i-1));

                    final float calculatedY = lineFunction(prevX, prevY, x, y, xOfScrape);
                    greyLinePath.moveTo(xOfScrape, calculatedY);
                    greyLinePath.lineTo(x, y);
                }
                catch (Exception e){
                    greyLinePath.moveTo(x, y);
                }
            } else {
                greyLinePath.lineTo(x, y);
            }
            cnt ++;
        }
    }

    @Nullable
    private Float getFillEdge() {
        switch (fillType) {
            case FillType.NONE:
                return null;
            case FillType.UP:
                return (float) getPaddingTop();
            case FillType.DOWN:
                return (float) getHeight() - getPaddingBottom();
            case FillType.TOWARD_ZERO:
                float zero = scaleHelper.getY(0F);
                float bottom = (float) getHeight() - getPaddingBottom();
                return Math.min(zero, bottom);
            default:
                throw new IllegalStateException(
                        String.format(Locale.US, "Unknown fill-type: %d", fillType)
                );
        }
    }

    /**
     * Get the scaled (pixel) coordinate of your given x value. If no scale is currently computed
     * (for instance {@link SparkAdapter} has not been set or has less than 2 points of data). This
     * method will return the unscaled value.
     *
     * @param x    the value to scale (should be the same units as your graph's data points)
     * @return the pixel coordinates of where this point is located in SparkView's bounds
     */
    public float getScaledX(float x) {
        if (scaleHelper == null) {
            Log.w(TAG, "getScaledX() - no scale available yet.");
            return x;
        }
        return scaleHelper.getX(x);
    }

    /**
     * Get the scaled (pixel) coordinate of your given y value. If no scale is currently computed
     * (for instance {@link SparkAdapter} has not been set or has less than 2 points of data). This
     * method will return the unscaled value.
     *
     * @param y    the value to scale (should be the same units as your graph's data points)
     * @return the pixel coordinates of where this point is located in SparkView's bounds
     */
    public float getScaledY(float y) {
        if (scaleHelper == null) {
            Log.w(TAG, "getScaledX() - no scale available yet.");
            return y;
        }
        return scaleHelper.getY(y);
    }

    /**
     * Gets a copy of the sparkline path
     */
    @NonNull
    public Path getSparkLinePath() {
        return new Path(sparkPath);
    }

    /**
     * Set the path to animate in onDraw, used for getAnimation purposes
     */
    public void setAnimationPath(@NonNull Path animationPath) {
        this.renderPath.reset();
        this.renderPath.addPath(animationPath);
        this.renderPath.rLineTo(0, 0);

        invalidate();
    }

    private void setScrubLine(float x) {
        createGreyLine(x);
        x = resolveBoundedScrubLine(x);
        scrubLinePath.reset();
        scrubLinePath.moveTo(x, 0);
        scrubLinePath.lineTo(x, getHeight());
        invalidate();
    }

    /**
     * Bounds the x coordinate of a scrub within the bounding rect minus padding and line width.
     */
    private float resolveBoundedScrubLine(float x) {
        float scrubLineOffset = scrubLineWidth / 2;

        float leftBound = getPaddingStart() + scrubLineOffset;
        if (x < leftBound) {
            return leftBound;
        }

        float rightBound = getWidth() - getPaddingEnd() - scrubLineOffset;
        if (x > rightBound) {
            return rightBound;
        }

        return x;
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        super.setPadding(left, top, right, bottom);
        updateContentRect();
        populatePath();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!scrubActive)
            canvas.drawPath(baseLinePath, baseLinePaint);

        if(fillType != FillType.NONE){
            canvas.drawPath(renderPath, sparkFillPaint);
        }

        canvas.drawPath(renderPath, sparkLinePaint);
        canvas.drawPath(greyLinePath, greyLinePaint);
        canvas.drawPath(scrubLinePath, scrubLinePaint);

        if (adapter == null || scrubActive)
            return;

        List<Float> allValues = (List<Float>) adapter.getAll();
        if (allValues.isEmpty())
            return;

        float maxValue = Collections.max(allValues);
        float minValue = Collections.min(allValues);

        int maxIndex = allValues.indexOf(maxValue);
        int minIndex = allValues.indexOf(minValue);

        if(maxIndex < 0 || minIndex < 0)
            return;

        String maxText = df.format(Double.parseDouble(Float.toString(round(maxValue))));
        String minText = df.format(Double.parseDouble(Float.toString(round(minValue))));

        float maxX = scaleHelper.getX(adapter.getX(maxIndex)) - (textPaint.measureText(maxText)/2);
        float maxY = scaleHelper.getY(adapter.getY(maxIndex)) - 25;
        // Make sure the text is at least 5px from the end of the canvas
        if(getWidth() - (maxX + textPaint.measureText(maxText)) < 5)
            maxX = getWidth() -  textPaint.measureText(maxText) - 5;
        else if (maxX < 5)
            maxX = 5;

        textPaint.getTextBounds(minText, 0, minText.length(), textBounds);
        int minTextHeight = textBounds.height();
        float minX = scaleHelper.getX(adapter.getX(minIndex)) - (textPaint.measureText(minText)/2);
        float minY = scaleHelper.getY(adapter.getY(minIndex)) + minTextHeight + 25;
        // Make sure the text is at least 5px from the start of the canvas
        if(getWidth() - (minX + textPaint.measureText(minText)) < 5)
            minX = getWidth() -  textPaint.measureText(minText) - 5;
        else if (minX < 5)
            minX = 5;

        canvas.drawText(minText, minX , minY, textPaint);
        canvas.drawText(maxText, maxX , maxY, textPaint);
    }

    /**
     * Get the color of the sparkline
     */
    @ColorInt public int getLineColor() {
        return lineColor;
    }

    /**
     * Set the color of the sparkline
     */
    public void setLineColor(@ColorInt int lineColor) {
        this.lineColor = lineColor;
        sparkLinePaint.setColor(lineColor);
        invalidate();
    }

    /**
     * Get the color of the sparkline
     */
    @ColorInt public int getFillColor() {
        return fillColor;
    }

    /**
     * Set the color of the sparkline
     */
    public void setFillColor(@ColorInt int fillColor) {
        this.fillColor = fillColor;
        sparkFillPaint.setColor(fillColor);
        invalidate();
    }

    /**
     * Get the width in pixels of the sparkline's stroke
     */
    public float getLineWidth() {
        return lineWidth;
    }

    /**
     * Set the width in pixels of the sparkline's stroke
     */
    public void setLineWidth(float lineWidth) {
        this.lineWidth = lineWidth;
        sparkLinePaint.setStrokeWidth(lineWidth);
        invalidate();
    }

    /**
     * Get the corner radius in pixels used when rounding the sparkline's segments.
     */
    public float getCornerRadius() {
        return cornerRadius;
    }

    /**
     * Set the corner radius in pixels to use when rounding the sparkline's segments. Passing 0
     * indicates that corners should not be rounded.
     */
    public void setCornerRadius(float cornerRadius) {
        this.cornerRadius = cornerRadius;
        if (cornerRadius != 0) {
            sparkLinePaint.setPathEffect(new CornerPathEffect(cornerRadius));
            sparkFillPaint.setPathEffect(new CornerPathEffect(cornerRadius));
        } else {
            sparkLinePaint.setPathEffect(null);
            sparkFillPaint.setPathEffect(null);
        }
        invalidate();
    }

    /**
     * Animator class to animate Spark
     * @return a {@link SparkAnimator} or null
     */
    @Nullable
    public SparkAnimator getSparkAnimator() {
        return sparkAnimator;
    }

    /**
     * Animator class to animate Spark
     * @param sparkAnimator - a {@link SparkAnimator}
     */
    public void setSparkAnimator(@Nullable SparkAnimator sparkAnimator) {
        this.sparkAnimator = sparkAnimator;
    }

    /**
     * Get the {@link Paint} used to draw the scrub line. Any custom modifications to this
     * {@link Paint} will not reflect until the next call to {@link #invalidate()}
     */
    @NonNull
    public Paint getScrubLinePaint() {
        return scrubLinePaint;
    }

    /**
     * Set the {@link Paint} to be used to draw the scrub line. Warning: setting a paint other than
     * the instance returned by {@link #getScrubLinePaint()} may result in loss of style attributes
     * specified on this view.
     */
    public void setScrubLinePaint(@NonNull Paint scrubLinePaint) {
        this.scrubLinePaint = scrubLinePaint;
        invalidate();
    }

    /**
     * Return whether or not this sparkline should fill the area underneath.
     *
     * @deprecated use {@link #getFillType()} instead
     */
    public boolean isFill() {
        switch (fillType) {
            case FillType.NONE:
                return false;
            case FillType.UP:
            case FillType.DOWN:
            case FillType.TOWARD_ZERO:
                return true;
            default:
                throw new IllegalStateException(
                        String.format(Locale.US, "Unknown fill-type: %d", fillType)
                );
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isFillInternal() {
        return isFill();
    }

    @FillType
    public int getFillType() {
        return fillType;
    }

    /**
     * Set whether or not this sparkline should fill the area underneath.
     *
     * @deprecated use {@link #setFillType(int)} instead
     */
    @Deprecated
    public void setFill(boolean fill) {
        setFillType(fill ? FillType.DOWN : FillType.NONE);
    }

    public void setFillType(@FillType int fillType) {
        if (this.fillType != fillType) {
            this.fillType = fillType;
            populatePath();
        }
    }

    /**
     * Get the {@link Paint} used to draw the sparkline. Any modifications to this {@link Paint}
     * will not reflect until the next call to {@link #invalidate()}
     */
    @NonNull
    public Paint getSparkLinePaint() {
        return sparkLinePaint;
    }

    /**
     * Set the {@link Paint} to be used to draw the sparkline. Warning: setting a paint other than
     * the instance returned by {@link #getSparkLinePaint()} may result in loss of style attributes
     * specified on this view.
     */
    public void setSparkLinePaint(@NonNull Paint pathPaint) {
        this.sparkLinePaint = pathPaint;
        invalidate();
    }

    /**
    /**
     * Set the {@link Paint} to be used to draw the spark fill. Warning: setting a paint other than
     * the instance returned by {@link #getSparkFillPaint()} may result in loss of style attributes
     * specified on this view.
     */
    public void setSparkFillPaint(@NonNull Paint pathPaint) {
        this.sparkFillPaint = pathPaint;
        invalidate();
    }

    /**
     * Get the {@link Paint} used to draw the spark fill. Any modifications to this {@link Paint}
     * will not reflect until the next call to {@link #invalidate()}
     */
    @NonNull
    public Paint getSparkFillPaint() {
        return sparkFillPaint;
    }
    /**
     * Get the color of the base line
     */
    @ColorInt public int getBaseLineColor() {
        return baseLineColor;
    }

    /**
     * Set the color of the base line
     */
    public void setBaseLineColor(@ColorInt int baseLineColor) {
        this.baseLineColor = baseLineColor;
        baseLinePaint.setColor(baseLineColor);
        invalidate();
    }

    /**
     * Get the width in pixels of the base line's stroke
     */
    public float getBaseLineWidth() {
        return baseLineWidth;
    }

    /**
     * Set the width in pixels of the base line's stroke
     */
    public void setBaseLineWidth(float baseLineWidth) {
        this.baseLineWidth = baseLineWidth;
        baseLinePaint.setStrokeWidth(baseLineWidth);
        invalidate();
    }

    /**
     * Get the {@link Paint} used to draw the base line. Any modifications to this {@link Paint}
     * will not reflect until the next call to {@link #invalidate()}
     */
    @NonNull
    public Paint getBaseLinePaint() {
        return baseLinePaint;
    }

    /**
     * Set the {@link Paint} to be used to draw the base line. Warning: setting a paint other than
     * the instance returned by {@link #getBaseLinePaint()} ()} may result in loss of style
     * attributes specified on this view.
     */
    public void setBaseLinePaint(@NonNull Paint baseLinePaint) {
        this.baseLinePaint = baseLinePaint;
        invalidate();
    }

    /**
     * Get the color of the scrub line
     */
    @ColorInt public int getScrubLineColor() {
        return scrubLineColor;
    }

    /**
     * Set the color of the scrub line
     */
    public void setScrubLineColor(@ColorInt int scrubLineColor) {
        this.scrubLineColor = scrubLineColor;
        scrubLinePaint.setColor(scrubLineColor);
        invalidate();
    }

    /**
     * Get the width in pixels of the scrub line's stroke
     */
    public float getScrubLineWidth() {
        return scrubLineWidth;
    }

    /**
     * Set the width in pixels of the scrub line's stroke
     */
    public void setScrubLineWidth(float scrubLineWidth) {
        this.scrubLineWidth = scrubLineWidth;
        scrubLinePaint.setStrokeWidth(scrubLineWidth);
        invalidate();
    }

    /**
     * Return true if scrubbing is enabled on this view
     */
    public boolean isScrubEnabled() {
        return scrubEnabled;
    }

    /**
     * Set whether or not to enable scrubbing on this view.
     */
    public void setScrubEnabled(boolean scrubbingEnabled) {
        this.scrubEnabled = scrubbingEnabled;
        scrubGestureDetector.setEnabled(scrubbingEnabled);
        invalidate();
    }

    /**
     * Get the current {@link OnScrubListener}
     */
    @Nullable
    public OnScrubListener getScrubListener() {
        return scrubListener;
    }

    /**
     * Set a {@link OnScrubListener} to be notified of the user's scrubbing gestures.
     */
    public void setScrubListener(@Nullable OnScrubListener scrubListener) {
        this.scrubListener = scrubListener;
    }

    /**
     * Get the backing {@link SparkAdapter}
     */
    @Nullable
    public SparkAdapter getAdapter() {
        return adapter;
    }

    /**
     * Sets the backing {@link SparkAdapter} to generate the points to be graphed
     */
    public void setAdapter(@Nullable SparkAdapter adapter) {
        if (this.adapter != null) {
            this.adapter.unregisterDataSetObserver(dataSetObserver);
        }
        this.adapter = adapter;
        if (this.adapter != null) {
            this.adapter.registerDataSetObserver(dataSetObserver);
        }
        populatePath();
    }

    /**
     * Returns a copy of current graphic X points
     * @return current graphic X points
     */
    @NonNull
    public List<Float> getXPoints() {
        return new ArrayList<>(xPoints);
    }

    /**
     * Returns a copy of current graphic Y points
     * @return current graphic Y points
     */
    @NonNull
    public List<Float> getYPoints() {
        return new ArrayList<>(yPoints);
    }

    private void doPathAnimation() {
        if (pathAnimator != null) {
            pathAnimator.cancel();
        }

        pathAnimator = getAnimator();

        if (pathAnimator != null) {
            pathAnimator.start();
        }
    }

    @Nullable
    private Animator getAnimator() {
        if (sparkAnimator != null) {
            return sparkAnimator.getAnimation(this);
        }

        return null;
    }

    private void clearData() {
        scaleHelper = null;
        renderPath.reset();
        sparkPath.reset();
        baseLinePath.reset();
        greyLinePath.reset();
        invalidate();
    }

    /**
     * Helper class for handling scaling logic.
     */
    static class ScaleHelper {
        // the width and height of the view
        final float width, height;
        final int size;
        // the scale factor for the Y values
        final float xScale, yScale;
        // translates the Y values back into the bounding rect after being scaled
        final float xTranslation, yTranslation;

        public ScaleHelper(SparkAdapter adapter, RectF contentRect, float lineWidth, boolean fill) {
            final float leftPadding = contentRect.left;
            final float topPadding = contentRect.top;

            // subtract lineWidth to offset for 1/2 of the line bleeding out of the content box on
            // either side of the view
            final float lineWidthOffset = fill ? 0 : lineWidth;
            this.width = contentRect.width() - lineWidthOffset;
            this.height = contentRect.height() - lineWidthOffset;

            this.size = adapter.getCount();

            // get data bounds from adapter
            RectF bounds = adapter.getDataBounds();

            // if data is a line (which technically has no size), expand bounds to center the data
            bounds.inset(bounds.width() == 0 ? -1 : 0, bounds.height() == 0 ? -1 : 0);

            final float minX = bounds.left;
            final float maxX = bounds.right;
            final float minY = bounds.top;
            final float maxY = bounds.bottom;

            // xScale will compress or expand the min and max x values to be just inside the view
            this.xScale = width / (maxX - minX);
            // xTranslation will move the x points back between 0 - width
            this.xTranslation = leftPadding - (minX * xScale) + (lineWidthOffset / 2);
            // yScale will compress or expand the min and max y values to be just inside the view
            this.yScale = height / (maxY - minY);
            // yTranslation will move the y points back between 0 - height
            this.yTranslation = minY * yScale + topPadding + (lineWidthOffset / 2);
        }

        /**
         * Given the 'raw' X value, scale it to fit within our view.
         */
        public float getX(float rawX) {
            return rawX * xScale + xTranslation;
        }

        /**
         * Given the 'raw' Y value, scale it to fit within our view. This method also 'flips' the
         * value to be ready for drawing.
         */
        public float getY(float rawY) {
            return height - (rawY * yScale) + yTranslation;
        }
    }

    @Override
    public int getPaddingStart() {
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN_MR1
                ? super.getPaddingStart()
                : getPaddingLeft();
    }

    @Override
    public int getPaddingEnd() {
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN_MR1
                ? super.getPaddingEnd()
                : getPaddingRight();
    }

    /**
     * Gets the rect representing the 'content area' of the view. This is essentially the bounding
     * rect minus any padding.
     */
    private void updateContentRect() {
        if (contentRect == null) return;

        contentRect.set(
                getPaddingStart(),
                getPaddingTop(),
                getWidth() - getPaddingEnd(),
                getHeight() - getPaddingBottom()
        );
    }

    /**
     * returns the nearest index (into {@link #adapter}'s data) for the given x coordinate.
     */
    static int getNearestIndex(List<Float> points, float x) {
        int index = Collections.binarySearch(points, x);

        // if binary search returns positive, we had an exact match, return that index
        if (index >= 0) return index;

        // otherwise, calculate the binary search's specified insertion index
        index = - 1 - index;

        // if we're inserting at 0, then our guaranteed nearest index is 0
        if (index == 0) return index;

        // if we're inserting at the very end, then our guaranteed nearest index is the final one
        if (index == points.size()) return --index;

        // otherwise we need to check which of our two neighbors we're closer to
        final float deltaUp = points.get(index) - x;
        final float deltaDown = x - points.get(index - 1);
        if (deltaUp > deltaDown) {
            // if the below neighbor is closer, decrement our index
            index--;
        }

        return index;
    }

    @Override
    public void onScrubbed(float x, float y) {
        if (adapter == null || adapter.getCount() == 0) return;
        if (scrubListener != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
            int index = getNearestIndex(xPoints, x);
            if (scrubListener != null) {
                scrubListener.onScrubbed(adapter.getItem(index));
            }
        }
        scrubActive = true;
        setScrubLine(x);
    }

    @Override
    public void onScrubEnded() {
        scrubLinePath.reset();
        greyLinePath.reset();
        if (scrubListener != null) scrubListener.onScrubbed(null);
        scrubActive = false;
        invalidate();
    }

    /**
     * Listener for a user scrubbing (dragging their finger along) the graph.
     */
    public interface OnScrubListener {
        /**
         * Indicates the user is currently scrubbing over the given value. A null value indicates
         * that the user has stopped scrubbing.
         */
        void onScrubbed(@Nullable Object value);
    }

    private final DataSetObserver dataSetObserver = new DataSetObserver() {
        @Override
        public void onChanged() {
            super.onChanged();
            populatePath();

            if (sparkAnimator != null) {
                doPathAnimation();
            }
        }

        @Override
        public void onInvalidated() {
            super.onInvalidated();
            clearData();
        }
    };

    private float round(float value){
        int places = -1;
        if (value >= 1)
            places = 2;
        else if (value >= 0.1)
            places = 4;

        if(places < 0)
            return value;

        BigDecimal bd = BigDecimal.valueOf(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.floatValue();
    }
}
