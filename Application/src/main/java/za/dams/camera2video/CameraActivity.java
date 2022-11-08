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

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

public class CameraActivity extends Activity {

    WifiManager.WifiLock mWifiLock ;

    private static final int REQUEST_VIDEO_PERMISSIONS = 1;
    private static final String[] VIDEO_PERMISSIONS = {
            Manifest.permission.CAMERA
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mWifiLock = ((WifiManager)getSystemService(Context.WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"za.dams.camera2video.WIFI_MODE_FULL_HIGH_PERF") ;

        if( !hasPermissionsGranted(VIDEO_PERMISSIONS)) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_VIDEO_PERMISSIONS);
            return ;
        }
        setContentView(R.layout.activity_camera);
        if (null == savedInstanceState) {
            getFragmentManager().beginTransaction()
                    .replace(R.id.container, Camera2VideoFragment.newInstance())
                    .commit();
        }
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
    @Override
    protected void onResume() {
        super.onResume();
        mWifiLock.acquire();
    }
    @Override
    protected void onPause() {
        mWifiLock.release();
        super.onPause();
    }


    public void openPreferences() {
        getFragmentManager().beginTransaction()
                .addToBackStack("camera_preferences")
                .replace(R.id.container, CameraPreferencesFragment.newInstance())
                .commit();
    }

    public void openPlayer() {
        getFragmentManager().beginTransaction()
                .addToBackStack("play_video")
                .replace(R.id.container, PlayVideoFragment.newInstance())
                .commit();
    }

    private boolean hasPermissionsGranted(String[] permissions) {
        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(this,permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_VIDEO_PERMISSIONS) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
                finish();
            } else {
                Intent intent = getIntent();
                finish();
                startActivity(intent);
            }
        }
    }

}
