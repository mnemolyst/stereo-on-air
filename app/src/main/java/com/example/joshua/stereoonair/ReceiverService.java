package com.example.joshua.stereoonair;

import android.app.Service;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;

public class ReceiverService extends Service {

    private final static String TAG = "ReceiverService";
    private OnFrameReceivedCallback onFrameReceivedCallback;
    private Handler codecHandler;
    private Handler networkHandler;
    private Surface videoOutputSurface;
    private ServerSocket serverSocket;
    private Socket socket;
    private MediaFormat videoFormat;
    private MediaCodec videoCodec;
    private ArrayBlockingQueue<ByteBuffer> blockingQueue;
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

    static abstract class OnFrameReceivedCallback {
        abstract void onFrameReceived(byte[] bytes);
    }

    public void registerOnFrameReceivedCallback(OnFrameReceivedCallback callback) {
        onFrameReceivedCallback = callback;
    }

    public void setVideoOutputSurface(Surface videoOutputSurface) {
        this.videoOutputSurface = videoOutputSurface;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        blockingQueue = new ArrayBlockingQueue<>(2);

        HandlerThread codecThread = new HandlerThread("receiverCodecThread");
        codecThread.start();
        codecHandler = new Handler(codecThread.getLooper());
        startVideoCodec();

        HandlerThread networkThread = new HandlerThread("receiverNetworkThread");
        networkThread.start();
        networkHandler = new Handler(networkThread.getLooper());
        openSocket();

        return START_NOT_STICKY;
    }

    private void startVideoCodec() {

//        int minWidth = videoOutputSurface
        MediaFormat format;
//        if (videoQuality == CameraService.VideoQuality.HIGH_1080P) {
            format = MediaFormat.createVideoFormat("video/x-vnd.on2.vp8", MainActivity.videoWidth, MainActivity.videoHeight);
//        } else if (videoQuality == CameraService.VideoQuality.MED_720P) {
//            format = MediaFormat.createVideoFormat("video/avc", 1280, 720);
//        } else {
//            Log.e(TAG, "No suitable video resolution found.");
//            stopSelf();
//            return;
//        }

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
//        format.setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000);
//        format.setString(MediaFormat.KEY_FRAME_RATE, null);
//        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);

        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String codecName = codecList.findDecoderForFormat(format);
        Log.d(TAG, "codecName: " + codecName);

//        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);

        try {
            videoCodec = MediaCodec.createByCodecName(codecName);

        } catch (IOException exception) {
            Log.e(TAG, exception.getMessage());
        }
        videoCodec.setCallback(videoCodecCallback, codecHandler);
        videoCodec.configure(format, videoOutputSurface, null, 0);
        videoCodec.start();
    }


    private MediaCodec.Callback videoCodecCallback = new MediaCodec.Callback() {

        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {

//            Log.d(TAG, "onInputBufferAvailable");
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
                ByteBuffer buffer = blockingQueue.take();
                Log.d(TAG, "putting " + buffer.remaining() + " bytes");
                inputBuffer.put(buffer);
                codec.queueInputBuffer(index, 0, buffer.capacity(), presentation++, 0);
                Log.d(TAG, "presentation: " + presentation);
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
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {

            if (videoFormat == null) {
                //Log.d(TAG, "Video format changed");
                videoFormat = format;
            } else {
                //Log.e(TAG, "Video format already changed");
            }
        }
    };

    public void openSocket() {

        Runnable socketRunnable = new Runnable() {

            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(MainActivity.port);
                    socket = serverSocket.accept();
                    Log.d(TAG, "Connected! Accepted socket.");
                    DataInputStream inputStream = new DataInputStream(socket.getInputStream());
                    byte[] bytes = new byte[40_960]; // 40k
                    int numBytes;
                    while (true) {
                        numBytes = inputStream.read(bytes);
                        Log.d(TAG, "numBytes read: " + numBytes);
                        try {
                            ByteBuffer buffer = ByteBuffer.allocate(numBytes);
                            buffer.put(bytes, 0, numBytes);
                            buffer.rewind();
                            blockingQueue.put(buffer);
                        } catch (InterruptedException exception) {
                            Log.e(TAG, exception.getMessage());
                        }
                    }
                } catch (IOException exception) {
                    Log.e(TAG, exception.getMessage());
                }
            }
        };

        networkHandler.post(socketRunnable);
    }

    public void stop() {

        try {
            socket.close();
            serverSocket.close();
            videoCodec.release();
        } catch (IOException exception) {
            Log.e(TAG, exception.getMessage());
        }
    }
}
