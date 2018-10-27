package com.example.joshua.stereoonair;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
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

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static com.example.joshua.stereoonair.MainActivity.port;

public class CameraService extends Service {

    private final static String TAG = "CameraService";
    private final static int ONGOING_NOTIFICATION_ID = 1;

    enum State {
        STOPPING, STOPPED, STARTING, STARTED
    }
    enum VideoQuality {
        HIGH_1080P, MED_720P
    }
    private State state = State.STOPPED;
    private static VideoQuality videoQuality = VideoQuality.HIGH_1080P;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession cameraCaptureSession;
    private MediaCodec videoCodec;
    private Surface videoInputSurface;
    private MediaFormat videoFormat;
    private Integer sensorOrientation = 0;
    private Socket socket;
    private String receiverAddress;

    private OnStartCameraCallback onStartCameraCallback;
    private OnStopCameraCallback onStopCameraCallback;

    static abstract class OnStartCameraCallback {
        abstract void onStartCamera();
    }

    static abstract class OnStopCameraCallback {
        abstract void onStopCamera();
    }

    public void registerOnStartCameraCallback(OnStartCameraCallback callback) {
        onStartCameraCallback = callback;
    }

    public void registerOnStopCameraCallback(OnStopCameraCallback callback) {
        onStopCameraCallback = callback;
    }

    class cameraServiceBinder extends Binder {

        CameraService getService() {

            return CameraService.this;
        }
    }

