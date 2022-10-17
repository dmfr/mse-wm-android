package za.dams.camera2video;

import android.media.MediaCodec;
import android.util.Log;

import java.nio.ByteBuffer;

import okhttp3.WebSocket;
import okio.ByteString;

public class EncoderThread extends Thread {
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
                    }
                }
            }
        }

        public void terminate() {
            isRunning=false ;
        }
}
