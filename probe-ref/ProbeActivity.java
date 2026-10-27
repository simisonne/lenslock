package com.simon.lenslock;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public class ProbeActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setTextSize(16f);
        status.setPadding(48, 200, 48, 48);
        status.setText("LensLock probe: waiting for camera permission");
        setContentView(status);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            return;
        }
        start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            start();
        } else {
            status.setText("camera permission denied");
        }
    }

    private void start() {
        status.setText("LensLock probe running, hold still");
        new Thread(() -> {
            try {
                Probe p = new Probe(getApplicationContext());
                String summary = p.run();
                runOnUiThread(() -> status.setText(summary));
            } catch (Throwable t) {
                Log.e(Probe.TAG, "probe crashed", t);
                runOnUiThread(() -> status.setText("probe crashed: " + t));
            }
        }, "probe").start();
    }
}
