package com.conectiontv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final int REQUEST_CAPTURE = 100;
    private static final String SERVICE_TYPE = "_conectiontv._tcp.";

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private final List<NsdServiceInfo> tvs = new ArrayList<>();
    private LinearLayout tvList;
    private TextView status;
    private SurfaceView surfaceView;
    private Button stopButton;
    private TvReceiver tvReceiver;
    private NsdServiceInfo selectedTv;
    private boolean isTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        isTv = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK)
                == Configuration.UI_MODE_TYPE_TELEVISION
                || getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        if (isTv) {
            showReceiver();
        } else {
            showPhone();
        }
    }

    private void showReceiver() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(24);
        status.setGravity(Gravity.CENTER);
        status.setText(R.string.tv_starting);
        status.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        statusParams.gravity = Gravity.CENTER;
        root.addView(status, statusParams);
        setContentView(root);

        tvReceiver = new TvReceiver(this, this::setStatus);
        surfaceView.getHolder().addCallback(this);
        tvReceiver.start();
    }

    private void showPhone() {
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Color.rgb(18, 24, 38));
        root.setPadding(32, 48, 32, 24);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        root.addView(title);

        TextView instruction = new TextView(this);
        instruction.setText(R.string.phone_instructions);
        instruction.setTextColor(0xffc7cfdf);
        instruction.setTextSize(16);
        instruction.setPadding(0, 12, 0, 20);
        root.addView(instruction);

        Button scan = new Button(this);
        scan.setText(R.string.find_tvs);
        scan.setOnClickListener(v -> discoverTvs());
        root.addView(scan);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        status.setText(R.string.searching_tvs);
        status.setPadding(0, 18, 0, 8);
        root.addView(status);

        stopButton = new Button(this);
        stopButton.setText(R.string.stop_mirroring);
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenStreamService.class));
            stopButton.setVisibility(View.GONE);
            status.setText(R.string.mirroring_stopped);
        });
        root.addView(stopButton);

        tvList = new LinearLayout(this);
        tvList.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(tvList);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        discoverTvs();
    }

    private void discoverTvs() {
        if (isTv || nsdManager == null) return;
        if (discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (IllegalArgumentException ignored) {
                // Discovery may have stopped while results were being updated.
            }
        }
        tvs.clear();
        tvList.removeAllViews();
        status.setText(R.string.searching_tvs);
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                runOnUiThread(() -> status.setText(getString(R.string.scan_error, errorCode)));
                nsdManager.stopServiceDiscovery(this);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                nsdManager.stopServiceDiscovery(this);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {}

            @Override
            public void onDiscoveryStopped(String serviceType) {}

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (SERVICE_TYPE.equals(serviceInfo.getServiceType())) resolveTv(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                runOnUiThread(() -> {
                    Iterator<NsdServiceInfo> iterator = tvs.iterator();
                    while (iterator.hasNext()) {
                        if (iterator.next().getServiceName().equals(serviceInfo.getServiceName())) {
                            iterator.remove();
                        }
                    }
                    renderTvList();
                });
            }
        };
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    @SuppressWarnings("deprecation")
    private void resolveTv(NsdServiceInfo serviceInfo) {
        nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                runOnUiThread(() -> status.setText(getString(R.string.resolve_error, info.getServiceName())));
            }

            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                runOnUiThread(() -> {
                    for (NsdServiceInfo tv : tvs) {
                        if (tv.getServiceName().equals(info.getServiceName())) return;
                    }
                    tvs.add(info);
                    renderTvList();
                });
            }
        });
    }

    private void renderTvList() {
        tvList.removeAllViews();
        if (tvs.isEmpty()) {
            status.setText(R.string.no_tvs_found);
            return;
        }
        status.setText(R.string.select_tv);
        for (NsdServiceInfo tv : tvs) {
            Button button = new Button(this);
            button.setText(tv.getServiceName());
            button.setOnClickListener(v -> requestScreenCapture(tv));
            tvList.addView(button);
        }
    }

    private void requestScreenCapture(NsdServiceInfo tv) {
        selectedTv = tv;
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null || selectedTv == null) {
            status.setText(R.string.capture_cancelled);
            return;
        }
        InetAddress host = selectedTv.getHost();
        if (host == null || selectedTv.getPort() <= 0) {
            status.setText(R.string.tv_address_error);
            return;
        }
        Intent service = new Intent(this, ScreenStreamService.class);
        service.putExtra(ScreenStreamService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(ScreenStreamService.EXTRA_PROJECTION_DATA, data);
        service.putExtra(ScreenStreamService.EXTRA_HOST, host.getHostAddress());
        service.putExtra(ScreenStreamService.EXTRA_PORT, selectedTv.getPort());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
        stopButton.setVisibility(View.VISIBLE);
        status.setText(getString(R.string.connecting_tv, selectedTv.getServiceName()));
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (tvReceiver != null) tvReceiver.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (tvReceiver != null) tvReceiver.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (tvReceiver != null) tvReceiver.setSurface(null);
    }

    private void setStatus(String message) {
        runOnUiThread(() -> {
            if (status != null) {
                status.setText(message);
                status.setVisibility(isTv && message.startsWith("Transmitindo")
                        ? View.GONE : View.VISIBLE);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (IllegalArgumentException ignored) {
                // Discovery has already stopped.
            }
        }
        if (tvReceiver != null) tvReceiver.stop();
    }
}
