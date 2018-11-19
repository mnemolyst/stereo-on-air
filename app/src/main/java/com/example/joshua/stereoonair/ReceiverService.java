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
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

public class ReceiverService extends Service {

    private final static String TAG = "ReceiverService";
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
    private int presentation = 0;

    class receiverServiceBinder extends Binder {

        ReceiverService getService() {

            return ReceiverService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {

        return new receiverServiceBinder();
    }

    public void setVideoOutputSurfaces(Surface leftSurface, Surface rightSurface) {
        this.leftOutputSurface = leftSurface;
        this.rightOutputSurface = rightSurface;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        leftBlockingQueue = new ArrayBlockingQueue<>(3);
        rightBlockingQueue = new ArrayBlockingQueue<>(3);

        HandlerThread leftCodecThread = new HandlerThread("leftCodecThread");
        leftCodecThread.start();
        leftCodecHandler = new Handler(leftCodecThread.getLooper());

        HandlerThread rightCodecThread = new HandlerThread("rightCodecThread");
        rightCodecThread.start();
        rightCodecHandler = new Handler(rightCodecThread.getLooper());

        startCodecs();

        HandlerThread leftNetworkThread = new HandlerThread("leftNetworkThread");
        leftNetworkThread.start();
        leftNetworkHandler = new Handler(leftNetworkThread.getLooper());

        HandlerThread rightNetworkThread = new HandlerThread("rightNetworkThread");
        rightNetworkThread.start();
        leftNetworkHandler = new Handler(rightNetworkThread.getLooper());

        openSockets();

        return START_NOT_STICKY;
    }

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
        }
        return null;
    }

    private MediaCodec.Callback leftCodecCallback = this.createCodecCallback(leftBlockingQueue);

    private MediaCodec.Callback rightCodecCallback = this.createCodecCallback(rightBlockingQueue);

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
//                    Log.d(TAG, "putting " + buffer.remaining() + " bytes");
                    inputBuffer.put(buffer);
                    codec.queueInputBuffer(index, 0, buffer.capacity(), presentation++, 0);
                } catch (InterruptedException exception) {
                    Log.e(TAG, exception.getMessage());
                }
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {

                codec.releaseOutputBuffer(index, info.presentationTimeUs);
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                Log.e(TAG, "MediaCodec.Callback.onError", e);
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) { }
        };
    };

    private Runnable createSocketRunnable(final int port, final ArrayBlockingQueue<ByteBuffer> queue) {

        return new Runnable() {

            private ServerSocket serverSocket;
            private Socket socket;

            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(port);
                    socket = leftServerSocket.accept();
                    Log.d(TAG, "Connected! Accepted socket on port " + port);
                    DataInputStream inputStream = new DataInputStream(leftSocket.getInputStream());
                    byte[] bytes = new byte[40_960]; // 40k
                    int numBytes;
                    while (true) {
                        numBytes = inputStream.read(bytes);
//                        Log.d(TAG, "numBytes read: " + numBytes);
                        try {
                            ByteBuffer buffer = ByteBuffer.allocate(numBytes);
                            buffer.put(bytes, 0, numBytes);
                            buffer.rewind();
                            queue.put(buffer);
                        } catch (InterruptedException exception) {
                            Log.e(TAG, exception.getMessage());
                        }
                    }
                } catch (IOException exception) {
                    Log.e(TAG, exception.getMessage());
                } finally {
                    try {
                        socket.close();
                        serverSocket.close();
                    } catch (IOException exception) {
                        Log.e(TAG, exception.getMessage());
                    }
                }
            }
        };
    }

    public void openSockets() {

        Runnable leftRunnable = this.createSocketRunnable(MainActivity.leftPort, leftBlockingQueue);
        leftNetworkHandler.post(leftRunnable);

        Runnable rightRunnable = this.createSocketRunnable(MainActivity.rightPort, rightBlockingQueue);
        leftNetworkHandler.post(rightRunnable);
    }

    public void stop() {

//        try {
            leftVideoCodec.stop();
            leftVideoCodec.release();
            rightVideoCodec.stop();
            rightVideoCodec.release();
//        } catch (IOException exception) {
//            Log.e(TAG, exception.getMessage());
//        }
    }
}
