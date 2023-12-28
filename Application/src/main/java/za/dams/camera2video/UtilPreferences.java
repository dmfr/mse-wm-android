package za.dams.camera2video;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.preference.PreferenceManager;
import android.util.Size;

public class UtilPreferences {
    private Context mContext ;
    private SharedPreferences mSharedPreferences;

    private static final String default_videoBitrate = "2000" ;
    private static final String default_videoCodec = "avc" ;
    private static final String default_videoProfile = "baseline" ;
    private static final String default_videoResolution = "720p" ;

    public UtilPreferences(Context context) {
        this.mContext = context.getApplicationContext() ;
        this.mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public String getVideoCodec() {
        String strCodec = mSharedPreferences.getString("video_codec", default_videoCodec) ;
        if( strCodec.equals("avc") ) {
            return MediaFormat.MIMETYPE_VIDEO_AVC ;
        } else if( strCodec.equals("hevc") ) {
            return MediaFormat.MIMETYPE_VIDEO_HEVC ;
        } else {
            return null ;
        }
    }
    public int getVideoBitrate() {
        String strBitrateK = mSharedPreferences.getString("video_bitrate", default_videoBitrate) ;
        int bitrateK = Integer.parseInt(strBitrateK) ;
        if( bitrateK < 500 ) bitrateK=500 ;
        if( bitrateK > 10000 ) bitrateK=10000 ;
        return bitrateK * 1000 ;
    }
    public int getVideoProfile() {
        String strProfile = mSharedPreferences.getString("video_profile", default_videoProfile) ;
        if( strProfile.equals("baseline") ) {
            return MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline ;
        } else if( strProfile.equals("main") ) {
            return MediaCodecInfo.CodecProfileLevel.AVCProfileMain ;
        } else if( strProfile.equals("high") ) {
            return MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh ;
        } else {
            return MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline ;
        }
    }
    public Size getVideoResolution(){
        String strResolution = mSharedPreferences.getString("video_resolution", default_videoResolution) ;
        if( strResolution.equals("720p") ) {
            return new Size(1280,720) ;
        } else if( strResolution.equals("1080p") ) {
            return new Size(1920,1080) ;
        } else {
            return new Size(1280,720) ;
        }
    }



    public String getPeerWebsocketRecordUrl(){
        String wsWebsocketUrlBase = mSharedPreferences.getString("peer_wsUrlBase", mContext.getString(R.string.peer_wsUrlBase) ) ;
        while( wsWebsocketUrlBase.endsWith("/") ) {
            wsWebsocketUrlBase = wsWebsocketUrlBase.substring(0,wsWebsocketUrlBase.length()-1) ;
        }

        String videoFormat = "undefined" ;
        switch( getVideoCodec() ) {
            case MediaFormat.MIMETYPE_VIDEO_HEVC :
                videoFormat = "hevc" ;
                break ;
            case MediaFormat.MIMETYPE_VIDEO_AVC:
                videoFormat = "avc" ;
                break ;
        }

        return wsWebsocketUrlBase+"/record"+"?"+"format="+videoFormat+"&audio=1" ;
    }
    public String getPeerWebsocketPlayUrl(){
        String wsWebsocketUrlBase = mSharedPreferences.getString("peer_wsUrlBase", mContext.getString(R.string.peer_wsUrlBase) ) ;
        while( wsWebsocketUrlBase.endsWith("/") ) {
            wsWebsocketUrlBase = wsWebsocketUrlBase.substring(0,wsWebsocketUrlBase.length()-1) ;
        }
        return wsWebsocketUrlBase+"/replay" ;
    }



    public boolean getDebugPlaybuffer() {
        return mSharedPreferences.getBoolean("debug_playBuffer",false) ;
    }
}
