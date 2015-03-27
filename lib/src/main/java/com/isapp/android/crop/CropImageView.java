package com.isapp.android.crop;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.support.annotation.IntDef;
import android.util.AttributeSet;
import android.view.MotionEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

public class CropImageView extends ImageViewTouchBase {
    @IntDef({HANDLE_MODE_NEVER, HANDLE_MODE_CHANGING, HANDLE_MODE_ALWAYS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface HandleMode{}

    @IntDef({SHAPE_SQUARE, SHAPE_CIRCLE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Shape{}

    public static final int HANDLE_MODE_NEVER = 0;
    public static final int HANDLE_MODE_CHANGING = 1;
    public static final int HANDLE_MODE_ALWAYS = 2;

    public static final int SHAPE_SQUARE = 0;
    public static final int SHAPE_CIRCLE = 1;

    private static final int DEFAULT_HIGHLIGHT_COLOR = 0xFF33B5E5;
    private static final int DEFAULT_OUTSIDE_COLOR = 0x88252525;

    private ArrayList<HighlightView> highlightViews = new ArrayList<>();
    private HighlightView motionHighlightView;

    private boolean showThirds = false;
    private int highlightColor = 0xFF33B5E5;
    private int outsideColor = 0x88252525;

    @HandleMode private int handleMode = HANDLE_MODE_CHANGING;
    @Shape private int shape = SHAPE_SQUARE;

    private float lastX;
    private float lastY;
    private int motionEdge;

    private boolean saving = false;

    @SuppressWarnings("UnusedDeclaration")
    public CropImageView(Context context) {
        super(context);

        init(null);
    }

    @SuppressWarnings("UnusedDeclaration")
    public CropImageView(Context context, AttributeSet attrs) {
        super(context, attrs);

        init(attrs);
    }

    @SuppressWarnings("UnusedDeclaration")
    public CropImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        init(attrs);
    }

    private void init(AttributeSet attrs) {
        if (attrs != null) {
            TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.CropImageView);

            showThirds = a.getBoolean(R.styleable.CropImageView_crop_show_thirds, false);
            highlightColor = a.getColor(R.styleable.CropImageView_crop_highlight_color, DEFAULT_HIGHLIGHT_COLOR);
            outsideColor = a.getColor(R.styleable.CropImageView_crop_outside_color, DEFAULT_OUTSIDE_COLOR);
            //noinspection ResourceType
            handleMode = a.getInt(R.styleable.CropImageView_crop_show_handles, HANDLE_MODE_CHANGING);
            //noinspection ResourceType
            shape = a.getInt(R.styleable.CropImageView_crop_shape, SHAPE_SQUARE);

            a.recycle();
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (bitmapDisplayed.getBitmap() != null) {
            for (HighlightView hv : highlightViews) {

                hv.matrix.set(getUnrotatedMatrix());
                hv.invalidate();
                if (hv.hasFocus()) {
                    centerBasedOnHighlightView(hv);
                }
            }
        }
    }

    @Override
    protected void zoomTo(float scale, float centerX, float centerY) {
        super.zoomTo(scale, centerX, centerY);
        for (HighlightView hv : highlightViews) {
            hv.matrix.set(getUnrotatedMatrix());
            hv.invalidate();
        }
    }

    @Override
    protected void zoomIn() {
        super.zoomIn();
        for (HighlightView hv : highlightViews) {
            hv.matrix.set(getUnrotatedMatrix());
            hv.invalidate();
        }
    }

    @Override
    protected void zoomOut() {
        super.zoomOut();
        for (HighlightView hv : highlightViews) {
            hv.matrix.set(getUnrotatedMatrix());
            hv.invalidate();
        }
    }

    @Override
    protected void postTranslate(float deltaX, float deltaY) {
        super.postTranslate(deltaX, deltaY);
        for (HighlightView hv : highlightViews) {
            hv.matrix.postTranslate(deltaX, deltaY);
            hv.invalidate();
        }
    }

    boolean shouldShowThirds() {
        return showThirds;
    }

    public void setShowThirds(boolean showThirds) {
        this.showThirds = showThirds;
        for(HighlightView hv : highlightViews) {
            hv.invalidate();
        }
    }

    int getHighlightColor() {
        return highlightColor;
    }

    public void setHighlightColor(int highlightColor) {
        this.highlightColor = highlightColor;
        for(HighlightView hv : highlightViews) {
          hv.invalidate();
        }
    }

    int getOutsideColor() {
        return outsideColor;
    }

    public void setOutsideColor(int outsideColor) {
        this.outsideColor = outsideColor;
        for(HighlightView hv : highlightViews) {
          hv.invalidate();
        }
    }

    @HandleMode int getHandleMode() {
        return handleMode;
    }

    public void setHandleMode(@HandleMode int handleMode) {
        this.handleMode = handleMode;
        for(HighlightView hv : highlightViews) {
          hv.invalidate();
        }
    }

    @Shape int getShape() {
        return shape;
    }

    public void setShape(@Shape int shape) {
        this.shape = shape;
        for(HighlightView hv : highlightViews) {
          hv.invalidate();
        }
    }

    void setSaving(boolean saving) {
        this.saving = saving;
    }

    List<HighlightView> getHighlightViews() {
        return highlightViews;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (saving) {
            return false;
        }

        switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
            for (HighlightView hv : highlightViews) {
                int edge = hv.getHit(event.getX(), event.getY());
                if (edge != HighlightView.GROW_NONE) {
                    motionEdge = edge;
                    motionHighlightView = hv;
                    lastX = event.getX();
                    lastY = event.getY();
                    motionHighlightView.setMode((edge == HighlightView.MOVE)
                            ? HighlightView.MODIFY_MODE_MOVE
                            : HighlightView.MODIFY_MODE_GROW);
                    break;
                }
            }
            break;
        case MotionEvent.ACTION_UP:
            if (motionHighlightView != null) {
                centerBasedOnHighlightView(motionHighlightView);
                motionHighlightView.setMode(HighlightView.MODIFY_MODE_NONE);
            }
            motionHighlightView = null;
            break;
        case MotionEvent.ACTION_MOVE:
            if (motionHighlightView != null) {
                motionHighlightView.handleMotion(motionEdge, event.getX()
                        - lastX, event.getY() - lastY);
                lastX = event.getX();
                lastY = event.getY();
                ensureVisible(motionHighlightView);
            }
            break;
        }

        switch (event.getAction()) {
        case MotionEvent.ACTION_UP:
            center(true, true);
            break;
        case MotionEvent.ACTION_MOVE:
            // if we're not zoomed then there's no point in even allowing
            // the user to move the image around. This call to center puts
            // it back to the normalized location (with false meaning don't
            // animate).
            if (getScale() == 1F) {
                center(true, true);
            }
            break;
        }

        return true;
    }

    // Pan the displayed image to make sure the cropping rectangle is visible.
    private void ensureVisible(HighlightView hv) {
        Rect r = hv.drawRect;

        int panDeltaX1 = Math.max(0, getLeft() - r.left);
        int panDeltaX2 = Math.min(0, getRight() - r.right);

        int panDeltaY1 = Math.max(0, getTop() - r.top);
        int panDeltaY2 = Math.min(0, getBottom() - r.bottom);

        int panDeltaX = panDeltaX1 != 0 ? panDeltaX1 : panDeltaX2;
        int panDeltaY = panDeltaY1 != 0 ? panDeltaY1 : panDeltaY2;

        if (panDeltaX != 0 || panDeltaY != 0) {
            panBy(panDeltaX, panDeltaY);
        }
    }

    // If the cropping rectangle's size changed significantly, change the
    // view's center and scale according to the cropping rectangle.
    private void centerBasedOnHighlightView(HighlightView hv) {
        Rect drawRect = hv.drawRect;

        float width = drawRect.width();
        float height = drawRect.height();

        float thisWidth = getWidth();
        float thisHeight = getHeight();

        float z1 = thisWidth / width * .6F;
        float z2 = thisHeight / height * .6F;

        float zoom = Math.min(z1, z2);
        zoom = zoom * this.getScale();
        zoom = Math.max(1F, zoom);

        if ((Math.abs(zoom - getScale()) / zoom) > .1) {
            float[] coordinates = new float[] { hv.cropRect.centerX(), hv.cropRect.centerY() };
            getUnrotatedMatrix().mapPoints(coordinates);
            zoomTo(zoom, coordinates[0], coordinates[1], 300F);
        }

        ensureVisible(hv);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (HighlightView mHighlightView : highlightViews) {
            mHighlightView.draw(canvas);
        }
    }

    void add(HighlightView hv) {
        highlightViews.add(hv);
        invalidate();
    }
}
