package com.conectiontv;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;

import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

public class ScreenStreamService extends Service {
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_PROJECTION_DATA = "projectionData";
    static final String EXTRA_HOST = "host";
    static final String EXTRA_PORT = "port";
    private static final String ACTION_STOP = "com.conectiontv.STOP_STREAM";

    private static final String CHANNEL_ID = "screen_stream";
    private static final int STREAM_MAGIC = 0x43545631;
    private volatile boolean running;
    private volatile Socket socket;
    private MediaProjection projection;
    private MediaCodec encoder;
    private VirtualDisplay virtualDisplay;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            running = false;
            closeResources();
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startForeground(1, buildNotification());
        if (intent == null || !intent.hasExtra(EXTRA_PROJECTION_DATA)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        running = true;
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent projectionData = getProjectionData(intent);
        String host = intent.getStringExtra(EXTRA_HOST);
        int port = intent.getIntExtra(EXTRA_PORT, -1);
        new Thread(() -> stream(resultCode, projectionData, host, port), "screen-stream").start();
        return START_NOT_STICKY;
    }

    private Intent getProjectionData(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent.class);
        }
        //noinspection deprecation
        return intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
    }

    private void stream(int resultCode, Intent data, String host, int port) {
        String failure = null;
        try {
            if (resultCode != Activity.RESULT_OK || data == null || host == null || port < 1) {
                throw new IllegalArgumentException("Autorização ou endereço da TV inválido.");
            }
            Socket connection = new Socket();
            socket = connection;
            connection.setTcpNoDelay(true);
            connection.connect(new InetSocketAddress(host, port), 10000);

            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, data);
            if (projection == null) throw new IllegalStateException("O Android não iniciou a captura de tela.");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    running = false;
                    projection = null;
                    closeResources();
                    stopForeground(true);
                    stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));

            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            int[] dimensions = videoDimensions(metrics.widthPixels, metrics.heightPixels);
            int width = dimensions[0];
            int height = dimensions[1];
            int density = metrics.densityDpi;

            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface inputSurface = encoder.createInputSurface();
            encoder.start();

            DataOutputStream output = new DataOutputStream(connection.getOutputStream());
            output.writeInt(STREAM_MAGIC);
            output.writeInt(width);
            output.writeInt(height);
            output.flush();

            virtualDisplay = projection.createVirtualDisplay(
                    "Conection TV", width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface, null, null);
            if (virtualDisplay == null) throw new IllegalStateException("Não foi possível iniciar a captura.");
            encode(output);
        } catch (Exception e) {
            if (running) failure = e.getMessage() == null ? "Falha na transmissão." : e.getMessage();
        } finally {
            running = false;
            closeResources();
            stopForeground(true);
            stopSelf();
        }
        if (failure != null) showError(failure);
    }

    private void encode(DataOutputStream output) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean formatSent = false;
        while (running) {
            int index = encoder.dequeueOutputBuffer(info, 10000);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat actual = encoder.getOutputFormat();
                byte[] csd0 = readBuffer(actual.getByteBuffer("csd-0"));
                byte[] csd1 = actual.containsKey("csd-1") ? readBuffer(actual.getByteBuffer("csd-1")) : new byte[0];
                output.writeByte(0);
                output.writeInt(csd0.length);
                output.write(csd0);
                output.writeInt(csd1.length);
                output.write(csd1);
                output.flush();
                formatSent = true;
                continue;
            }
            if (index < 0) continue;

            ByteBuffer buffer = encoder.getOutputBuffer(index);
            if (buffer != null && formatSent && info.size > 0
                    && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                byte[] frame = new byte[info.size];
                buffer.get(frame);
                output.writeByte(1);
                output.writeLong(info.presentationTimeUs);
                output.writeInt(info.flags);
                output.writeInt(frame.length);
                output.write(frame);
                output.flush();
            }
            encoder.releaseOutputBuffer(index, false);
        }
    }

    private static byte[] readBuffer(ByteBuffer source) {
        if (source == null) return new byte[0];
        ByteBuffer copy = source.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private static int[] videoDimensions(int screenWidth, int screenHeight) {
        int maxWidth = screenWidth >= screenHeight ? 1280 : 720;
        int maxHeight = screenWidth >= screenHeight ? 720 : 1280;
        double scale = Math.min(1.0, Math.min(
                (double) maxWidth / screenWidth, (double) maxHeight / screenHeight));
        int width = Math.max(2, ((int) Math.round(screenWidth * scale)) & ~1);
        int height = Math.max(2, ((int) Math.round(screenHeight * scale)) & ~1);
        return new int[]{width, height};
    }

    private void closeResources() {
        Socket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {}
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (Exception ignored) {}
            encoder.release();
            encoder = null;
        }
        MediaProjection currentProjection = projection;
        projection = null;
        if (currentProjection != null) currentProjection.stop();
    }

    private void showError(String message) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            manager.notify(2, builder
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Conection TV")
                    .setContentText(message)
                    .setAutoCancel(true)
                    .build());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Espelhamento de tela", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Intent stopIntent = new Intent(this, ScreenStreamService.class).setAction(ACTION_STOP);
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return builder.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Conection TV")
                .setContentText("Espelhando a tela.")
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Parar",
                        PendingIntent.getService(this, 1, stopIntent, pendingIntentFlags))
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        closeResources();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
