package za.dams.camera2video;

import android.app.Fragment;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public class PlayVideoFragment extends Fragment {

    private AutoFitSurfaceView mSurfaceView;

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
    }

}
