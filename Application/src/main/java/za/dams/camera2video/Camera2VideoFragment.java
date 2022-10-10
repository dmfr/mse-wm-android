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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Camera2VideoFragment extends Fragment
        implements View.OnClickListener {

    private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;
    private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

    private static final String TAG = "Camera2VideoFragment";
    private static final String FRAGMENT_DIALOG = "dialog";


    private static Size[] sSizes = new Size[]{
            new Size(1920,1080),
            new Size(1280,720)
    } ;

    private AutoFitSurfaceView mSurfaceView;
    private ImageButton mButtonVideo;

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;

    private Size mVideoSize;

    private MediaCodec mMediaCodec;

    /**
     * Whether the app is recording video now
     */
    private boolean mIsRecordingVideoPending ;
    private boolean mIsRecordingVideo;

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    private HandlerThread mEncoderThread;
    private Handler mEncoderHandler;

    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    public static Camera2VideoFragment newInstance() {
        return new Camera2VideoFragment();
    }

    private static Size chooseVideoSize(Size[] choices) {
        for( Size requestSize : sSizes ) {
            for (Size size : choices) {
                if( size.equals(requestSize) ) {
                    return size ;
                }
            }
        }
        return null ;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera2_video, container, false);
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        mSurfaceView = (AutoFitSurfaceView) view.findViewById(R.id.texture);
        mSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                openCamera();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {

            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {

            }
        });
        mButtonVideo = (ImageButton) view.findViewById(R.id.video);
        mButtonVideo.setOnClickListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        closeCamera();
        super.onPause();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.video: {
                if (mIsRecordingVideo) {
                    stopRecordingVideo();
                } else {
                    startRecordingVideo();
                }
                break;
            }
            case R.id.info: {
                Activity activity = getActivity();
                if (null != activity) {
                    new AlertDialog.Builder(activity)
                            .setMessage(R.string.intro_message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
                break;
            }
        }
    }

    /**
     * Gets whether you should show UI with rationale for requesting permissions.
     *
     * @param permissions The permissions your app wants to request.
     * @return Whether you can show permission rationale UI.
     */


    /**
     * Tries to open a {@link CameraDevice}. The result is listened by `mStateCallback`.
     */
    @SuppressWarnings("MissingPermission")
    private void openCamera() {
        int width = mSurfaceView.getWidth() ;
        int height = mSurfaceView.getHeight() ;

        final Activity activity = getActivity();
        if (null == activity || activity.isFinishing()) {
            return;
        }
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            Log.d(TAG, "tryAcquire");
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }

            String cameraId = null ;
            for (String currentCameraId : manager.getCameraIdList()) {
                CameraCharacteristics cameraCharacteristics = manager.getCameraCharacteristics(currentCameraId);
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK) {
                    cameraId = currentCameraId;
                    break;
                }
            }
            if (cameraId == null) {
                throw new RuntimeException("Cannot get backfacing camera");
            }

            // Choose the sizes for camera preview and video recording
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            int mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (map == null) {
                throw new RuntimeException("Cannot get available preview/video sizes");
            }
            mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
            Log.d("damsdebug", "VideoSize : "+mVideoSize.getWidth()+" x "+mVideoSize.getHeight());

            int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                mSurfaceView.setAspectRatio(mVideoSize.getWidth(), mVideoSize.getHeight());
            } else {
                mSurfaceView.setAspectRatio(mVideoSize.getHeight(), mVideoSize.getWidth());
            }

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {

                @Override
                public void onOpened(CameraDevice cameraDevice) {
                    mCameraDevice = cameraDevice;
                    startCaptureSession();
                    mCameraOpenCloseLock.release();
                }

                @Override
                public void onDisconnected(CameraDevice cameraDevice) {
                    mCameraOpenCloseLock.release();
                    cameraDevice.close();
                    mCameraDevice = null;
                }

                @Override
                public void onError(CameraDevice cameraDevice, int error) {
                    mCameraOpenCloseLock.release();
                    cameraDevice.close();
                    mCameraDevice = null;
                    Activity activity = getActivity();
                    if (null != activity) {
                        activity.finish();
                    }
                }

            }, null);

        } catch (CameraAccessException e) {
            Toast.makeText(activity, "Cannot access the camera.", Toast.LENGTH_SHORT).show();
            activity.finish();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.");
        }
    }

    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            closeCaptureSession();
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mMediaCodec) {
                destroyMediaCodec();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.");
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Start / stop the camera capture session
     */
    private void closeCaptureSession() {
        if (mCaptureSession != null) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if( mBackgroundThread != null ) {
            mBackgroundThread.quitSafely() ;
            mBackgroundThread = null ;
            mBackgroundHandler = null ;
        }
    }
    private void startCaptureSession() {
        if (null == mCameraDevice || !mSurfaceView.getHolder().getSurface().isValid() || null == mVideoSize) {
            return;
        }
        try {
            closeCaptureSession();

            CaptureRequest.Builder previewBuilder = mCameraDevice.createCaptureRequest(mIsRecordingVideoPending ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
            List<Surface> surfaces = new ArrayList<>();

            // Set up Surface for the camera preview
            Surface previewSurface = mSurfaceView.getHolder().getSurface();
            surfaces.add(previewSurface);
            previewBuilder.addTarget(previewSurface);

            if( mIsRecordingVideoPending && (mMediaCodec != null) ) {
                Surface encoderSurface = mMediaCodec.createInputSurface() ;
                surfaces.add(encoderSurface);
                previewBuilder.addTarget(encoderSurface);
            }

            mCameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            mCaptureSession = session;

                            previewBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                            previewBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);

                            previewBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range(30, 30)) ;

                            mBackgroundThread = new HandlerThread("CameraBackground");
                            mBackgroundThread.start();
                            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
                            try {
                                mCaptureSession.setRepeatingRequest(previewBuilder.build(), null, mBackgroundHandler);
                            } catch( Exception e ) {
                                e.printStackTrace();
                            }
                            if( mIsRecordingVideoPending ) {
                                getActivity().runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        mIsRecordingVideoPending = false ;
                                        // UI
                                        //mButtonVideo.setText(R.string.stop);
                                        mIsRecordingVideo = true;

                                        // Start codec ??
                                    }
                                });

                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Activity activity = getActivity();
                            if (null != activity) {
                                Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }



    private void setUpMediaCodec() throws IOException {
        /*
        final Activity activity = getActivity();
        if (null == activity) {
            return;
        }
        //mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (mNextVideoAbsolutePath == null || mNextVideoAbsolutePath.isEmpty()) {
            mNextVideoAbsolutePath = getVideoFilePath(getActivity());
        }
        mMediaRecorder.setOutputFile(mNextVideoAbsolutePath);
        mMediaRecorder.setVideoEncodingBitRate(10000000);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        //mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        switch (mSensorOrientation) {
            case SENSOR_ORIENTATION_DEFAULT_DEGREES:
                mMediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation));
                break;
            case SENSOR_ORIENTATION_INVERSE_DEGREES:
                mMediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
                break;
        }
        mMediaRecorder.prepare();
         */
    }
    private void destroyMediaCodec() {
        mMediaCodec.flush();
        mMediaCodec.release();
        mMediaCodec = null ;
        if( mEncoderThread != null ) {
            mEncoderThread.quitSafely();
            mEncoderThread = null ;
            mEncoderHandler = null ;
        }
    }

    private String getVideoFilePath(Context context) {
        final File dir = context.getExternalFilesDir(null);
        return (dir == null ? "" : (dir.getAbsolutePath() + "/"))
                + System.currentTimeMillis() + ".mp4";
    }

    private void startRecordingVideo() {
        if (null == mCameraDevice || !mSurfaceView.getHolder().getSurface().isValid() || null == mVideoSize) {
            return;
        }
        try {
            closeCaptureSession();

            mIsRecordingVideoPending = true ;
            setUpMediaCodec();

            startCaptureSession();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void stopRecordingVideo() {
        // UI
        mIsRecordingVideo = false ;
        //mButtonVideo.setText(R.string.record);

        // Stop recording
        closeCaptureSession();
        destroyMediaCodec();

        startCaptureSession();
    }


    public static class ErrorDialog extends DialogFragment {
        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }
    }


}
