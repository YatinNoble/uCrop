package com.passport.photo.ucrop.callback;

import android.graphics.RectF;

public interface OverlayViewChangeListener {

    void onCropRectUpdated(RectF cropRect);

}