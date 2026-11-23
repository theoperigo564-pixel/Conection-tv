package com.conectiontv;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.view.Surface;

import java.io.DataInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

final class TvReceiver {
    interface StatusListener {
        void onStatus(String message);
    }

    private static final String SERVICE_TYPE = "_conectiontv._tcp.";
    private static final int STREAM_MAGIC = 0x43545631;
    private static final int MAX_CONFIG_SIZE = 1024 * 1024;
    private static final int MAX_FRAME_SIZE = 16 * 1024 * 1024;

    private final Context context;
    private final StatusListener statusListener;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Surface surface;
    private volatile ServerSocket serverSocket;
    private volatile Socket clientSocket;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;

    TvReceiver(Context context, StatusListener listener) {
        this.context = context.getApplicationContext();
        statusListener = listener;
    }

    void start() {
        if (!running.compareAndSet(false, true)) return;
        new Thread(this::listen, "tv-receiver").start();
    }

    void setSurface(Surface surface) {
        this.surface = surface;
    }

    private void listen() {
        try {
            ServerSocket server = new ServerSocket(0);
            serverSocket = server;
            registerService(server.getLocalPort());
            statusListener.onStatus("Conection TV\nPronto para receber uma conexão.");
            while (running.get()) {
                Socket client = server.accept();
                clientSocket = client;
                statusListener.onStatus("Conectando ao celular...");
                try {
                    receive(client);
                } catch (Exception e) {
                    if (running.get()) {
                        String detail = e.getMessage();
                        statusListener.onStatus((detail == null ? "Conexão encerrada." : detail)
                                + " Aguardando outro celular.");
                    }
                } finally {
                    clientSocket = null;
                    try {
                        client.close();
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            if (running.get()) statusListener.onStatus("Falha ao iniciar o receptor: " + e.getMessage());
        }
    }

    private void registerService(int port) {
        nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        NsdServiceInfo service = new NsdServiceInfo();
        service.setServiceName("Conection TV");
        service.setServiceType(SERVICE_TYPE);
        service.setPort(port);
        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                statusListener.onStatus("Falha ao anunciar a TV na rede (erro " + errorCode + ").");
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}

            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {}

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {}
        };
        nsdManager.registerService(service, NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }

    private void receive(Socket client) throws Exception {
        DataInputStream input = new DataInputStream(client.getInputStream());
        if (input.readInt() != STREAM_MAGIC) throw new IllegalStateException("Formato de transmissão inválido.");
        int width = input.readInt();
        int height = input.readInt();
        if (width < 2 || height < 2 || width > 8192 || height > 8192) {
            throw new IllegalStateException("Resolução de vídeo inválida.");
        }

        MediaCodec decoder = null;
        try {
            while (running.get() && !client.isClosed()) {
                int type = input.readUnsignedByte();
                if (type == 0) {
                    byte[] csd0 = readSized(input, MAX_CONFIG_SIZE);
                    byte[] csd1 = readSized(input, MAX_CONFIG_SIZE);
                    if (csd0.length == 0) throw new IllegalStateException("Configuração de vídeo vazia.");
                    if (decoder != null) {
                        decoder.stop();
                        decoder.release();
                    }
                    decoder = createDecoder(width, height, csd0, csd1);
                    statusListener.onStatus("Transmitindo tela do celular.");
                } else if (type == 1) {
                    long presentationTimeUs = input.readLong();
                    int flags = input.readInt();
                    byte[] frame = readSized(input, MAX_FRAME_SIZE);
                    if (decoder == null) throw new IllegalStateException("O vídeo não foi configurado.");
                    renderFrame(decoder, frame, presentationTimeUs, flags);
                } else {
                    throw new IllegalStateException("Pacote de vídeo desconhecido.");
                }
            }
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Exception ignored) {}
                decoder.release();
            }
        }
    }

    private MediaCodec createDecoder(int width, int height, byte[] csd0, byte[] csd1) throws Exception {
        Surface target = surface;
        if (target == null || !target.isValid()) {
            throw new IllegalStateException("A superfície de vídeo da TV não está pronta.");
        }
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
        if (csd1.length > 0) format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1));
        MediaCodec decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        decoder.configure(format, target, null, 0);
        decoder.start();
        return decoder;
    }

    private void renderFrame(MediaCodec decoder, byte[] frame, long pts, int flags) throws Exception {
        int inputIndex = -1;
        while (running.get()
                && (inputIndex = decoder.dequeueInputBuffer(10000)) < 0) {
            drain(decoder);
        }
        if (!running.get() || inputIndex < 0) return;
        ByteBuffer buffer = decoder.getInputBuffer(inputIndex);
        if (buffer == null || frame.length > buffer.capacity()) {
            throw new IllegalStateException("O quadro excede o buffer do decodificador.");
        }
        buffer.clear();
        buffer.put(frame);
        decoder.queueInputBuffer(inputIndex, 0, frame.length, pts, flags & MediaCodec.BUFFER_FLAG_KEY_FRAME);
        drain(decoder);
    }

    private void drain(MediaCodec decoder) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int outputIndex;
        while ((outputIndex = decoder.dequeueOutputBuffer(info, 0)) >= 0) {
            decoder.releaseOutputBuffer(outputIndex, true);
        }
    }

    private static byte[] readSized(DataInputStream input, int maxSize) throws Exception {
        int size = input.readInt();
        if (size < 0 || size > maxSize) throw new IllegalStateException("Tamanho de pacote inválido.");
        byte[] bytes = new byte[size];
        input.readFully(bytes);
        return bytes;
    }

    void stop() {
        running.set(false);
        closeSocket(clientSocket);
        closeSocket(serverSocket);
        if (nsdManager != null && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (IllegalArgumentException ignored) {}
            registrationListener = null;
        }
    }

    private static void closeSocket(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {}
    }
}
