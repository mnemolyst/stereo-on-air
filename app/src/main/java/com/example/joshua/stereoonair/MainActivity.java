package com.example.joshua.stereoonair;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Point;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.util.Log;
import android.widget.TextView;

import java.nio.ByteBuffer;

public class MainActivity extends Activity {

    private final static String TAG = "MainActivity";
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private IntentFilter intentFilter;
    private SurfaceView surfaceView;
    private CameraService cameraService = null;
    private ReceiverService receiverService = null;

    enum Role {
        CAMERA, RECEIVER
    }
    private Role myRole;
    public static String serverAddress;
    public final static int port = 18353; // arbitrary port
//    public static int videoWidth = 1920;
//    public static int videoHeight = 1080;
    public static int videoWidth = 160;
    public static int videoHeight = 120;
    public static Point screenSize = new Point();

    private class BroadcastReceiver extends android.content.BroadcastReceiver {

        private WifiP2pManager manager;
        private WifiP2pManager.Channel channel;
        private Activity activity;

        public BroadcastReceiver(
                WifiP2pManager manager,
                WifiP2pManager.Channel channel,
                Activity activity) {

            super();
            this.manager = manager;
            this.channel = channel;
            this.activity = activity;
        }

        @Override
        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();

            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {

                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    Log.d(TAG, "wifi p2p state changed: enabled");
                } else {
                    Log.d(TAG, "wifi p2p state changed: not enabled");
                }
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                Log.d(TAG, "wifi p2p connection changed");

                NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);

                if (networkInfo.isConnected()) {
                    Log.d(TAG, "network isConnected");

                    manager.requestConnectionInfo(channel, connectionInfoListener);
                }

            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                Log.d(TAG, "wifi p2p device changed");
            }
        }
    }

    private WifiP2pManager.ConnectionInfoListener connectionInfoListener = new WifiP2pManager.ConnectionInfoListener() {

        @Override
        public void onConnectionInfoAvailable(WifiP2pInfo info) {
            Log.d(TAG, "onConnectionInfoAvailable: " + info.toString());

            if (info.groupFormed) {
                MainActivity.serverAddress = info.groupOwnerAddress.getHostAddress();
            }

//            if (info.groupFormed && info.isGroupOwner) {
//                myRole = Role.RECEIVER;
//                                startServer();
//            } else if (info.groupFormed) {
//                myRole = Role.CAMERA;
//                                startCamera();
//            }

            TextView connectionTextView = findViewById(R.id.connection_textview);
            connectionTextView.setText(info.toString());
        }
    };

    private void startServer() {

        Intent intent = new Intent(this, ReceiverService.class);
        startService(intent);
    }

    private void stopServer() {

        receiverService.stop();
    }

    private void startCamera() {

        if (cameraService.getState().equals(CameraService.State.STOPPED)) {

            Intent intent = new Intent(this, CameraService.class);
            startService(intent);
        }
    }

    private void stopCamera() {
        cameraService.stopCamera();
    }

    private ServiceConnection cameraConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {

            Log.d(TAG, "onServiceConnected: " + name.toString());

            cameraService = ((CameraService.cameraServiceBinder) binder).getService();
            cameraService.registerOnStartCameraCallback(onStartCameraCallback);
            cameraService.registerOnStopCameraCallback(onStopCameraCallback);
            cameraService.registerOnFrameReceivedCallback(onFrameReceivedCallback);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {

            //Log.d(TAG, "onServiceDisconnected");
        }
    };

    private ServiceConnection receiverConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {

            SurfaceView surfaceView = findViewById(R.id.surface_view);
            Surface surface = surfaceView.getHolder().getSurface();
            receiverService = ((ReceiverService.receiverServiceBinder) binder).getService();
            receiverService.setVideoOutputSurface(surface);
            receiverService.registerOnFrameReceivedCallback(onFrameReceivedCallback);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {

        }
    };

    private CameraService.OnStartCameraCallback onStartCameraCallback = new CameraService.OnStartCameraCallback() {

        @Override
        void onStartCamera() {
            Log.d(TAG, "onStartCameraCallback onStartCamera");
        }
    };

    private CameraService.OnStopCameraCallback onStopCameraCallback = new CameraService.OnStopCameraCallback() {

        @Override
        void onStopCamera() {
            Log.d(TAG, "onStopCameraCallback onStopCamera");
        }
    };

    private ReceiverService.OnFrameReceivedCallback onFrameReceivedCallback = new ReceiverService.OnFrameReceivedCallback() {

        @Override
        void onFrameReceived(byte[] bytes) {
            Log.d(TAG, "onFrameReceivedCallback.onFrameReceived");
//            ByteBuffer redBuffer = ByteBuffer.allocate(length / 3);
//            ByteBuffer greenBuffer = ByteBuffer.allocate(length / 3);
//            ByteBuffer blueBuffer = ByteBuffer.allocate(length / 3);
            Canvas canvas = surfaceView.getHolder().lockHardwareCanvas();
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            canvas.drawBitmap(bitmap, 0, 0, null);
            surfaceView.getHolder().unlockCanvasAndPost(canvas);
        }
    };

    private void checkConnection() {

        manager.requestConnectionInfo(channel, connectionInfoListener);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);

        receiver = new BroadcastReceiver(manager, channel, MainActivity.this);

        getWindowManager().getDefaultDisplay().getRealSize(screenSize);

        surfaceView = findViewById(R.id.surface_view);

        final Button cameraButton = findViewById(R.id.camera_button);
        cameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startCamera();
            }
        });

        final Button stopCameraButton = findViewById(R.id.stop_camera_button);
        stopCameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopCamera();
            }
        });

        final Button receiverButton = findViewById(R.id.receiver_button);
        receiverButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startServer();
            }
        });

        final Button stopReceiverButton = findViewById(R.id.stop_receiver_button);
        stopReceiverButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopServer();
            }
        });

        Intent cameraIntent = new Intent(this, CameraService.class);
        bindService(cameraIntent, cameraConnection, Context.BIND_AUTO_CREATE);

        Intent receiverIntent = new Intent(this, ReceiverService.class);
        bindService(receiverIntent, receiverConnection, Context.BIND_AUTO_CREATE);

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
    }

    @Override
    protected void onResume() {

        super.onResume();
        registerReceiver(receiver, intentFilter);
//        discoverPeers();
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        Log.d(TAG, size.toString());
        checkConnection();
    }

    @Override
    protected void onPause() {

        super.onPause();
        unregisterReceiver(receiver);
    }

    @Override
    public void onDestroy() {

        //Log.d(TAG, "onDestroy");
        super.onDestroy();
        unbindService(cameraConnection);
    }
}
