package com.example.joshua.stereoonair;

import android.app.Service;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

public class ReceiverService extends Service {

    private final static String TAG = "ReceiverService";
    private HandlerThread leftCodecThread;
    private HandlerThread rightCodecThread;
    private HandlerThread leftNetworkThread;
    private HandlerThread rightNetworkThread;
    private Handler leftCodecHandler;
    private Handler rightCodecHandler;
    private Handler leftNetworkHandler;
    private Handler rightNetworkHandler;
    private Surface leftOutputSurface;
    private Surface rightOutputSurface;
    private ServerSocket leftServerSocket;
    private ServerSocket rightServerSocket;
    private Socket leftSocket;
    private Socket rightSocket;
    private MediaCodec leftVideoCodec;
    private MediaCodec rightVideoCodec;
    private ArrayBlockingQueue<ByteBuffer> leftBlockingQueue;
    private ArrayBlockingQueue<ByteBuffer> rightBlockingQueue;
    private MediaCodec.Callback leftCodecCallback;
    private MediaCodec.Callback rightCodecCallback;

    private int presentation = 0;

    class receiverServiceBinder extends Binder {

        ReceiverService getService() {

            return ReceiverService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {

        Log.d(TAG, "onBind");
        return new receiverServiceBinder();
    }

    public void setVideoOutputSurfaces(Surface leftSurface, Surface rightSurface) {

        Log.d(TAG, "setVideoOutputSurfaces");
        this.leftOutputSurface = leftSurface;
        this.rightOutputSurface = rightSurface;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.d(TAG, "onStartCommand");
        start();
        return START_NOT_STICKY;
    }

    public void start() {

        leftBlockingQueue = new ArrayBlockingQueue<>(3);
        leftCodecCallback = this.createCodecCallback(leftBlockingQueue);

        rightBlockingQueue = new ArrayBlockingQueue<>(3);
        rightCodecCallback = this.createCodecCallback(rightBlockingQueue);

        leftCodecThread = new HandlerThread("leftCodecThread");
        leftCodecThread.start();
        leftCodecHandler = new Handler(leftCodecThread.getLooper());

        rightCodecThread = new HandlerThread("rightCodecThread");
        rightCodecThread.start();
        rightCodecHandler = new Handler(rightCodecThread.getLooper());

        startCodecs();

        leftNetworkThread = new HandlerThread("leftNetworkThread");
        leftNetworkThread.start();
        leftNetworkHandler = new Handler(leftNetworkThread.getLooper());

        rightNetworkThread = new HandlerThread("rightNetworkThread");
        rightNetworkThread.start();
        rightNetworkHandler = new Handler(rightNetworkThread.getLooper());

        openSockets();
    }

    private MediaCodec.Callback createCodecCallback(final ArrayBlockingQueue<ByteBuffer> queue) {

        return new MediaCodec.Callback() {

            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {

                ByteBuffer inputBuffer;
                try {
                    inputBuffer = codec.getInputBuffer(index);
                } catch (IllegalStateException e) {
                    e.printStackTrace();
                    return;
                }

                if (inputBuffer == null) {
                    return;
                }

                try {
                    ByteBuffer buffer = queue.take();
                    Log.d(TAG, "putting " + buffer.remaining() + " bytes");
                    inputBuffer.put(buffer);
                    codec.queueInputBuffer(index, 0, buffer.capacity(), presentation++, 0);
                } catch (InterruptedException exception) {
                    Log.e(TAG, "Interrupted reading from ByteBuffer queue");
                }
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {

                try {
                    codec.releaseOutputBuffer(index, info.presentationTimeUs);
                } catch (IllegalStateException exception) {
                    Log.e(TAG, exception.getMessage());
                }
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                Log.e(TAG, "MediaCodec.Callback.onError", e);
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) { }
        };
    };

    private void startCodecs() {

        MediaFormat mediaFormat = MediaFormat.createVideoFormat(MainActivity.mimeType, MainActivity.videoWidth, MainActivity.videoHeight);

        leftVideoCodec = this.createCodec(mediaFormat);
        leftVideoCodec.setCallback(leftCodecCallback, leftCodecHandler);
        leftVideoCodec.configure(mediaFormat, leftOutputSurface, null, 0);
        leftVideoCodec.start();
        rightVideoCodec = this.createCodec(mediaFormat);
        rightVideoCodec.setCallback(rightCodecCallback, rightCodecHandler);
        rightVideoCodec.configure(mediaFormat, rightOutputSurface, null, 0);
        rightVideoCodec.start();
    }

    private MediaCodec createCodec(MediaFormat mediaFormat) {

        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String codecName = codecList.findDecoderForFormat(mediaFormat);
//        Log.d(TAG, "codecName: " + codecName);

        try {
            return MediaCodec.createByCodecName(codecName);
//            leftVideoCodec = MediaCodec.createDecoderByType(MainActivity.mimeType);

        } catch (IOException exception) {
            Log.e(TAG, exception.getMessage());
            stopSelf();
        }
        return null;
    }

    public void openSockets() {

        Runnable leftRunnable = this.createSocketRunnable(MainActivity.leftPort, leftBlockingQueue);
        leftNetworkHandler.post(leftRunnable);

        Runnable rightRunnable = this.createSocketRunnable(MainActivity.rightPort, rightBlockingQueue);
        rightNetworkHandler.post(rightRunnable);
    }

    private Runnable createSocketRunnable(final int port, final ArrayBlockingQueue<ByteBuffer> queue) {

        return new Runnable() {

            private ServerSocket serverSocket;
            private Socket socket;
            private DataInputStream inputStream;

            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress(port));
                    serverSocket.setSoTimeout(5_000);

                    while (true) {
                        try {
                            socket = serverSocket.accept();
                            Log.d(TAG, "Connected! Accepted socket on port " + port);
                            inputStream = new DataInputStream(socket.getInputStream());
                            break;
                        } catch (SocketTimeoutException exception) {
                            if (Thread.interrupted()) {
                                Log.d(TAG, "interrupted");
                                serverSocket.close();
                                return;
                            }
                        }
                    }
                    // Make a really big byte array to hold video frames
                    byte[] bytes = new byte[100_000];
                    int numBytes;
                    while (true) {
                        try {
                            numBytes = inputStream.read(bytes);
                            ByteBuffer buffer = ByteBuffer.allocate(numBytes);
                            buffer.put(bytes, 0, numBytes);
                            buffer.rewind();
                            queue.put(buffer);
                        } catch (InterruptedException exception) {
                            Log.e(TAG, "Interrupted reading socket input stream");
                        } finally {
                            serverSocket.close();
                        }
                    }
                } catch (IOException exception) {
                    Log.e(TAG, exception.getMessage());
                }
            }
        };
    }

    public void stop() {

//        try {
        Log.d(TAG, "stop");
        leftVideoCodec.stop();
        leftVideoCodec.release();
        leftCodecThread.interrupt();

        rightVideoCodec.stop();
        rightVideoCodec.release();
        rightCodecThread.interrupt();

        leftNetworkThread.interrupt();
        rightNetworkThread.interrupt();
//        } catch (IOException exception) {
//            Log.e(TAG, exception.getMessage());
//        }
    }
}
