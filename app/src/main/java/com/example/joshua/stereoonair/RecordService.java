package com.example.joshua.stereoonair;

import android.Manifest;
import android.app.Activity;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.location.Location;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.text.format.DateFormat;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import static java.lang.Math.PI;
import static java.lang.Math.max;
import static java.lang.Math.sin;

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
    private static VideoQuality videoQuality;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession cameraCaptureSession;
    private MediaCodec videoCodec;
    private Surface videoInputSurface;
    private MediaCodec audioCodec;
    private MediaFormat videoFormat;
    private MediaFormat audioFormat;
    private Integer sensorOrientation = 0;

    private AudioRecord audioRecord;
    private int audioSampleRate = 48000;
    private int audioChunkBytes = audioSampleRate * 2 / 24;

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

        //Log.d(TAG, "onStartCommand");

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
                   //Log.e(TAG, "No camera permission!");
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
        audioFormat = null;

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
           //Log.e(TAG, "No suitable video resolution found.");
            stopSelf();
            return;
        }

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000);
        format.setString(MediaFormat.KEY_FRAME_RATE, null);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String codecName = codecList.findEncoderForFormat(format);
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

    @RequiresApi(Build.VERSION_CODES.O)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channelId = createNotificationChannel();
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }

        notification = builder
                .setContentTitle(getText(R.string.notification_title))
