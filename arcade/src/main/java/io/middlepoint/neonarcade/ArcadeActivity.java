package io.middlepoint.neonarcade;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** Full-screen Android TV game hub. Runs fully offline; no accounts or ads. */
public final class ArcadeActivity extends Activity {
    private NeonView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        view = new NeonView(this);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        setContentView(view);
        view.requestFocus();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (view != null && view.handleKey(keyCode, true, event.getRepeatCount())) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (view != null && view.handleKey(keyCode, false, 0)) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (view != null && view.handleMotion(event)) return true;
        return super.onGenericMotionEvent(event);
    }

    @Override public void onBackPressed() {
        if (view != null && view.goBack()) return;
        super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        if (view != null) view.resume();
    }

    @Override protected void onPause() {
        if (view != null) view.pause();
        super.onPause();
    }
}
