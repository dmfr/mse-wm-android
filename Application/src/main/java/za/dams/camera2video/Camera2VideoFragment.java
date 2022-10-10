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
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
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
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
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
        updateUI();
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
                if (mIsRecordingVideo || mIsRecordingVideoPending) {
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

            CaptureRequest.Builder camRequestBuilder = mCameraDevice.createCaptureRequest(mIsRecordingVideoPending ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
            List<Surface> surfaces = new ArrayList<>();

            // Set up Surface for the camera preview
            Surface previewSurface = mSurfaceView.getHolder().getSurface();
            surfaces.add(previewSurface);
            camRequestBuilder.addTarget(previewSurface);

            if( mIsRecordingVideoPending && (mMediaCodec != null) ) {
                Surface encoderSurface = mMediaCodec.createInputSurface() ;
                surfaces.add(encoderSurface);
                camRequestBuilder.addTarget(encoderSurface);
            }

            mCameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            mCaptureSession = session;

                            camRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                            camRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range(30, 30));
                            camRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                    mIsRecordingVideoPending ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF );

                            if( mIsRecordingVideoPending ) {
                                mMediaCodec.start() ;
                            }

                            mBackgroundThread = new HandlerThread("CameraBackground");
                            mBackgroundThread.start();
                            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
                            try {
                                mCaptureSession.setRepeatingRequest(camRequestBuilder.build(), null, mBackgroundHandler);
                            } catch( Exception e ) {
                                e.printStackTrace();
                            }
                            if( mIsRecordingVideoPending ) {
                                new Handler(Looper.getMainLooper()).post(new Runnable() {
                                    @Override
                                    public void run() {
                                        mIsRecordingVideoPending = false ;
                                        mIsRecordingVideo = true;

                                        updateUI();
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
        int orientation = getResources().getConfiguration().orientation;
        int streamWidth,streamHeight ;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            streamWidth = mVideoSize.getWidth() ;
            streamHeight = mVideoSize.getHeight() ;
        } else {
            streamHeight = mVideoSize.getWidth() ;
            streamWidth = mVideoSize.getHeight() ;
        }

        mEncoderThread = new HandlerThread("EncoderBackground");
        mEncoderThread.start();
        mEncoderHandler = new Handler(mEncoderThread.getLooper());

        mMediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
//            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
//                    width, height);

        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                streamWidth, streamHeight);
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 8000000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
        format.setInteger(MediaFormat.KEY_LATENCY, 0);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
        // Set the encoder priority to realtime.
        format.setInteger(MediaFormat.KEY_PRIORITY, 0x00);
        mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        mMediaCodec.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {

            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                ByteBuffer outputBuffer = codec.getOutputBuffer(index);

                Log.i("has", String.valueOf(outputBuffer.hasRemaining()));
                Log.e("damsdebug","Buffer has "+outputBuffer.remaining());

               // int length = outputBuffer

                codec.releaseOutputBuffer(index, false);
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {

            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {

            }
        },mEncoderHandler);
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
        //mMediaCodec.start() ;
    }
    private void destroyMediaCodec() {
        if( mMediaCodec != null ) {
            mMediaCodec.stop() ;
            mMediaCodec.release();
            mMediaCodec = null;
        }
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
            mIsRecordingVideoPending = true ;
            updateUI() ;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        setUpMediaCodec();
                        Thread.sleep(1000);
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                mIsRecordingVideoPending = true ;
                                startCaptureSession();
                            }
                        });
                    } catch( Exception e ) {

                    }
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void stopRecordingVideo() {
        // UI
        mIsRecordingVideoPending = false ;
        mIsRecordingVideo = false ;
        updateUI() ;

        // Stop recording
        closeCaptureSession();
        destroyMediaCodec();

        startCaptureSession();
    }


    private void updateUI() {
        View recordCaption = getView().findViewById(R.id.record_caption) ;
        ImageView recordCaptionIcon = (ImageView)getView().findViewById(R.id.record_caption_icon) ;
        TextView recordCaptionText = (TextView)getView().findViewById(R.id.record_caption_text) ;
        if( mIsRecordingVideoPending ) {
            recordCaptionIcon.setVisibility(View.INVISIBLE);
            recordCaptionText.setText("Preparing...") ;
            recordCaption.setVisibility(View.VISIBLE);
        } else if( mIsRecordingVideo ) {
            recordCaptionIcon.setVisibility(View.VISIBLE);
            recordCaptionText.setText("RECORD") ;
            recordCaption.setVisibility(View.VISIBLE);
        } else {
            recordCaption.setVisibility(View.GONE);
        }
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
