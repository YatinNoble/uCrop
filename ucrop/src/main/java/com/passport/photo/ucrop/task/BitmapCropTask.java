package com.passport.photo.ucrop.task;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.AsyncTask;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicConvolve3x3;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.passport.photo.ucrop.callback.BitmapCropCallback;
import com.passport.photo.ucrop.model.CropParameters;
import com.passport.photo.ucrop.model.ExifInfo;
import com.passport.photo.ucrop.model.ImageState;
import com.passport.photo.ucrop.util.ColorFilterGenerator;
import com.passport.photo.ucrop.util.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class BitmapCropTask extends AsyncTask<Void, Void, Throwable> {

    private static final String TAG = "BitmapCropTask";

    static {
        System.loadLibrary("ucrop");
    }

    private Context mContext;
    private Bitmap mViewBitmap;

    private final RectF mCropRect;
    private final RectF mCurrentImageRect;

    private float mCurrentScale, mCurrentAngle;
    private final int mMaxResultImageSizeX, mMaxResultImageSizeY;

    private final Bitmap.CompressFormat mCompressFormat;
    private final int mCompressQuality;
    private final String mImageInputPath, mImageOutputPath;
    private final ExifInfo mExifInfo;
    private final BitmapCropCallback mCropCallback;

    private float mBrightness;
    private float mContrast;
    private float mSaturation;

    private float mSharpness;

    private int mCroppedImageWidth, mCroppedImageHeight;
    private int cropOffsetX, cropOffsetY;

    public BitmapCropTask(@NonNull Context context, @Nullable Bitmap viewBitmap, @NonNull ImageState imageState, @NonNull CropParameters cropParameters,
                          @Nullable BitmapCropCallback cropCallback) {
        mContext = context;
        mViewBitmap = viewBitmap;
        mCropRect = imageState.getCropRect();
        mCurrentImageRect = imageState.getCurrentImageRect();

        mCurrentScale = imageState.getCurrentScale();
        mCurrentAngle = imageState.getCurrentAngle();
        mMaxResultImageSizeX = cropParameters.getMaxResultImageSizeX();
        mMaxResultImageSizeY = cropParameters.getMaxResultImageSizeY();

        mCompressFormat = cropParameters.getCompressFormat();
        mCompressQuality = cropParameters.getCompressQuality();

        mImageInputPath = cropParameters.getImageInputPath();
        mImageOutputPath = cropParameters.getImageOutputPath();
        mExifInfo = cropParameters.getExifInfo();

        mBrightness = cropParameters.getBrightness();
        mContrast = cropParameters.getContrast();
        mSaturation = cropParameters.getSaturation();

        mSharpness = cropParameters.getSharpness();

        mCropCallback = cropCallback;
    }

    @Override
    @Nullable
    protected Throwable doInBackground(Void... params) {
        if (mViewBitmap == null) {
            return new NullPointerException("ViewBitmap is null");
        } else if (mViewBitmap.isRecycled()) {
            return new NullPointerException("ViewBitmap is recycled");
        } else if (mCurrentImageRect.isEmpty()) {
            return new NullPointerException("CurrentImageRect is empty");
        }

        float resizeScale = resize();

        try {
            crop(resizeScale);

            if (mBrightness != 0.0f || mContrast != 0.0f || mSaturation != 0.0f || mSharpness != 0.0f) {
                Bitmap sourceBitmap = BitmapFactory.decodeFile(mImageOutputPath);
                Bitmap alteredBitmap = Bitmap.createBitmap(sourceBitmap.getWidth(), sourceBitmap.getHeight(), sourceBitmap.getConfig());

                ColorMatrix cm = new ColorMatrix();
                ColorFilterGenerator.adjustBrightness(cm, mBrightness);
                ColorFilterGenerator.adjustContrast(cm, mContrast);
                ColorFilterGenerator.adjustSaturation(cm, mSaturation);

                ColorMatrixColorFilter colorFilter = new ColorMatrixColorFilter(cm);

                Canvas canvas = new Canvas(alteredBitmap);
                Paint paint = new Paint();
                paint.setColorFilter(colorFilter);
                Matrix matrix = new Matrix();
                canvas.drawBitmap(sourceBitmap, matrix, paint);

                if (mSharpness != 0.0f) {
                    RenderScript rs = RenderScript.create(mContext);

                    // Allocate buffers
                    Allocation inAllocation = Allocation.createFromBitmap(rs, sourceBitmap);
                    Allocation outAllocation = Allocation.createFromBitmap(rs, alteredBitmap);

                    // Load script
                    ScriptIntrinsicConvolve3x3 sharpnessScript = ScriptIntrinsicConvolve3x3.create(rs, Element.U8_4(rs));
                    sharpnessScript.setInput(inAllocation);
                    float[] coefficients = {
                            0, -mSharpness, 0,
                            -mSharpness, 1 + (4 * mSharpness), -mSharpness,
                            0, -mSharpness, 0};
                    sharpnessScript.setCoefficients(coefficients);
                    sharpnessScript.forEach(outAllocation);
                    outAllocation.copyTo(alteredBitmap);

                    inAllocation.destroy();
                    outAllocation.destroy();
                    sharpnessScript.destroy();
                    rs.destroy();
                }

                File file = new File(mImageOutputPath);
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                alteredBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
                fileOutputStream.close();
            }

            mViewBitmap = null;
        } catch (Throwable throwable) {
            return throwable;
        }

        return null;
    }

    private float resize() {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(mImageInputPath, options);

        boolean swapSides = mExifInfo.getExifDegrees() == 90 || mExifInfo.getExifDegrees() == 270;
        float scaleX = (swapSides ? options.outHeight : options.outWidth) / (float) mViewBitmap.getWidth();
        float scaleY = (swapSides ? options.outWidth : options.outHeight) / (float) mViewBitmap.getHeight();

        float resizeScale = Math.min(scaleX, scaleY);

        mCurrentScale /= resizeScale;

        resizeScale = 1;
        if (mMaxResultImageSizeX > 0 && mMaxResultImageSizeY > 0) {
            float cropWidth = mCropRect.width() / mCurrentScale;
            float cropHeight = mCropRect.height() / mCurrentScale;

            if (cropWidth > mMaxResultImageSizeX || cropHeight > mMaxResultImageSizeY) {

                scaleX = mMaxResultImageSizeX / cropWidth;
                scaleY = mMaxResultImageSizeY / cropHeight;
                resizeScale = Math.min(scaleX, scaleY);

                mCurrentScale /= resizeScale;
            }
        }
        return resizeScale;
    }

    private boolean crop(float resizeScale) throws IOException {
        ExifInterface originalExif = new ExifInterface(mImageInputPath);

        cropOffsetX = Math.round((mCropRect.left - mCurrentImageRect.left) / mCurrentScale);
        cropOffsetY = Math.round((mCropRect.top - mCurrentImageRect.top) / mCurrentScale);
        mCroppedImageWidth = Math.round(mCropRect.width() / mCurrentScale);
        mCroppedImageHeight = Math.round(mCropRect.height() / mCurrentScale);

        boolean shouldCrop = shouldCrop(mCroppedImageWidth, mCroppedImageHeight);
        Log.i(TAG, "Should crop: " + shouldCrop);

        if (shouldCrop) {
            Bitmap originalBitmap = BitmapFactory.decodeFile(mImageInputPath);
            if (originalBitmap != null) {
                // Crop the bitmap using the specified coordinates and dimensions
                Bitmap updatedBitmap = Bitmap.createBitmap(originalBitmap, cropOffsetX, cropOffsetY, mCroppedImageWidth, mCroppedImageHeight);
                File file = new File(mImageOutputPath);
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                updatedBitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream);
                fileOutputStream.close();
            }
            return true;
        } else {
            FileUtils.copyFile(mImageInputPath, mImageOutputPath);
            return false;
        }
    }


    private boolean shouldCrop(int width, int height) {
        int pixelError = 1;
        pixelError += Math.round(Math.max(width, height) / 1000f);
        return (mMaxResultImageSizeX > 0 && mMaxResultImageSizeY > 0)
                || Math.abs(mCropRect.left - mCurrentImageRect.left) > pixelError
                || Math.abs(mCropRect.top - mCurrentImageRect.top) > pixelError
                || Math.abs(mCropRect.bottom - mCurrentImageRect.bottom) > pixelError
                || Math.abs(mCropRect.right - mCurrentImageRect.right) > pixelError
                || mCurrentAngle != 0;
    }

    @SuppressWarnings("JniMissingFunction")
    native public static boolean
    cropCImg(String inputPath, String outputPath,
             int left, int top, int width, int height,
             float angle, float resizeScale,
             int format, int quality,
             int exifDegrees, int exifTranslation) throws IOException, OutOfMemoryError;

    @Override
    protected void onPostExecute(@Nullable Throwable t) {
        if (mCropCallback != null) {
            if (t == null) {
                Uri uri = Uri.fromFile(new File(mImageOutputPath));
                mCropCallback.onBitmapCropped(uri, cropOffsetX, cropOffsetY, mCroppedImageWidth, mCroppedImageHeight);
            } else {
                mCropCallback.onCropFailure(t);
            }
        }
    }

}
