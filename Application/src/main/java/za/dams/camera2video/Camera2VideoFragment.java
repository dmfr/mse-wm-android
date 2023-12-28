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
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
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
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
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
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class Camera2VideoFragment extends Fragment
        implements View.OnClickListener {

    private final class VideoSocketListener extends WebSocketListener {
        private static final int NORMAL_CLOSURE_STATUS = 1000;
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Camera2VideoFragment.this.wsRunning = true ;
        }
        @Override
        public void onMessage(WebSocket webSocket, String text) {
            //output("Receiving : " + text);
        }
        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            //output("Receiving bytes : " + bytes.hex());
        }
        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(NORMAL_CLOSURE_STATUS, null);
            //output("Closing : " + code + " / " + reason);
        }
        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            Camera2VideoFragment.this.websocket = null ;
        }
        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.w("DAMSDEBUG","Error : " + t.getMessage());
            Camera2VideoFragment.this.wsError = true ;
        }
    }


    private static final String TAG = "Camera2VideoFragment";
    private static final String FRAGMENT_DIALOG = "dialog";


    private static int sFPS = 30 ;

    private UtilPreferences mPrefs ;

    private AutoFitSurfaceView mSurfaceView;
    private FloatingActionButton mButtonVideo;
    private FloatingActionButton mButtonPrefs;
    private FloatingActionButton mButtonPlay;

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;

    private Size mVideoSize;

    private MediaCodec mMediaCodec;
    private ImageReader mImgReader ;
    private AudioRecord mAudioRecord ;
    private long mImageFramePTS ;
    private int mImageYUVbytesize ;

    private OkHttpClient okHttpClient ;
    private WebSocket websocket ;
    private boolean wsRunning ;
    private boolean wsError ;


    /**
     * Whether the app is recording video now
     */
    private boolean mIsRecordingVideoPending ;
    private boolean mIsRecordingVideo;
    private int mNbInputImg;

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    private EncoderThread mEncoderThread;

    private HandlerThread mImageThread ;
    private Handler mImageHandler ;

    private static final int AUDIOCFG_RATE = 44100;
    private static final int AUDIOCFG_CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIOCFG_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int AUDIO_BUFFERSIZE = AudioRecord.getMinBufferSize(AUDIOCFG_RATE,
            AUDIOCFG_CHANNEL, AUDIOCFG_FORMAT) * 1;
    private AudioReaderThread mAudioThread ;
    private MediaCodec mMediaCodecAudio ;
    private AudioEncoderThread mAudioEncoderThread ;


    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    public static Camera2VideoFragment newInstance() {
        return new Camera2VideoFragment();
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        return inflater.inflate(R.layout.fragment_camera2_video, container, false);
    }
    @Override
    public void onDestroyView() {
        //getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        super.onDestroyView();
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

        mButtonVideo = (FloatingActionButton) view.findViewById(R.id.video);
        mButtonVideo.setOnClickListener(this);
        mButtonPrefs = (FloatingActionButton) view.findViewById(R.id.prefs);
        mButtonPrefs.setOnClickListener(this);
        mButtonPlay = (FloatingActionButton) view.findViewById(R.id.play);
        mButtonPlay.setOnClickListener(this);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        okHttpClient = new OkHttpClient();
        mPrefs = new UtilPreferences(getActivity()) ;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateUI();
    }

    @Override
    public void onPause() {
        if (mIsRecordingVideo || mIsRecordingVideoPending) {
            stopRecordingVideo(true);
        }
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
            case R.id.prefs: {
                Activity activity = getActivity();
                if ((null != activity) && (activity instanceof CameraActivity)) {
                    ((CameraActivity)activity).openPreferences();
                }
                break;
            }
            case R.id.play: {
                Activity activity = getActivity();
                if ((null != activity) && (activity instanceof CameraActivity)) {
                    ((CameraActivity)activity).openPlayer();
                }
                break;
            }
        }
    }


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
            mVideoSize = mPrefs.getVideoResolution();
            //Log.d("damsdebug", "VideoSize : "+mVideoSize.getWidth()+" x "+mVideoSize.getHeight());

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

            //HACK stay on template preview while recording (prevent force zoom ? stabilization issue?)
            CaptureRequest.Builder camRequestBuilder = mCameraDevice.createCaptureRequest(mIsRecordingVideoPending ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);

            List<Surface> surfaces = new ArrayList<>();

            // Set up Surface for the camera preview
            Surface previewSurface = mSurfaceView.getHolder().getSurface();
            surfaces.add(previewSurface);
            camRequestBuilder.addTarget(previewSurface);

            if( mIsRecordingVideoPending && (mMediaCodec != null) ) {
                mImageThread = new HandlerThread("imgThread") ;
                mImageThread.start();
                mImageHandler = new Handler(mImageThread.getLooper());

                mImageFramePTS = 1000000l / (long)sFPS ;
                mImageYUVbytesize = mVideoSize.getWidth() * mVideoSize.getHeight() * 12 / 8 ;
                //https://wiki.videolan.org/YUV

                mImgReader = ImageReader.newInstance(mVideoSize.getWidth(), mVideoSize.getHeight(), ImageFormat.YUV_420_888,5);
                Surface imgSurface = mImgReader.getSurface() ;
                surfaces.add(imgSurface);
                camRequestBuilder.addTarget(imgSurface);
                mImgReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader reader) {
                        Image img = reader.acquireLatestImage() ;
                        if( img==null ) {
                            return ;
                        }
                        int inputBufferId = mMediaCodec.dequeueInputBuffer(0);
                        if (inputBufferId >= 0) {
                            // int sizeReturn = mMediaCodec.getInputBuffer(inputBufferId).remaining() ;
                            Image imgwrite = mMediaCodec.getInputImage(inputBufferId) ;
                            for( int i=0 ; i<imgwrite.getPlanes().length ; i++ ) {
                                ByteBuffer buffer = img.getPlanes()[i].getBuffer();
                                byte[] bytes = new byte[buffer.remaining()];
                                buffer.get(bytes);

                                imgwrite.getPlanes()[i].getBuffer().put(bytes) ;
                            }
                            // imgwrite.close();
                            long PTS = mNbInputImg * mImageFramePTS ;
                            mMediaCodec.queueInputBuffer(inputBufferId, 0, mImageYUVbytesize, PTS, 0);
                            mNbInputImg++;
                            //Log.w("DAMS","Read image = "+mNbInputImg);
                        }
                        img.close();
                    }
                },mImageHandler);


                mNbInputImg=0;
                mMediaCodec.start();
                mEncoderThread = new EncoderThread(mMediaCodec,websocket);
                mEncoderThread.start();
            }
            if( mIsRecordingVideoPending && (mMediaCodecAudio != null) ) {
                mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, AUDIOCFG_RATE,
                        AUDIOCFG_CHANNEL, AUDIOCFG_FORMAT, AUDIO_BUFFERSIZE);
                //Log.w("DAMS","Buffer size is "+AUDIO_BUFFERSIZE);
                android.media.audiofx.NoiseSuppressor noiseSuppressor = android.media.audiofx.NoiseSuppressor
                        .create(mAudioRecord.getAudioSessionId());
                if( noiseSuppressor != null ) {
                    noiseSuppressor.setEnabled(true) ;
                }
                android.media.audiofx.AutomaticGainControl automaticGainControl = android.media.audiofx.AutomaticGainControl
                        .create(mAudioRecord.getAudioSessionId());
                if( automaticGainControl != null ) {
                    automaticGainControl.setEnabled(true) ;
                }
                mAudioThread = new AudioReaderThread(mAudioRecord,mMediaCodecAudio) ;

                mMediaCodecAudio.start();
                mAudioEncoderThread = new AudioEncoderThread(mMediaCodecAudio,websocket);
                mAudioEncoderThread.start();
            }

            mCameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            mCaptureSession = session;

                            camRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                            camRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range(sFPS, sFPS));
                            camRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                    mIsRecordingVideoPending ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF );

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

                                        mAudioRecord.startRecording();
                                        mAudioThread.start();

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

        String codecMimetype = mPrefs.getVideoCodec() ;
        int codecProfile = 0 ;
        if( codecMimetype.equals(MediaFormat.MIMETYPE_VIDEO_AVC) ) {
            codecProfile = mPrefs.getVideoProfile() ;
        }
        if( codecMimetype.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) ) {
            codecProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain ;
        }

        mMediaCodec = MediaCodec.createEncoderByType(codecMimetype);
        MediaFormat format = MediaFormat.createVideoFormat(codecMimetype,
                streamWidth, streamHeight);
        format.setInteger(MediaFormat.KEY_PROFILE, codecProfile);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mPrefs.getVideoBitrate());
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, sFPS);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_LATENCY, 0);
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
        // Set the encoder priority to realtime.
        format.setInteger(MediaFormat.KEY_PRIORITY, 0x00);
        mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
   }
    private void setUpMediaCodecAudio() throws IOException {
        String codecMimetype = MediaFormat.MIMETYPE_AUDIO_AAC ;
        mMediaCodecAudio = MediaCodec.createEncoderByType(codecMimetype);
        MediaFormat format = MediaFormat.createAudioFormat(codecMimetype,
                AUDIOCFG_RATE, AUDIOCFG_CHANNEL);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
        format.setInteger(MediaFormat.KEY_LATENCY, 0);
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_BUFFERSIZE);
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, AUDIOCFG_RATE);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);

        // Set the encoder priority to realtime.
        format.setInteger(MediaFormat.KEY_PRIORITY, 0x00);
        mMediaCodecAudio.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }
    private void destroyMediaCodec() {
        if( mAudioEncoderThread != null ) {
            mAudioEncoderThread.terminate();
            try {
                mAudioEncoderThread.join();
            } catch( Exception e ) {
                e.printStackTrace();
            }
            mAudioEncoderThread = null ;
        }
        if( mAudioRecord != null ) {
            mAudioThread.terminate() ;
            try {
                mAudioThread.join();
            } catch( Exception e ) {
                e.printStackTrace();
            }
            mAudioThread = null ;

            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null ;
        }
        if( mMediaCodecAudio != null ) {
            mMediaCodecAudio.stop() ;
            mMediaCodecAudio.release();
            mMediaCodecAudio = null;
        }

        if( mEncoderThread != null ) {
            mEncoderThread.terminate();
            try {
                mEncoderThread.join();
            } catch( Exception e ) {
                e.printStackTrace();
            }
            mEncoderThread = null ;
        }
        if( mImgReader != null ) {
            mImgReader.setOnImageAvailableListener(null,null);

            mImageThread.quitSafely() ;
            try {
                mImageThread.join();
            } catch( Exception e ) {
                e.printStackTrace();
            }
            mImageThread = null ;
            mImageHandler = null ;

            mImgReader.close();
            mImgReader = null ;
        }
        if( mMediaCodec != null ) {
            mMediaCodec.stop() ;
            mMediaCodec.release();
            mMediaCodec = null;
        }
    }

    private void setUpWebsocket() {
        closeWebsocket() ;

        String wsUrl = mPrefs.getPeerWebsocketRecordUrl();
        wsRunning = wsError = false ;
        Request request = new Request.Builder().url(wsUrl).build();
        websocket = okHttpClient.newWebSocket(request, new VideoSocketListener());
        //okHttpClient.dispatcher().executorService().shutdown();
    }
    private void closeWebsocket() {
        if( websocket != null ) {
            if( websocket.queueSize() > 0l ) {
                websocket.cancel();
                return;
            }
            websocket.close(1000, null);
        }
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
                        setUpMediaCodecAudio();

                        setUpWebsocket() ;
                        int wsTries=0 ;
                        while(wsTries<10) {
                            Thread.sleep(1000);
                            if( wsRunning || wsError ) {
                                break ;
                            }
                            wsTries++ ;
                        }
                        if( !wsRunning ) {
                            throw new Exception("Cannot open WS") ;
                        }

                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                mIsRecordingVideoPending = true ;
                                utilKeepScreenOn(true);
                                startCaptureSession();
                            }
                        });
                    } catch( Exception e ) {
                        e.printStackTrace();
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                stopRecordingVideo();
                            }
                        });
                    }
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopRecordingVideo(){
        stopRecordingVideo(false);
    }
    private void stopRecordingVideo( boolean isGoingAway ) {
        // UI
        utilKeepScreenOn(false);
        mIsRecordingVideoPending = false ;
        mIsRecordingVideo = false ;
        updateUI(true) ;

        // Stop recording
        closeCaptureSession();
        destroyMediaCodec();
        closeWebsocket();

        if( !isGoingAway ) {
            startCaptureSession();
        }
    }


    private void updateUI() {
        updateUI(false) ;
    }
    private void updateUI(boolean isRecordingStop) {
        if( Looper.getMainLooper() != Looper.myLooper() ) {
            Log.e(TAG,"Not UI Thread !") ;
            return ;
        }
        int colorGray = getResources().getColor(android.R.color.darker_gray) ;
        int colorOrange = getResources().getColor(android.R.color.holo_orange_light) ;

        View recordCaption = getView().findViewById(R.id.record_caption) ;
        ImageView recordCaptionIcon = (ImageView)getView().findViewById(R.id.record_caption_icon) ;
        TextView recordCaptionText = (TextView)getView().findViewById(R.id.record_caption_text) ;
        if( mIsRecordingVideoPending ) {
            mButtonPrefs.setVisibility(View.GONE);
            mButtonPlay.setVisibility(View.GONE);
            mButtonVideo.setVisibility(View.GONE);

            recordCaptionIcon.setVisibility(View.INVISIBLE);
            recordCaptionText.setText("Preparing...") ;
            recordCaption.setVisibility(View.VISIBLE);
        } else if( mIsRecordingVideo ) {
            mButtonPrefs.setVisibility(View.GONE);
            mButtonPlay.setVisibility(View.GONE);
            mButtonVideo.setVisibility(View.VISIBLE);
            ((FloatingActionButton)mButtonVideo).setBackgroundTintList(ColorStateList.valueOf(colorGray)) ;

            recordCaptionIcon.setVisibility(View.VISIBLE);
            recordCaptionText.setText("RECORD") ;
            recordCaption.setVisibility(View.VISIBLE);
        } else if( isRecordingStop ) {
            mButtonPrefs.setVisibility(View.GONE);
            mButtonPlay.setVisibility(View.GONE);
            mButtonVideo.setVisibility(View.GONE);
            recordCaption.setVisibility(View.GONE);

            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    updateUI();
                }
            },1500) ;
        } else {
            mButtonPrefs.setVisibility(View.VISIBLE);
            mButtonPlay.setVisibility(View.VISIBLE);
            mButtonVideo.setVisibility(View.VISIBLE);
            ((FloatingActionButton)mButtonVideo).setBackgroundTintList(ColorStateList.valueOf(colorOrange)) ;

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

    private static class AudioReaderThread extends Thread {
        private boolean isRunning = true ;
        private AudioRecord audioRecord ;
        private MediaCodec mediaCodec ;

        AudioReaderThread() {

        }
        AudioReaderThread( AudioRecord audioRecord, MediaCodec mediaCodec ) {
            this.audioRecord = audioRecord ;
            this.mediaCodec = mediaCodec ;
        }
        @Override
        public void run() {
            final ByteBuffer buffer = ByteBuffer.allocateDirect(AUDIO_BUFFERSIZE);
            while( isRunning ) {
                int length = audioRecord.read(buffer, AUDIO_BUFFERSIZE);
                //Log.w("DAMS","AUDIO bytes = "+length);

                int inputBufferId = mediaCodec.dequeueInputBuffer(0);
                if (inputBufferId >= 0) {
                    ByteBuffer codecBuffer = mediaCodec.getInputBuffer(inputBufferId);
                    codecBuffer.put(buffer);
                    mediaCodec.queueInputBuffer(inputBufferId, 0, length, 0, 0 );
                }
                buffer.clear();
            }
        }
        public void terminate() {
            isRunning=false ;
        }
    }
    private static class EncoderThread extends Thread {
        private boolean isRunning = true ;
        MediaCodec.BufferInfo mBufferInfo;
        final long mTimeoutUsec;

        private MediaCodec mediaCodec ;
        private WebSocket webSocket ;

        ByteString bs ;

        EncoderThread( MediaCodec mediaCodec, WebSocket webSocket ) {
            this.mediaCodec=mediaCodec;
            this.webSocket=webSocket;

            mBufferInfo = new MediaCodec.BufferInfo();
            mTimeoutUsec = 10000l;
        }

        @Override
        public void run() {
            super.run();
            while( isRunning ) {
                encode() ;
            }
        }


        private void encode() {
            for(;;) {
                if( !isRunning ) break;
                int status = mediaCodec.dequeueOutputBuffer(mBufferInfo, mTimeoutUsec);
                if (status >= 0) {
                    // encoded sample
                    ByteBuffer data = mediaCodec.getOutputBuffer(status);
                    if (data != null) {
                        bs = ByteString.of(data);
                        // releasing buffer is important
                        mediaCodec.releaseOutputBuffer(status, false);

                        //Log.e("damsdebug","Buffer is "+webSocket.queueSize());
                        webSocket.send(bs);
                        //Log.w("DAMS","Send websocket");
                    }
                }
            }
        }

        public void terminate() {
            isRunning=false ;
        }
    }
    private static class AudioEncoderThread extends Thread {
        private boolean isRunning = true ;
        MediaCodec.BufferInfo mBufferInfo;
        final long mTimeoutUsec;

        private MediaCodec mediaCodec ;
        private WebSocket webSocket ;

        ByteString bs ;

        AudioEncoderThread( MediaCodec mediaCodec, WebSocket webSocket ) {
            this.mediaCodec=mediaCodec;
            this.webSocket=webSocket;

            mBufferInfo = new MediaCodec.BufferInfo();
            mTimeoutUsec = 10000l;
        }

        @Override
        public void run() {
            super.run();
            while( isRunning ) {
                encode() ;
            }
        }


        private void encode() {
            for(;;) {
                if( !isRunning ) break;
                int status = mediaCodec.dequeueOutputBuffer(mBufferInfo, mTimeoutUsec);
                if (status >= 0) {
                    //Log.w("dams","Flag is "+mBufferInfo.flags) ;
                    //Log.w("dams","Offset is "+mBufferInfo.offset) ;
                    //Log.w("dams","Size is "+mBufferInfo.size) ;

                    // encoded sample
                    ByteBuffer data = mediaCodec.getOutputBuffer(status);
                    if (data != null) {
                        if( mBufferInfo.flags==0 ) {
                            // releasing buffer is important
                            int rawlength = mBufferInfo.size - mBufferInfo.offset ;
                            byte[] header = createAdtsHeader(rawlength);
                            byte[] rawdata = new byte[rawlength];
                            data.get(rawdata,mBufferInfo.offset,rawlength) ;
                            ByteBuffer outbb = ByteBuffer.allocate(rawlength+ header.length).put(header).put(rawdata);
                            outbb.rewind();
                            bs = ByteString.of(outbb);
                            webSocket.send(bs);

                        }
                        mediaCodec.releaseOutputBuffer(status, false);
                    }
                }
            }
        }

        public void terminate() {
            isRunning=false ;
        }

        private byte[] createAdtsHeader(int length) {
            final int SAMPLE_RATE_INDEX = 4 ; // 44100
            final int CHANNELS = 1 ; // MONO
            int frameLength = length + 7;
            byte[] adtsHeader = new byte[7];

            adtsHeader[0] = (byte) 0xFF; // Sync Word
            adtsHeader[1] = (byte) 0xF1; // MPEG-4, Layer (0), No CRC
            adtsHeader[2] = (byte) ((MediaCodecInfo.CodecProfileLevel.AACObjectLC - 1) << 6);
            adtsHeader[2] |= (((byte) SAMPLE_RATE_INDEX) << 2);
            adtsHeader[2] |= (((byte) CHANNELS) >> 2);
            adtsHeader[3] = (byte) (((CHANNELS & 3) << 6) | ((frameLength >> 11) & 0x03));
            adtsHeader[4] = (byte) ((frameLength >> 3) & 0xFF);
            adtsHeader[5] = (byte) (((frameLength & 0x07) << 5) | 0x1f);
            adtsHeader[6] = (byte) 0xFC;

            return adtsHeader;
        }
    }

    void utilKeepScreenOn( boolean torf ) {
        if( torf ) {
            getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }
}
