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
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceView;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

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
    private SurfaceView surfaceView;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession cameraCaptureSession;
    private MediaCodec videoCodec;
    private int outputFormat = ImageFormat.YUV_420_888;
    private ImageReader imageReader;
    private Surface videoInputSurface;
    private MediaFormat videoFormat;
    private Integer sensorOrientation = 0;
    private StreamConfigurationMap configurationMap;
    private Socket socket;
    private String receiverAddress;
    private Handler cameraHandler;
    private Handler networkHandler;
    private Handler uiHandler;
    private ArrayBlockingQueue<byte[]> blockingQueue;

    private OnStartCameraCallback onStartCameraCallback;
    private OnStopCameraCallback onStopCameraCallback;
    private ReceiverService.OnFrameReceivedCallback onFrameReceivedCallback;

    static abstract class OnStartCameraCallback {
        abstract void onStartCamera();
    }

    static abstract class OnStopCameraCallback {
        abstract void onStopCamera();
    }

    public void registerOnFrameReceivedCallback(ReceiverService.OnFrameReceivedCallback callback) {
        onFrameReceivedCallback = callback;
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

        blockingQueue = new ArrayBlockingQueue<>(2);

        state = State.STOPPED;

        HandlerThread cameraThread = new HandlerThread("cameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        HandlerThread networkThread = new HandlerThread("cameraNetworkThread");
        networkThread.start();
        networkHandler = new Handler(networkThread.getLooper());

        uiHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message message) {
                byte[] bytes = (byte[]) message.obj;
                onFrameReceivedCallback.onFrameReceived(bytes);
            }
        };

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

                    configurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

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
        format = MediaFormat.createVideoFormat("video/x-vnd.on2.vp8", MainActivity.videoWidth, MainActivity.videoHeight);

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000);
        format.setString(MediaFormat.KEY_FRAME_RATE, null);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0);

        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String codecName = codecList.findEncoderForFormat(format);
        Log.d(TAG, "codecName: " + codecName);

        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);

        videoCodec = MediaCodec.createByCodecName(codecName);
        videoCodec.setCallback(videoCodecCallback, cameraHandler);
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

        int[] formats = configurationMap.getOutputFormats();
        Log.d(TAG, "formats:");
        for (int i = 0; i < formats.length; i++) {
            Log.d(TAG, String.valueOf(formats[i]));
        }
        // formats: 32, 256, 34, 35, 36, 37
        // raw_sensor, jpeg, private, yuv_420_888, raw_private, raw_10
        int smallestWidth = 99_999;
        int smallestHeight = 99_999;
//        if (Arrays.asList(formats).contains(outputFormat)) {
            Size[] sizes = configurationMap.getOutputSizes(outputFormat);
            for (int i = 0; i < sizes.length; i++) {
                Size size = sizes[i];
                if (size.getWidth() < smallestWidth && size.getHeight() < smallestHeight) {
                    smallestWidth = size.getWidth();
                    smallestHeight = size.getHeight();
                }
            }
            Log.d(TAG, "Outputting to size: " + smallestWidth + ", " + smallestHeight);
//        } else {
//            Log.e(TAG, "outputFormat not supported");
//            stopSelf();
//            return;
//        }
//        imageReader = ImageReader.newInstance(smallestWidth, smallestHeight, outputFormat, 2);
//        imageReader.setOnImageAvailableListener(onImageAvailableListener, cameraHandler);
//        videoInputSurface = imageReader.getSurface();

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

        if (MainActivity.serverAddress == null) {
            Log.e(TAG, "cameraService openSocket: null serverAddress");
            return;
        }
        Runnable socketRunnable = new Runnable() {

            @Override
            public void run() {
                try {
                    socket = new Socket();
                    socket.bind(null);
                    socket.connect(new InetSocketAddress(MainActivity.serverAddress, port));
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

    private ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
//                Log.d(TAG, "onImageAvailable");

            Image image = reader.acquireLatestImage();
            Image.Plane[] planes = image.getPlanes();
//            ByteBuffer byteBuffer = planes[0].getBuffer();
//            byte[] bytes = new byte[byteBuffer.remaining()];
//            byteBuffer.get(bytes);
            ByteBuffer redBuffer = planes[0].getBuffer();
            ByteBuffer greenBuffer = planes[1].getBuffer();
            ByteBuffer blueBuffer = planes[2].getBuffer();
//            Log.d(TAG, "redBuffer length: " + redBuffer.capacity());
//            Log.d(TAG, "greenBuffer length: " + greenBuffer.capacity());
//            Log.d(TAG, "blueBuffer length: " + blueBuffer.capacity());
            ByteBuffer transmissionBuffer = ByteBuffer.allocateDirect(redBuffer.remaining() + greenBuffer.remaining() + blueBuffer.remaining());
            transmissionBuffer.put(redBuffer).put(greenBuffer).put(blueBuffer);
//            try {
//                blockingQueue.put(bytes);
//            } catch (InterruptedException exception) {
//                Log.e(TAG, exception.getMessage());
//            }
            image.close();
        }
    };

    private CameraCaptureSession.StateCallback captureSessionStateCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(CameraCaptureSession session) {

            cameraCaptureSession = session;

            if (state == State.STARTING) {

                state = State.STARTED;

                if (onStartCameraCallback != null) {
                    onStartCameraCallback.onStartCamera();
                }
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

            Log.d(TAG, "onOutputBufferAvailable");

            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
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

            byte[] bufferBytes = new byte[outputBuffer.remaining()];
            outputBuffer.get(bufferBytes);
            codec.releaseOutputBuffer(index, false);

//            try {
                blockingQueue.offer(bufferBytes);
//            } catch (InterruptedException exception) {
//                Log.e(TAG, exception.getMessage());
//            }

//            if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == MediaCodec.BUFFER_FLAG_KEY_FRAME) {
//            }

//            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
//               Log.d(TAG, "videoCodec EOS");
//            }
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
    }

    private void cameraStopped() {

        Log.d(TAG, "cameraStopped");
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