//                .setContentText(getText(R.string.notification_message))
                .setSmallIcon(R.drawable.recording_notification_icon)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(ONGOING_NOTIFICATION_ID, notification);
    }

    RecordState getRecordState() {
        return recordState;
    }

    void discardRecording() {

        saveRecording = false;
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

        if (audioRecord != null
                && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {

            audioRecord.stop();
        }

        SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        sensorManager.unregisterListener(sensorEventListener);

        if (saveLocation
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && MainActivity.hasLocationApi()) {

            location = LocationServices.FusedLocationApi.getLastLocation(MainActivity.googleApiClient);
        } else {
            location = null;
        }

        if (saveRecording) {
            boolean saved = dumpBuffersToFile();
        }

        if (onStopRecordCallback != null) {
            onStopRecordCallback.onStopRecord();
        }

        recordState = RecordState.STOPPED;
        stopForeground(true);
        stopSelf();
    }

    private boolean dumpBuffersToFile() {

        String formattedDate = (String) DateFormat.format("yyyy-MM-dd HH:mm:ss", Calendar.getInstance());

        internalFile = new File(getApplicationContext().getFilesDir(), formattedDate + ".mp4");
        String filePath = internalFile.getAbsolutePath();

        File thumbnailFile = new File(getApplicationContext().getFilesDir(), formattedDate + "_thumbnail.jpg");

        /*try {
            internalFile.delete();
            internalFile.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }*/

        MediaMuxer mediaMuxer = null;
        try {
            mediaMuxer = new MediaMuxer(filePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException e) {
            e.printStackTrace();
            stopSelf();
            return false;
        }

        if (videoFormat == null) {
           //Log.d(TAG, "null videoFormat");
            stopSelf();
            return false;
        }

        int videoTrackIdx = mediaMuxer.addTrack(videoFormat);

        int audioTrackIdx = -1;
        if (recordAudio && audioFormat != null) {
            audioTrackIdx = mediaMuxer.addTrack(audioFormat);
        }

        if (location != null) {
            mediaMuxer.setLocation((float) location.getLatitude(), (float) location.getLongitude());
//                Log.d(TAG, "latitude: " + String.valueOf(location.getLatitude()));
//                Log.d(TAG, "longitude: " + String.valueOf(location.getLongitude()));
        }

        mediaMuxer.setOrientationHint(sensorOrientation);

        mediaMuxer.start();

        long latestPt = 0;
        for (BufferDataInfoPair dataInfoPair : videoBufferList) {

            long bufferId = dataInfoPair.getDataId();
            MediaCodec.BufferInfo info = dataInfoPair.getBufferInfo();
            if (info.presentationTimeUs > latestPt) {
                latestPt = info.presentationTimeUs;
            } else {
                continue;
            }

            Cursor cursor = videoDb.query(EncodedVideoContract.Schema.TABLE_NAME,
                    null,
                    EncodedVideoContract.Schema._ID + " = " + String.valueOf(bufferId),
                    null, null, null,
                    EncodedVideoContract.Schema._ID + " ASC");

            if (! cursor.moveToFirst()) {
                continue;
            }

            ByteBuffer buffer = ByteBuffer.wrap(cursor.getBlob(
                    cursor.getColumnIndex(EncodedVideoContract.Schema.VIDEO_DATA_COLUMN_NAME)
            ));

            cursor.close();

            mediaMuxer.writeSampleData(videoTrackIdx, buffer, info);
        }

        if (recordAudio && audioTrackIdx != -1) {

            latestPt = 0;
            for (BufferDataInfoPair dataInfoPair : audioBufferList) {

                long bufferId = dataInfoPair.getDataId();
                MediaCodec.BufferInfo info = dataInfoPair.getBufferInfo();
                if (info.presentationTimeUs > latestPt) {
                    latestPt = info.presentationTimeUs;
                } else {
                    continue;
                }

                Cursor cursor = audioDb.query(EncodedAudioContract.Schema.TABLE_NAME,
                        null,
                        EncodedAudioContract.Schema._ID + " = " + String.valueOf(bufferId),
                        null, null, null,
                        EncodedAudioContract.Schema._ID + " ASC");

                if (!cursor.moveToFirst()) {
                    continue;
                }

                ByteBuffer buffer = ByteBuffer.wrap(cursor.getBlob(
                        cursor.getColumnIndex(EncodedAudioContract.Schema.AUDIO_DATA_COLUMN_NAME)
                ));

                cursor.close();

                mediaMuxer.writeSampleData(audioTrackIdx, buffer, info);
            }
        }

        mediaMuxer.stop();
        mediaMuxer.release();

        /*Bitmap thumbnail = ThumbnailUtils.createVideoThumbnail(filePath, MediaStore.Images.Thumbnails.MICRO_KIND);
        try {
            OutputStream thumbnailOutputStream = new FileOutputStream(thumbnailFile);
            thumbnail.compress(Bitmap.CompressFormat.JPEG, 75, thumbnailOutputStream);
            thumbnailOutputStream.flush();
            thumbnailOutputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }*/

        //SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        if (backupToDrive && MainActivity.hasDriveApi()) {

                /*String folderId = sharedPreferences.getString(KEY_DRIVE_FOLDER_ID, null);
                DriveId driveId = null;
                if (folderId != null) {
                    driveId = DriveId.decodeFromString(folderId);
                }
                if (driveId != null) {
                    DriveFolder driveFolder = driveId.asDriveFolder();
                }

                MetadataChangeSet changeSet = new MetadataChangeSet.Builder()
                        .setTitle("Flight Recorder").build();

                Drive.DriveApi.getRootFolder(MainActivity.googleApiClient).createFolder(
                        MainActivity.googleApiClient, changeSet).setResultCallback(folderCreatedCallback);*/

            Drive.DriveApi.newDriveContents(MainActivity.googleApiClient).setResultCallback(driveContentsResultCallback);
        }

        return true;
    }

    private ResultCallback<DriveApi.DriveContentsResult> driveContentsResultCallback = new ResultCallback<DriveApi.DriveContentsResult>() {

        @Override
        public void onResult(@NonNull DriveApi.DriveContentsResult driveContentsResult) {

//            Log.d(TAG, "driveContentsCallback.onResult");

            DriveContents driveContents = driveContentsResult.getDriveContents();

            try {
                FileInputStream fileInputStream = new FileInputStream(internalFile);
                OutputStream outputStream = driveContents.getOutputStream();

                byte[] buffer = new byte[1024 * 10];
                int len;
                while ((len = fileInputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, len);
                }

                fileInputStream.close();
                outputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            MetadataChangeSet changeSet = new MetadataChangeSet.Builder()
                    .setTitle("Flight Recording " + internalFile.getName())
                    .setMimeType("video/mp4").build();

            Drive.DriveApi.getRootFolder(MainActivity.googleApiClient).createFile(MainActivity.googleApiClient, changeSet, driveContents);
        }
    };

    private ResultCallback<DriveFolder.DriveFolderResult> folderCreatedCallback = new ResultCallback<DriveFolder.DriveFolderResult>() {
        @Override
        public void onResult(@NonNull DriveFolder.DriveFolderResult driveFolderResult) {
            Status status = driveFolderResult.getStatus();
            if (status.isSuccess()) {
                driveFolderResult.getDriveFolder().getDriveId();
            }
        }
    };

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

        if (audioCodec != null) {
            audioCodec.release();
        }
        if (audioRecord != null) {
            audioRecord.release();
        }
    }

    public static void setRecordDuration(int recordDuration) {
       //Log.d(TAG, "setRecordDuration: " + String.valueOf(recordDuration));
        RecordService.recordDuration = recordDuration * 1_000_000;
    }

    public static void setVideoQuality(String pref, String q1080p, String q720p) {
       //Log.d(TAG, "setVideoQuality: " + pref);
        if (pref.equals(q1080p)) {
            RecordService.videoQuality = VideoQuality.HIGH_1080P;
        } else if (pref.equals(q720p)) {
            RecordService.videoQuality = VideoQuality.MED_720P;
        }
    }

    public static void setCamera(String pref, String back, String front) {

        if (pref.equals(back)) {
            RecordService.camera = Camera.BACK;
        } else if (pref.equals(front)) {
            RecordService.camera = Camera.FRONT;
        }
    }

    public static void setSaveOnTipover(boolean saveOnTipover) {
       //Log.d(TAG, "setSaveOnTipover: " + String.valueOf(saveOnTipover));
        RecordService.saveOnTipover = saveOnTipover;
    }

    public static void setTipoverThreshold(String pref, String low, String medium, String high) {
       //Log.d(TAG, "setTipoverThreshold: " + pref);
        if (pref.equals(low)) {
//            RecordService.tipoverThreshold = TipoverThreshold.LOW;
            gThreshold = G_THRESHOLD_LOW;
        } else if (pref.equals(medium)) {
//            RecordService.tipoverThreshold = TipoverThreshold.MEDIUM;
            gThreshold = G_THRESHOLD_MEDIUM;
        } else if (pref.equals(high)) {
//            RecordService.tipoverThreshold = TipoverThreshold.HIGH;
            gThreshold = G_THRESHOLD_HIGH;
        }
    }

    public static void setTipoverTimeout(Long tipoverTimeout) {
        RecordService.tipoverTimeout = tipoverTimeout * 1_000_000_000;
        //Log.d(TAG, "setTipoverTimeout: " + String.valueOf(RecordService.tipoverTimeout));
    }

    public static void setSaveLocation(boolean saveLocation) {
       //Log.d(TAG, "setSaveLocation: " + String.valueOf(saveLocation));
        RecordService.saveLocation = saveLocation;
    }

    public static void setBackupToDrive(boolean backupToDrive) {
       //Log.d(TAG, "setBackupToDrive: " + String.valueOf(backupToDrive));
        RecordService.backupToDrive = backupToDrive;
    }
}