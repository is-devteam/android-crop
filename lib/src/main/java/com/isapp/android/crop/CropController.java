package com.isapp.android.crop;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.opengl.GLES10;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.SoftReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A utility class that helps crop images. Make sure to call {@link CropController#release} when you are done.
 */
public class CropController {
    private static final int FULL_QUALITY = 100;

    private static final int SIZE_DEFAULT = 2048;
    private static final int SIZE_LIMIT = 4096;

    private Builder builder;

    private int exifRotation;

    private int sampleSize;
    private RotateBitmap rotateBitmap;
    private HighlightView cropView;

    private AtomicBoolean saving = new AtomicBoolean(false);

    private boolean error = false;

    private CropController() {}

    private CropController(Builder builder) {
        this.builder = builder;

        setup();
    }

    private void setup() {
        CropImageView imageView = builder.imageView.get();
        if(imageView == null) {
            if(builder.errorListener != null) {
                builder.errorListener.onFatalError(new IllegalStateException("The CropImageView is null or not attached to a Context"));
            }
            error = true;
            return;
        }

        Context context = imageView.getContext();
        if(context == null) {
            if(builder.errorListener != null) {
                builder.errorListener.onFatalError(new IllegalStateException("The CropImageView is null or not attached to a Context"));
            }
            error = true;
            return;
        }

        exifRotation = CropUtil.getExifRotation(CropUtil.getFromMediaUri(context, builder.input));

        InputStream is = null;
        try {
            sampleSize = calculateBitmapSampleSize(context, builder.input);
            is = context.getContentResolver().openInputStream(builder.input);
            BitmapFactory.Options option = new BitmapFactory.Options();
            option.inSampleSize = sampleSize;
            rotateBitmap = new RotateBitmap(BitmapFactory.decodeStream(is, null, option), exifRotation);
        } catch (Throwable e) {
            if(builder.errorListener != null) {
                builder.errorListener.onFatalError(e);
            }
            error = true;
        } finally {
            CropUtil.closeSilently(is);
        }
    }

    /**
     * This should be called immediately after this {@link CropController} is instantiated.
     *
     * @return {@code false} if there was some error (do not use this object if that is the case)
     */
    public boolean start() {
        final CropImageView imageView = builder.imageView.get();
        if(error || imageView == null) {
            return false;
        }

        imageView.setImageRotateBitmapResetBase(rotateBitmap, true);

        imageView.post(new Runnable() {
            @Override
            public void run() {
                final CropImageView imageView = builder.imageView.get();
                if(imageView == null) {
                    return;
                }

                if (imageView.getScale() == 1F) {
                    imageView.center(true, true);
                }

                crop(imageView);
            }
        });

        return true;
    }

    /*
     * TODO
     * This should use the decode/crop/encode single step API so that the whole
     * (possibly large) Bitmap doesn't need to be read into memory
     */
    /**
     * Save the result of the crop. This method must be called in a background thread.
     *
     * @throws java.lang.IllegalStateException if it is called on the main thread
     *
     * @return {@code false} if there was some error (do not use this object if that is the case)
     */
    public boolean save() {
        if(Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("You can't call CropController.save() on the main thread");
        }

        if (error || cropView == null || saving.getAndSet(true)) {
            return false;
        }

        CropImageView imageView = builder.imageView.get();
        if(imageView == null) {
            error = true;
            return false;
        }
        imageView.setSaving(true);

        Context context = imageView.getContext();
        if(context == null) {
            error = true;
            return false;
        }

        final Bitmap croppedImage;
        Rect r = cropView.getScaledCropRect(sampleSize);
        int width = r.width();
        int height = r.height();

        int outWidth = width;
        int outHeight = height;
        if (builder.maxSizeWidth > 0 && builder.maxSizeHeight > 0 && (width > builder.maxSizeWidth || height > builder.maxSizeHeight)) {
            float ratio = (float) width / (float) height;
            if ((float) builder.maxSizeWidth / (float) builder.maxSizeHeight > ratio) {
                outHeight = builder.maxSizeHeight;
                outWidth = (int) ((float) builder.maxSizeHeight * ratio + .5f);
            } else {
                outWidth = builder.maxSizeWidth;
                outHeight = (int) ((float) builder.maxSizeWidth / ratio + .5f);
            }
        }

        try {
            croppedImage = decodeRegionCrop(imageView, context, r, outWidth, outHeight);
        } catch (final IllegalArgumentException e) {
            if(builder.errorListener != null) {
                imageView.post(new Runnable() {
                    @Override
                    public void run() {
                        builder.errorListener.onFatalError(e);
                    }
                });
            }
            error = true;
            return false;
        }

        if (croppedImage != null) {
            final CountDownLatch croppedImageLatch = new CountDownLatch(1);
            imageView.post(new Runnable() {
                @Override
                public void run() {
                    CropImageView imageView = builder.imageView.get();
                    if(imageView == null) {
                        croppedImageLatch.countDown();
                        error = true;
                        return;
                    }

                    imageView.setImageRotateBitmapResetBase(new RotateBitmap(croppedImage, exifRotation), true);
                    imageView.center(true, true);
                    imageView.getHighlightViews().clear();
                    croppedImageLatch.countDown();
                }
            });

            try {
                croppedImageLatch.await();
            } catch (final InterruptedException e) {
                if(builder.errorListener != null) {
                    imageView.post(new Runnable() {
                        @Override
                        public void run() {
                            builder.errorListener.onError(e);
                        }
                    });
                }
            }
        }

        saveImage(croppedImage);
        saving.set(false);
        imageView.setSaving(false);

        return true;
    }

    public boolean hasError() {
        return error;
    }

    public boolean isSaving() {
        return saving.get();
    }

    /**
     * Releases expensive resources. Do not use the object after calling this.
     */
    public void release() {
        CropImageView imageView = builder.imageView.get();
        if(imageView != null) {
            clearImageView(imageView);
            imageView.getHighlightViews().clear();
        }

        if(builder != null) {
            builder.release();
        }
    }

    private void crop(CropImageView imageView) {
        imageView.post(new Runnable() {
            public void run() {
                CropImageView imageView = builder.imageView.get();
                if (imageView == null || rotateBitmap == null) {
                    error = true;
                    return;
                }

                HighlightView hv = new HighlightView(imageView);
                final int width = rotateBitmap.getWidth();
                final int height = rotateBitmap.getHeight();

                Rect imageRect = new Rect(0, 0, width, height);

                // Make the default size about 4/5 of the width or height
                int cropWidth = Math.min(width, height) * 4 / 5;
                @SuppressWarnings("SuspiciousNameCombination")
                int cropHeight = cropWidth;

                if (builder.aspectX != 0 && builder.aspectY != 0) {
                    if (builder.aspectX > builder.aspectY) {
                        cropHeight = cropWidth * builder.aspectY / builder.aspectX;
                    } else {
                        cropWidth = cropHeight * builder.aspectX / builder.aspectY;
                    }
                }

                int x = (width - cropWidth) / 2;
                int y = (height - cropHeight) / 2;

                RectF cropRect = new RectF(x, y, x + cropWidth, y + cropHeight);
                hv.setup(imageView.getUnrotatedMatrix(), imageRect, cropRect, builder.aspectX != 0 && builder.aspectY != 0);
                imageView.add(hv);

                imageView.invalidate();
                if (imageView.getHighlightViews().size() == 1) {
                    cropView = imageView.getHighlightViews().get(0);
                    cropView.setFocus(true);
                }
            }
        });
    }

    private Bitmap decodeRegionCrop(CropImageView imageView, Context context, Rect rect, int outWidth, int outHeight) {
        // Release memory now
        final CountDownLatch clearImageViewLatch = new CountDownLatch(1);
        imageView.post(new Runnable() {
            @Override
            public void run() {
                CropImageView imageView = builder.imageView.get();
                if(imageView != null) {
                    clearImageView(imageView);
                }
                clearImageViewLatch.countDown();
            }
        });

        try {
            clearImageViewLatch.await();
        } catch (final InterruptedException e) {
            if(builder.errorListener != null) {
                imageView.post(new Runnable() {
                    @Override
                    public void run() {
                        builder.errorListener.onError(e);
                    }
                });
            }
        }

        InputStream is = null;
        Bitmap croppedImage = null;
        try {
            is = context.getContentResolver().openInputStream(builder.input);
            BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(is, false);
            final int width = decoder.getWidth();
            final int height = decoder.getHeight();

            if (exifRotation != 0) {
                // Adjust crop area to account for image rotation
                Matrix matrix = new Matrix();
                matrix.setRotate(-exifRotation);

                RectF adjusted = new RectF();
                matrix.mapRect(adjusted, new RectF(rect));

                // Adjust to account for origin at 0,0
                adjusted.offset(adjusted.left < 0 ? width : 0, adjusted.top < 0 ? height : 0);
                rect = new Rect((int) adjusted.left, (int) adjusted.top, (int) adjusted.right, (int) adjusted.bottom);
            }

            try {
                croppedImage = decoder.decodeRegion(rect, new BitmapFactory.Options());
                if (rect.width() > outWidth || rect.height() > outHeight) {
                    Matrix matrix = new Matrix();
                    matrix.postScale((float) outWidth / rect.width(), (float) outHeight / rect.height());
                    croppedImage = Bitmap.createBitmap(croppedImage, 0, 0, croppedImage.getWidth(), croppedImage.getHeight(), matrix, true);
                }
            } catch (IllegalArgumentException e) {
                // Rethrow with some extra information
                throw new IllegalArgumentException("Rectangle " + rect + " is outside of the image ("
                    + width + "," + height + "," + exifRotation + ")", e);
            }

        } catch (final Throwable e) {
            if(builder.errorListener != null) {
                imageView.post(new Runnable() {
                    @Override
                    public void run() {
                        builder.errorListener.onError(e);
                    }
                });
            }
            error = true;
        } finally {
            CropUtil.closeSilently(is);
        }
        return croppedImage;
    }

    private void clearImageView(CropImageView imageView) {
        imageView.clear();
        if (rotateBitmap != null) {
            rotateBitmap.recycle();
        }
        System.gc();
    }

    private void saveImage(Bitmap croppedImage) {
        CropImageView imageView = builder.imageView.get();
        if(imageView == null) {
            error = true;
            return;
        }

        if (croppedImage != null) {
            final boolean success = saveOutput(croppedImage);
            if(builder.finishedListener != null) {
                imageView.post(new Runnable() {
                    @Override
                    public void run() {
                        if(success) {
                            builder.finishedListener.onCropFinished(builder.output);
                        }
                        else {
                            builder.finishedListener.onCropFailed();
                        }
                    }
                });
            }
        } else {
            if(builder.finishedListener != null) {
                imageView.post(new Runnable() {
                    @Override
                    public void run() {
                        builder.finishedListener.onCropFailed();
                    }
                });
            }
        }
    }

    private boolean saveOutput(Bitmap croppedImage) {
        CropImageView imageView = builder.imageView.get();
        if(imageView == null) {
            error = true;
            return false;
        }
        Context context = imageView.getContext();
        if(context == null) {
            error = true;
            return false;
        }

        OutputStream outputStream = null;
        try {
            if (builder.output != null) {
                outputStream = context.getContentResolver().openOutputStream(builder.output);
                if (outputStream != null) {
                    croppedImage.compress(builder.compressFormat, builder.compressionQuality, outputStream);
                }

                return true;
            }
            else {
                error = true;
                return false;
            }
        }
        catch(final Throwable e) {
            if(builder.errorListener != null) {
                imageView.post(new Runnable() {
                    @Override
                    public void run() {
                        builder.errorListener.onFatalError(e);
                    }
                });
                error = true;
            }
            return false;
        } finally {
            CropUtil.closeSilently(outputStream);

            final CountDownLatch imageViewClearLatch = new CountDownLatch(1);
            final Bitmap b = croppedImage;
            imageView.post(new Runnable() {
                public void run() {
                    CropImageView imageView = builder.imageView.get();
                    if(imageView != null) {
                        imageView.clear();
                        b.recycle();
                    }
                    imageViewClearLatch.countDown();
                }
            });

            try {
                imageViewClearLatch.await();
            } catch (final InterruptedException e) {
                if(builder.errorListener != null) {
                    imageView.post(new Runnable() {
                        @Override
                        public void run() {
                            builder.errorListener.onError(e);
                        }
                    });
                }
            }
        }
    }

    private int calculateBitmapSampleSize(Context context, Uri bitmapUri) throws IOException {
        InputStream is = null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try {
            is = context.getContentResolver().openInputStream(bitmapUri);
            BitmapFactory.decodeStream(is, null, options); // Just get image size
        } finally {
            CropUtil.closeSilently(is);
        }

        int maxSize = getMaxImageSize();
        int sampleSize = 1;
        while (options.outHeight / sampleSize > maxSize || options.outWidth / sampleSize > maxSize) {
            sampleSize = sampleSize << 1;
        }
        return sampleSize;
    }

    private int getMaxImageSize() {
        int textureLimit = getMaxTextureSize();
        if (textureLimit == 0) {
            return SIZE_DEFAULT;
        } else {
            return Math.min(textureLimit, SIZE_LIMIT);
        }
    }

    private int getMaxTextureSize() {
        // The OpenGL texture size is the maximum size that can be drawn in an ImageView
        int[] maxSize = new int[1];
        GLES10.glGetIntegerv(GLES10.GL_MAX_TEXTURE_SIZE, maxSize, 0);
        return maxSize[0];
    }

    /**
     * Provide {@link CropController.Builder} with an implementation of {@code OnCropFinishedListener} to get notified when
     * the crop is finished (successfully or not)
     */
    public interface OnCropFinishedListener {
        public void onCropFinished(Uri output);
        public void onCropFailed();
    }

    /**
     * Provide {@link CropController.Builder} with an implementation of {@code OnErrorListener} to listen for errors
     * during the lifetime of the {@link CropController}
     */
    public interface OnErrorListener {
        public void onError(Throwable e);
        public void onFatalError(Throwable e);
    }

    public static class Builder {
        private Uri input;
        private Uri output;
        private SoftReference<CropImageView> imageView;
        private Bitmap.CompressFormat compressFormat = Bitmap.CompressFormat.JPEG;
        private int compressionQuality = FULL_QUALITY;
        private int aspectX;
        private int aspectY;
        private int maxSizeWidth;
        private int maxSizeHeight;
        private OnCropFinishedListener finishedListener;
        private OnErrorListener errorListener;

        /**
         * Create a builder with input image
         *
         * @param imageView The Image View
         * @param input Input image URI
         * @param output Output image URI
         *
         * @throws java.lang.IllegalArgumentException if {@code imageView}, {@code input}, or {@code output} is {@code null}
         * @throws java.lang.IllegalStateException if {@code imageView.getContext()} returns {@code null}
         */
        public Builder(CropImageView imageView, Uri input, Uri output) {
            if(imageView == null) {
                throw new IllegalArgumentException("CropImageView cannot be null");
            }
            if(imageView.getContext() == null) {
                throw new IllegalStateException("Is CropImageView attached to a Context?");
            }
            imageView.setRecycler(new ImageViewTouchBase.Recycler() {
                @Override
                public void recycle(Bitmap b) {
                    b.recycle();
                    System.gc();
                }
            });
            this.imageView = new SoftReference<>(imageView);

            if(input == null) {
                throw new IllegalArgumentException("Input URI cannot be null");
            }
            this.input = input;

            if(output == null) {
                throw new IllegalArgumentException("Output URI cannot be null");
            }
            this.output = output;
        }

        /**
         * Sets the type of compression the output will be saved as.
         * The output will be saved at full quality
         *
         * @param compressFormat The compression format to use
         */
        public Builder compression(Bitmap.CompressFormat compressFormat) {
            return compression(compressFormat, FULL_QUALITY);
        }

        /**
         * Sets the type of compression the output will be saved as
         * and sets the compression quality that will be used
         *
         * @param compressFormat The compression format to use
         * @param compressionQuality The compression quality to use (must be 1-100)
         *
         * @throws java.lang.IllegalArgumentException if {@code compressionQuality <= 0 || compressionQuality > 100}
         */
        public Builder compression(Bitmap.CompressFormat compressFormat, int compressionQuality) {
            if(compressionQuality <=0 || compressionQuality > 100) {
                throw new IllegalArgumentException(String.format("Illegal value for compressionQuality - %d", compressionQuality));
            }
            this.compressFormat = compressFormat;
            this.compressionQuality = compressionQuality;
            return this;
        }

        /**
         * Set fixed aspect ratio for crop area
         *
         * @param x Aspect X
         * @param y Aspect Y
         */
        public Builder withAspectRatio(int x, int y) {
            aspectX = x;
            aspectY = y;
            return this;
        }

        /**
         * Crop area with fixed 1:1 aspect ratio
         */
        public Builder asSquare() {
            aspectX = 1;
            aspectY = 1;
            return this;
        }

        /**
         * Set maximum crop size
         *
         * @param width Max width
         * @param height Max height
         */
        public Builder withMaxSize(int width, int height) {
            maxSizeWidth = width;
            maxSizeHeight = height;
            return this;
        }

        /**
         * Set the {@link OnCropFinishedListener}
         *
         * @param finishedListener The OnCropFinishedListener
         */
        public Builder withCropFinishedListener(OnCropFinishedListener finishedListener) {
            this.finishedListener = finishedListener;
            return this;
        }

        /**
         * Set the {@link OnErrorListener}
         *
         * @param errorListener The OnErrorListener
         */
        public Builder withErrorListener(OnErrorListener errorListener) {
            this.errorListener = errorListener;
            return this;
        }

        /**
         * Build the {@link CropController}
         *
         * @return the {@code CropController}
         */
        public CropController build() {
            return new CropController(this);
        }

        private void release() {
            if(imageView != null) {
                imageView.clear();
                imageView = null;
            }
            finishedListener = null;
            errorListener = null;
        }
    }
}
