package za.dams.camera2video;

import android.app.Fragment;
import android.content.pm.ActivityInfo;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class PlayVideoFragment extends Fragment {

    private AutoFitSurfaceView mSurfaceView;

    private MediaCodec mMediaCodec ;
    private boolean mMediaCodecStarted = false ;
    private boolean mMediaCodecConfigured = false ;
    private DecoderThread mDecoderThread ;

    private static Size sSize = new Size(1920,1080) ;
    private static int sFPS = 30 ;
    private long mImageFramePTS ;
    private int mNbInputImg ;

    public static PlayVideoFragment newInstance() {
        return new PlayVideoFragment();
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

        view.findViewById(R.id.record_caption).setVisibility(View.VISIBLE);
        view.findViewById(R.id.record_caption_icon).setVisibility(View.GONE);
        view.findViewById(R.id.record_caption_text).setVisibility(View.VISIBLE);
        ((TextView)getView().findViewById(R.id.record_caption_text)).setText("PLAYER");

        // hide buttons
        view.findViewById(R.id.video).setVisibility(View.GONE);
        view.findViewById(R.id.prefs).setVisibility(View.GONE);
        view.findViewById(R.id.play).setVisibility(View.GONE);

        view.findViewById(R.id.wait).setVisibility(View.VISIBLE);

        mSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                startPlaying();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {

            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {

            }
        });
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
        //startPlaying();
    }
    @Override
    public void onPause() {
        stopPlaying();
        super.onPause();
    }


    private UtilPreferences mPrefs ;

    private OkHttpClient okHttpClient ;
    private WebSocket websocket ;
    private boolean wsRunning ;
    private boolean wsError ;
    private final class VideoSocketListener extends WebSocketListener {
        private static final int NORMAL_CLOSURE_STATUS = 1000;
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            PlayVideoFragment.this.wsRunning = true ;
        }
        @Override
        public void onMessage(WebSocket webSocket, String text) {
            //output("Receiving : " + text);
        }
        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            //Log.w("DAMSDEBUG","Receiving bytes : " + bytes.size());
            PlayVideoFragment.this.onFrameReceived(bytes.toByteArray());
        }
        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(NORMAL_CLOSURE_STATUS, null);
            //output("Closing : " + code + " / " + reason);
        }
        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            PlayVideoFragment.this.websocket = null ;
        }
        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            //Log.w("DAMSDEBUG","onFailure : " + t.getMessage());
            Log.w("DAMSDEBUG","Error onFailure : " + t.getMessage());
            PlayVideoFragment.this.wsError = true ;
        }
    }

    private void startPlaying() {
        closeWebsocket() ;

        mMediaCodecConfigured = false ;
        mMediaCodecStarted = false ;

        mSurfaceView.setAspectRatio(sSize.getWidth(),sSize.getHeight());

        mImageFramePTS = 1000000l / (long)(sFPS) ;
        mNbInputImg = 0 ;

        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, sSize.getWidth(), sSize.getHeight());
        format.setInteger(MediaFormat.KEY_LOW_LATENCY,1);
        try {
            mMediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mMediaCodec.configure(format, mSurfaceView.getHolder().getSurface(), null, 0);
            mMediaCodec.start() ;

            mDecoderThread = new PlayVideoFragment.DecoderThread(mMediaCodec);
            mDecoderThread.start();

            mMediaCodecStarted = true ;
        } catch(Exception e) {
            e.printStackTrace();
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    setUpWebsocket();
                    // wait for websocket alive
                    int wsTries = 0;
                    while (wsTries < 10) {
                        Thread.sleep(1000);
                        if (wsRunning || wsError) {
                            break;
                        }
                        wsTries++;
                    }
                    if (!wsRunning) {
                        throw new Exception("Cannot open WS");
                    }
                } catch(Exception e) {
                    Log.e("damsdebug",e.getMessage()) ;
                    stopPlaying();
                }
            }
        }).start();
    }
    private void stopPlaying() {
        closeWebsocket() ;

        if( mDecoderThread != null ) {
            mDecoderThread.terminate();
            try {
                mDecoderThread.join();
            } catch(Exception e) {
                e.printStackTrace();
            }
            mDecoderThread = null ;
        }
        if( mMediaCodec != null ) {
            mMediaCodecStarted = false;
            mMediaCodec.stop();
            mMediaCodec = null;
        }
    }
    private void setUpWebsocket() {
        closeWebsocket() ;

        String wsUrl = mPrefs.getPeerWebsocketPlayUrl();
        wsUrl += "?id=CLIP1" ;

        wsRunning = wsError = false ;
        Request request = new Request.Builder().url(wsUrl).build();
        websocket = okHttpClient.newWebSocket(request, new PlayVideoFragment.VideoSocketListener());
        //okHttpClient.dispatcher().executorService().shutdown();
    }
    private void closeWebsocket() {
        if( websocket != null ) {
            websocket.close(1000, null);
        }
    }



    private void onFrameReceived( byte[] data ) {
        if( !mMediaCodecStarted ) {
            return ;
        }
        if( !mMediaCodecConfigured ) {
            int typesMask = H264helper.getNALtypes(data);
            int maskToCheck = 0;
            maskToCheck |= H264helper.TYPE_SPS;
            maskToCheck |= H264helper.TYPE_PPS;
            if ((typesMask & maskToCheck) == maskToCheck) {
                // ok to initialize
            } else {
                return ;
            }
        }

        int inputIndex = mMediaCodec.dequeueInputBuffer(-1);
        if (inputIndex >= 0) {
            ByteBuffer buffer = mMediaCodec.getInputBuffer(inputIndex);
            buffer.put(data);

            long PTS = mNbInputImg * mImageFramePTS ;
            //PTS = 0 ; // HACK to test ?
            mMediaCodec.queueInputBuffer(inputIndex, 0, data.length, PTS,
                    !mMediaCodecConfigured ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG & MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            mNbInputImg++ ;
        }
        if( !mMediaCodecConfigured ) {
            mMediaCodecConfigured = true ;
        }
    }




    private static class DecoderThread extends Thread {
        private boolean isRunning = true ;
        MediaCodec.BufferInfo mBufferInfo;
        final long mTimeoutUsec;

        private MediaCodec mediaCodec ;

        DecoderThread( MediaCodec mediaCodec ) {
            this.mediaCodec=mediaCodec;

            mBufferInfo = new MediaCodec.BufferInfo();
            mTimeoutUsec = 10000l;
        }

        @Override
        public void run() {
            super.run();
            while( isRunning ) {
                decode() ;
            }
        }


        private void decode() {
            for(;;) {
                if( !isRunning ) break;
                int status = mediaCodec.dequeueOutputBuffer(mBufferInfo, mTimeoutUsec);
                if (status >= 0) {
                    // encoded sample
                    ByteBuffer data = mediaCodec.getOutputBuffer(status);
                    if (data != null) {
                        mediaCodec.releaseOutputBuffer(status, true);
                        //Log.w("DAMSDEBUG","Released frame");
                    }
                }
            }
        }

        public void terminate() {
            isRunning=false ;
        }
    }

    private static class H264helper {
        static final public int TYPE_PPS = 1 ;
        static final public int TYPE_SPS = 2 ;
        static final public int TYPE_NDR = 4 ;
        static final public int TYPE_IDR = 8 ;

        static final private byte[] pattern = {0x00,0x00,0x01} ;

        public static int indexOf(byte[] array, byte[] target, int start) {
            if (target.length == 0) {
                return 0;
            }
            outer: for (int i = start; i < array.length - target.length + 1; i++) {
                for (int j = 0; j < target.length; j++) {
                    if (array[i + j] != target[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
        public static int[] toolFindStartNALs( byte[] ba ) {
            List<Integer> lStartPos = new ArrayList<Integer>();
            int position = 0, found ;
            while(true) {
                found = indexOf(ba, pattern, position) ;
                if( found < 0 ) {
                    break ;
                }
                if( ba[found-1] == 0x00 ) {
                    found-- ;
                }
                lStartPos.add(found) ;
                position = found + 2 ;
            }

            int[] arrPositions = new int[lStartPos.size()] ;
            int idx = 0 ;
            for( int pos : lStartPos ) {
                arrPositions[idx] = pos ;
                idx++ ;
                Log.w("DAMSDEBUG","found NAL start at "+pos);
            }
            return arrPositions;
        }
        public static byte getNALfirstByte( byte[] ba, int startPos ) {
            if( ba[startPos+2] == 0x01 ) { //separator=3
                return ba[startPos+3] ;
            } else if( ba[startPos+2] == 0x00 ) { // separator=4
                return ba[startPos+4] ;
            } else {
                return 0x00 ;
            }
        }

        public static int getNALtypes( byte[] ba ) {
            int mask = 0 ;

            int[] arrPositions = toolFindStartNALs( ba ) ;
            for( int idx=0 ; idx<arrPositions.length ; idx++ ) {
                switch( getNALfirstByte(ba,arrPositions[idx]) & 0x1f ) {
                    case 1 :
                        mask |= TYPE_NDR ;
                        break ;
                    case 5 :
                        mask |= TYPE_IDR ;
                        break ;
                    case 7 :
                        mask |= TYPE_SPS ;
                        break ;
                    case 8 :
                        mask |= TYPE_PPS ;
                        break ;
                }
            }
            return mask ;
        }
    }
}