    @Override
    public void onCreate() {

        //Log.d(TAG, "onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.d(TAG, "onStartCommand");

        state = State.STOPPED;

        HandlerThread thread = new HandlerThread("receiverThread");
        thread.start();
        Looper looper = thread.getLooper();
        Handler handler = new Handler(looper);

        Runnable runnable = new Runnable() {

            public void run() {

                openSocket();
//                Log.d(TAG, "onStartCommand new thread id: " + String.valueOf(Thread.currentThread().getId()));
//                if (getApplicationContext().checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED) {
//                    Log.e(TAG, "No camera permission!");
//                    stopSelf();
//                    return;
//                }
//
//                CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
//
//                try {
//
//                    String[] cameraIdList = cameraManager.getCameraIdList();
//                    for (String id : cameraIdList) {
//
//                        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
//                        int lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
//                        if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
//
//                            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
//
//                            cameraManager.openCamera(id, cameraStateCallback, null);
//                            break;
//                        }
//                    }
//                } catch (CameraAccessException e) {
//                    e.printStackTrace();
//                }
            }
        };

        handler.post(runnable);

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {

        //Log.d(TAG, "onBind");
        return new cameraServiceBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {

        //Log.d(TAG, "onUnbind");
        return false;
    }

    @Override
    public void onDestroy() {

       //Log.d(TAG, "onDestroy");
        releaseResources();
    }

    private CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice camera) {

            Log.d(TAG, "cameraStateCallback.onOpened thread id: " + String.valueOf(Thread.currentThread().getId()));

            cameraDevice = camera;
            state = State.STARTING;
            videoFormat = null;
            prepareForRecording();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {

            Log.d(TAG, "cameraStateCallback.onDisconnected");
            cameraStopped();
        }

        @Override
        public void onError(CameraDevice camera, int error) {

            Log.e(TAG, "cameraStateCallback.onError");
            stopSelf();
        }
    };

    private void prepareForRecording() {

        try {
            startVideoCodec();
            startCamera();
            openSocket();
        } catch (IOException e) {
            e.printStackTrace();
            stopSelf();
            return;
        }

        notifyForeground();
    }

    private void startVideoCodec() throws IOException {

        MediaFormat format;
        if (videoQuality == VideoQuality.HIGH_1080P) {
            format = MediaFormat.createVideoFormat("video/avc", 1920, 1080);
        } else if (videoQuality == VideoQuality.MED_720P) {
            format = MediaFormat.createVideoFormat("video/avc", 1280, 720);
        } else {
            Log.e(TAG, "No suitable video resolution found.");
            stopSelf();
            return;
        }

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000);
        format.setString(MediaFormat.KEY_FRAME_RATE, null);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);

        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String codecName = codecList.findEncoderForFormat(format);
        Log.d(TAG, "codecName: " + codecName);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);

        videoCodec = MediaCodec.createByCodecName(codecName);
        videoCodec.setCallback(videoCodecCallback);
        videoCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        videoInputSurface = videoCodec.createInputSurface();
        videoCodec.start();
    }

    private void startCamera() {

        if (cameraDevice == null) {
            Log.e(TAG, "cameraDevice is null");
            stopSelf();
            return;
        }

        if (videoInputSurface == null) {
            Log.e(TAG, "videoInputSurface is null");
            stopSelf();
            return;
        }

        try {
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            captureRequestBuilder.addTarget(videoInputSurface);
            cameraDevice.createCaptureSession(Arrays.asList(videoInputSurface), captureSessionStateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void setReceiverAddress(String address) {
        receiverAddress = address;
    }

    private void openSocket() {

        Log.d(TAG, "cameraService openSocket thread id: " + String.valueOf(Thread.currentThread().getId()));
        try {
            socket = new Socket();
            socket.bind(null);
            socket.connect(new InetSocketAddress(receiverAddress, port));
            DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream());
            String s = "hello world";
            outputStream.write(s.getBytes("UTF-8"));
            outputStream.flush();
            Log.d(TAG, "openSocket: bytes written");
        } catch (IOException exception) {
            Log.e(TAG, exception.getMessage());
        }
    }

    private CameraCaptureSession.StateCallback captureSessionStateCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(CameraCaptureSession session) {

            cameraCaptureSession = session;

            try {
                session.setRepeatingRequest(captureRequestBuilder.build(), null, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
                stopSelf();
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            Log.e(TAG, "captureSessionStateCallback.onConfigureFailed");
            stopSelf();
        }

        @Override
        public void onClosed(CameraCaptureSession session) {
            Log.d(TAG, "captureSessionStateCallback.onClosed");
            cameraStopped();
        }
    };

    private MediaCodec.Callback videoCodecCallback = new MediaCodec.Callback() {

        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) { }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {

            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                return;
            }

            if (state == State.STARTING) {

                state = State.STARTED;

                if (onStartCameraCallback != null) {
                    onStartCameraCallback.onStartCamera();
                }
            }

            if (state != State.STARTED) {
                return;
            }

            ByteBuffer outputBuffer;
            try {
                outputBuffer = codec.getOutputBuffer(index);
            } catch (IllegalStateException e) {
                e.printStackTrace();
                return;
            }

            if (outputBuffer == null) {
                return;
            }

            byte[] bufferBytes = new byte[outputBuffer.remaining()]; // TODO transfer these bytes to receiver
            outputBuffer.get(bufferBytes);

            codec.releaseOutputBuffer(index, false);

            if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == MediaCodec.BUFFER_FLAG_KEY_FRAME) {
            }

            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
               //Log.d(TAG, "videoCodec EOS");
            }
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

    private String createNotificationChannel() {
        String channelId = "record_service";
        String channelName = "Background Recording Service";
        NotificationChannel chan = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT);
        chan.setLightColor(Color.BLUE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager service = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        service.createNotificationChannel(chan);
        return channelId;
    }

    private void notifyForeground() {

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        String channelId;
        Notification.Builder builder;
        Notification notification;

        channelId = createNotificationChannel();
        builder = new Notification.Builder(this, channelId);

        notification = builder
                .setContentTitle(getText(R.string.notification_title))
//                .setContentText(getText(R.string.notification_message))
//                .setSmallIcon(R.drawable.recording_notification_icon)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(ONGOING_NOTIFICATION_ID, notification);
    }

    State getState() {
        return state;
    }

    void stopCamera() {

        if (state.equals(State.STARTED)) {

            state = State.STOPPING;
            videoCodec.signalEndOfInputStream();

            try {
                cameraCaptureSession.abortCaptures();
                cameraCaptureSession.close();
            } catch (CameraAccessException | IllegalStateException e) {
                //e.printStackTrace();
                cameraStopped();
            }
        }
    }

    private void cameraStopped() {

       //Log.d(TAG, "cameraStopped");
        releaseResources();

        if (state.equals(State.STARTED)) {

            state = State.STOPPING;
//            videoCodec.signalEndOfInputStream();
        }

        if (onStopCameraCallback != null) {
            onStopCameraCallback.onStopCamera();
        }

        state = State.STOPPED;
        stopForeground(true);
        stopSelf();
    }

    private void releaseResources() {

        if (videoCodec != null) {
            videoCodec.release();
        }
        if (videoInputSurface != null) {
            videoInputSurface.release();
        }
        if (cameraDevice != null) {
            cameraDevice.close();
        }
    }

    public static void setVideoQuality(String pref, String q1080p, String q720p) {
       //Log.d(TAG, "setVideoQuality: " + pref);
        if (pref.equals(q1080p)) {
            CameraService.videoQuality = VideoQuality.HIGH_1080P;
        } else if (pref.equals(q720p)) {
            CameraService.videoQuality = VideoQuality.MED_720P;
        }
    }
}
