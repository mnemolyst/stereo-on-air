package com.example.joshua.stereoonair;

import android.Manifest;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * An {@link IntentService} subclass for handling asynchronous task requests in
 * a service on a separate handler thread.
 */
public class RecordService extends Service {

    private final static String TAG = "CameraService";
    private final static int ONGOING_NOTIFICATION_ID = 1;

    enum RecordState {
        STOPPING, STOPPED, STARTING, STARTED
    }
    enum VideoQuality {
        HIGH_1080P, MED_720P
    }
    private RecordState recordState = RecordState.STOPPED;
    private static VideoQuality videoQuality = VideoQuality.HIGH_1080P;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession cameraCaptureSession;
    private MediaCodec videoCodec;
    private Surface videoInputSurface;
    private MediaFormat videoFormat;
    private Integer sensorOrientation = 0;

    private OnStartRecordCallback onStartRecordCallback;
    private OnStopRecordCallback onStopRecordCallback;

    static abstract class OnStartRecordCallback {
        abstract void onStartRecord();
    }

    static abstract class OnStopRecordCallback {
        abstract void onStopRecord();
    }

    public void registerOnStartRecordCallback(OnStartRecordCallback callback) {
        onStartRecordCallback = callback;
    }

    public void registerOnStopRecordCallback(OnStopRecordCallback callback) {
        onStopRecordCallback = callback;
    }

    class RecordServiceBinder extends Binder {

        RecordService getService() {

            return RecordService.this;
        }
    }

    @Override
    public void onCreate() {

        //Log.d(TAG, "onCreate");

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.d(TAG, "onStartCommand");

        recordState = RecordState.STOPPED;
        startRecording();

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {

        //Log.d(TAG, "onBind");
        return new RecordServiceBinder();
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

    private void startRecording() {

       //Log.d(TAG, "startRecording");

        new Thread(new Runnable() {

            public void run() {

                if (getApplicationContext().checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED) {
                    Log.e(TAG, "No camera permission!");
                    stopSelf();
                    return;
                }

//                recordAudio = ActivityCompat.checkSelfPermission(RecordService.this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

                CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

                try {

                    String[] cameraIdList = cameraManager.getCameraIdList();
                    for (String id : cameraIdList) {

                        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                        int lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                        if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {

                            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

                            cameraManager.openCamera(id, cameraStateCallback, null);
                            break;
                        }
                    }
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }
        }).run();
    }

    private CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice camera) {

           //Log.d(TAG, "cameraStateCallback.onOpened");

            cameraDevice = camera;
            recordState = RecordState.STARTING;
            prepareForRecording();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {

           //Log.d(TAG, "cameraStateCallback.onDisconnected");
            cameraStopped();
        }

        @Override
        public void onError(CameraDevice camera, int error) {

           //Log.e(TAG, "cameraStateCallback.onError");
            stopSelf();
        }
    };

    private void prepareForRecording() {

        videoFormat = null;

        try {
            startVideoCodec();
            startCamera();
        } catch (IOException e) {
            e.printStackTrace();
            stopSelf();
            return;
        }

        notifyForeground();
    }

    private void startVideoCodec() throws IOException {

        MediaFormat format = null;
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
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

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
           //Log.e(TAG, "cameraDevice is null");
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

    private CameraCaptureSession.StateCallback captureSessionStateCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(CameraCaptureSession session) {

            cameraCaptureSession = session;

            /*HandlerThread thread = new HandlerThread("CameraPreview");
            thread.start();
            Handler backgroundHandler = new Handler(thread.getLooper());*/

            try {
                session.setRepeatingRequest(captureRequestBuilder.build(), null, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
                stopSelf();
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
           //Log.e(TAG, "captureSessionStateCallback.onConfigureFailed");
            stopSelf();
        }

        @Override
        public void onClosed(CameraCaptureSession session) {

           //Log.d(TAG, "captureSessionStateCallback.onClosed");
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

            if (recordState == RecordState.STARTING) {

                recordState = RecordState.STARTED;

                if (onStartRecordCallback != null) {
                    onStartRecordCallback.onStartRecord();
                }
            }

            if (recordState != RecordState.STARTED) {
                return;
            }

            ByteBuffer outputBuffer = null;
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
           //Log.e(TAG, "MediaCodec.Callback.onError", e);
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

    RecordState getRecordState() {
        return recordState;
    }

    void discardRecording() {

        stopRecording();
    }

    void stopRecording() {

       //Log.d(TAG, "stopRecording");

        if (recordState.equals(RecordState.STARTED)) {

            recordState = RecordState.STOPPING;
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

        if (recordState.equals(RecordState.STARTED)) {

            recordState = RecordState.STOPPING;
//            videoCodec.signalEndOfInputStream();
        }

        if (onStopRecordCallback != null) {
            onStopRecordCallback.onStopRecord();
        }

        recordState = RecordState.STOPPED;
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
            RecordService.videoQuality = VideoQuality.HIGH_1080P;
        } else if (pref.equals(q720p)) {
            RecordService.videoQuality = VideoQuality.MED_720P;
        }
    }
}
