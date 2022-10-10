/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package za.dams.camera2video;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceView;
import android.view.TextureView;

/**
 * A {@link TextureView} that can be adjusted to a specified aspect ratio.
 */
public class AutoFitSurfaceView extends SurfaceView {

    private float aspectRatio = 0;

    public AutoFitSurfaceView(Context context) {
        super(context);
    }
    public AutoFitSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public AutoFitSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public AutoFitSurfaceView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /**
     * Sets the aspect ratio for this view. The size of the view will be measured based on the ratio
     * calculated from the parameters. Note that the actual sizes of parameters don't matter, that
     * is, calling setAspectRatio(2, 3) and setAspectRatio(4, 6) make the same result.
     *
     * @param width  Relative horizontal size
     * @param height Relative vertical size
     */
    public void setAspectRatio(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Size cannot be negative.");
        }
        Log.d("damsSurfaceView", "AutoFitSurfaceView ratio : "+width+" x "+height) ;
        aspectRatio = (float)width / (float)height ;
        getHolder().setFixedSize(width, height);
        requestLayout() ;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        Log.d("damsSurfaceView", "Measured spec set: "+width+" x "+height) ;
        if (aspectRatio == 0f) {
            setMeasuredDimension(width, height) ;
        } else {

            // Performs center-crop transformation of the camera frames
            int newWidth ;
            int newHeight ;
            //float actualRatio = (width > height) ? aspectRatio : 1f / aspectRatio ;
            if (width < height * aspectRatio) {
                newWidth = width ;
                newHeight = (int)(width / aspectRatio) ;
            } else {
                newHeight = height ;
                newWidth = (int)(height * aspectRatio) ;
            }

            Log.d("damsSurfaceView", "Measured dimensions set: "+newWidth+" x "+newHeight) ;
            setMeasuredDimension(newWidth, newHeight);
        }
    }

}
