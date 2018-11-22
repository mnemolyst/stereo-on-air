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
    private ServerSocket serverSocket;
    private Socket leftSocket;
    private Socket rightSocket;
    private MediaCodec leftVideoCodec;
    private MediaCodec rightVideoCodec;
    private ArrayBlockingQueue<ByteBuffer> leftBlockingQueue;
    private ArrayBlockingQueue<ByteBuffer> rightBlockingQueue;
    private MediaCodec.Callback leftCodecCallback;
    private MediaCodec.Callback rightCodecCallback;

    private enum Side { LEFT, RIGHT }
    private Side acceptingSocketForSide = Side.LEFT;
    private boolean leftConnected = false;

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

        MediaFormat mediaFormat = MediaFormat.createVideoFormat(MainActivity.mimeType, MainActivity.videoWidth, MainActivity.videoHeight);
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(MainActivity.leftPort));
            serverSocket.setSoTimeout(5_000);
        } catch (IOException exception) {
            Log.e(TAG, exception.getMessage());
            stopSelf();
            return;
        }

        leftBlockingQueue = new ArrayBlockingQueue<>(3);
        leftCodecCallback = createCodecCallback(leftBlockingQueue);

        leftCodecThread = new HandlerThread("leftCodecThread");
        leftCodecThread.start();
        leftCodecHandler = new Handler(leftCodecThread.getLooper());

        leftVideoCodec = createCodec(mediaFormat);
        leftVideoCodec.setCallback(leftCodecCallback, leftCodecHandler);
        leftVideoCodec.configure(mediaFormat, leftOutputSurface, null, 0);
        leftVideoCodec.start();

        leftNetworkThread = new HandlerThread("leftNetworkThread");
        leftNetworkThread.start();
        leftNetworkHandler = new Handler(leftNetworkThread.getLooper());
        leftNetworkHandler.post(createSocketRunnable());

        rightBlockingQueue = new ArrayBlockingQueue<>(3);
        rightCodecCallback = createCodecCallback(rightBlockingQueue);

        rightCodecThread = new HandlerThread("rightCodecThread");
        rightCodecThread.start();
        rightCodecHandler = new Handler(rightCodecThread.getLooper());

        rightVideoCodec = createCodec(mediaFormat);
        rightVideoCodec.setCallback(rightCodecCallback, rightCodecHandler);
        rightVideoCodec.configure(mediaFormat, rightOutputSurface, null, 0);
        rightVideoCodec.start();

        rightNetworkThread = new HandlerThread("rightNetworkThread");
        rightNetworkThread.start();
        rightNetworkHandler = new Handler(rightNetworkThread.getLooper());
        rightNetworkHandler.post(createSocketRunnable());

//        openSockets();
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
            return null;
        }
    }

    private Runnable createSocketRunnable() {

        return new Runnable() {

            private Socket socket;
            private DataInputStream inputStream;
            private ArrayBlockingQueue<ByteBuffer> myQueue;

            @Override
            public void run() {

                if (acceptingSocketForSide == Side.LEFT) {
                    acceptingSocketForSide = Side.RIGHT;
                    myQueue = leftBlockingQueue;
                } else {
                    while (! leftConnected) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException exception) {
                            Log.e(TAG, "Interrupted waiting for left to connect");
                            return;
                        }
                    }
                    myQueue = rightBlockingQueue;
                }
                while (true) {
                    try {
                        try {
                            socket = serverSocket.accept();
                            leftConnected = true;
                            Log.d(TAG, "Connected! Accepted socket on port " + serverSocket.getLocalPort());
                            inputStream = new DataInputStream(socket.getInputStream());
                            break;
                        } catch (SocketTimeoutException exception) {
                            if (Thread.interrupted()) {
                                Log.d(TAG, "Interrupted waiting for socket connection");
                                return;
                            }
                        }
                    } catch (IOException exception) {
                        Log.e(TAG, exception.getMessage());
                        stopSelf();
                    }
                }
                // Make a really big byte array to hold video frames
                byte[] bytes = new byte[10_000];
                int numBytes;
                try {
                    while (true) {
                        numBytes = inputStream.read(bytes);
                        ByteBuffer buffer = ByteBuffer.allocate(numBytes);
                        buffer.put(bytes, 0, numBytes);
                        buffer.rewind();
                        myQueue.put(buffer);
                    }
                } catch (InterruptedException exception) {
                    Log.e(TAG, "Interrupted reading socket input stream");
                } catch (IOException exception) {
                    Log.e(TAG, exception.getMessage());
                    stopSelf();
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
