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
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;

public class CameraService extends Service {

    private final static String TAG = "CameraService";
    private final static int ONGOING_NOTIFICATION_ID = 1;

    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession cameraCaptureSession;
    private MediaCodec videoCodec;
    private Surface videoInputSurface;
    private MediaFormat videoFormat;
//    private Integer sensorOrientation = 0;
//    private StreamConfigurationMap configurationMap;
    private Socket socket;
    private Handler codecHandler;
    private Handler networkHandler;
    private ArrayBlockingQueue<byte[]> blockingQueue;

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
//        int numCodecs = MediaCodecList.getCodecCount();
//        for (int i = 0; i < numCodecs; i++) {
//            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
//            Log.d(TAG, "codec: " + codecInfo.getName() + " enc: " + codecInfo.isEncoder());
//
//            String[] types = codecInfo.getSupportedTypes();
//            for (int j = 0; j < types.length; j++) {
//                Log.d(TAG, "type: " + types[j]);
//            }
//        }
//        return START_NOT_STICKY;
//
        blockingQueue = new ArrayBlockingQueue<>(2);

        HandlerThread cameraThread = new HandlerThread("cameraThread");
        cameraThread.start();
        codecHandler = new Handler(cameraThread.getLooper());

        HandlerThread networkThread = new HandlerThread("cameraNetworkThread");
        networkThread.start();
        networkHandler = new Handler(networkThread.getLooper());

        openCamera();

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

    private void openCamera() {

        if (getApplicationContext().checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED) {
            Log.e(TAG, "No camera permission!");
            stopSelf();
            return;
        }

        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try {

            String[] cameraIdList = cameraManager.getCameraIdList();
            for (String id : cameraIdList) {

                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                int lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {

//                    configurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
//                    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

                    cameraManager.openCamera(id, cameraStateCallback, null);
                    break;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }

    private CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice camera) {

            Log.d(TAG, "cameraStateCallback.onOpened");

            cameraDevice = camera;
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

        final MediaFormat format = MediaFormat.createVideoFormat(MainActivity.mimeType, MainActivity.videoWidth, MainActivity.videoHeight);

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.1f);

        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String codecName = codecList.findEncoderForFormat(format);
        Log.d(TAG, "codecName: " + codecName);

        videoCodec = MediaCodec.createByCodecName(codecName);
//        videoCodec = MediaCodec.createEncoderByType(MainActivity.mimeType);
//        MediaCodecInfo info = videoCodec.getCodecInfo();
//        MediaCodecInfo.CodecCapabilities capabilities = info.getCapabilitiesForType(MainActivity.mimeType);
//        MediaCodecInfo.VideoCapabilities videoCapabilities = capabilities.getVideoCapabilities();
//        int[] formats = configurationMap.getOutputFormats();
//        for (int i : formats) {
//            Log.d(TAG, "format: " + i);
//            Size[] sizes = configurationMap.getOutputSizes(i);
//            for (Size s : sizes) {
//                Log.d(TAG, "size: " + s.toString());
//                Log.d(TAG, String.valueOf(videoCapabilities.areSizeAndRateSupported(s.getWidth(), s.getHeight(), 30)));
//            }
//        }
//        Log.d(TAG, "custom: " + videoCapabilities.areSizeAndRateSupported(960, 1080, 60));
//        Log.d(TAG, videoCodec.getCodecInfo().toString());

        videoCodec.setCallback(videoCodecCallback, codecHandler);
        videoCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        videoInputSurface = videoCodec.createInputSurface();

        codecHandler.post(new Runnable() {
            @Override
            public void run() {

                videoCodec.start();
            }
        });
    }

    private void startCamera() {

        if (cameraDevice == null) {
            Log.e(TAG, "cameraDevice is null");
            stopSelf();
            return;
        }

        try {
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
//            captureRequestBuilder.set(CaptureRequest.)
            captureRequestBuilder.addTarget(videoInputSurface);
            cameraDevice.createCaptureSession(Arrays.asList(videoInputSurface), captureSessionStateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void openSocket() {

        if (MainActivity.serverAddress == null) {
            Log.e(TAG, "cameraService openSockets: null serverAddress");
            return;
        }
        Runnable socketRunnable = new Runnable() {

            @Override
            public void run() {
                try {
                    socket = new Socket();
                    socket.bind(null);
                    socket.connect(new InetSocketAddress(MainActivity.serverAddress, MainActivity.port));
                    Log.d(TAG, "Connected!");
                    DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream());
                    while (true) {
                        try {
                            byte[] bytes = blockingQueue.take();
                            Log.d(TAG, "outputStream bytes: " + bytes.length);
                            outputStream.write(bytes);
                            outputStream.flush();
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

    private CameraCaptureSession.StateCallback captureSessionStateCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(CameraCaptureSession session) {

            cameraCaptureSession = session;

            if (onStartCameraCallback != null) {
                onStartCameraCallback.onStartCamera();
            }
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

            byte[] bufferBytes = new byte[outputBuffer.remaining()];
            outputBuffer.get(bufferBytes);
            codec.releaseOutputBuffer(index, false);

            blockingQueue.offer(bufferBytes);
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

    void stopCamera() {

        if (videoCodec != null) {
          videoCodec.signalEndOfInputStream();
        }

        try {
            cameraCaptureSession.abortCaptures();
            cameraCaptureSession.close();
        } catch (CameraAccessException | IllegalStateException e) {
            //e.printStackTrace();
            cameraStopped();
        }
    }

    private void cameraStopped() {

        Log.d(TAG, "cameraStopped");
        releaseResources();

        if (onStopCameraCallback != null) {
            onStopCameraCallback.onStopCamera();
        }

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
}
