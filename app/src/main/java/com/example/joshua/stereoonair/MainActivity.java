package com.example.joshua.stereoonair;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.util.Log;
import android.widget.TextView;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class MainActivity extends Activity {

    private final static String TAG = "MainActivity";
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private IntentFilter intentFilter;
    private CameraService cameraService = null;
    private ReceiverService receiverService = null;

    enum Role {
        CAMERA, RECEIVER
    }
    private Role myRole;
    private ServerSocket serverSocket;
    private Socket socket;
    public final static int port = 8353;

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

                    manager.requestConnectionInfo(channel, new WifiP2pManager.ConnectionInfoListener() {
                        @Override
                        public void onConnectionInfoAvailable(WifiP2pInfo info) {
                            Log.d(TAG, "onConnectionInfoAvailable: " + info.toString());

                            String receiverAddress = info.groupOwnerAddress.getHostAddress();

                            if (info.groupFormed && info.isGroupOwner) {
                                myRole = Role.RECEIVER;
                                Log.d(TAG, "I am the group owner. Owner: " + receiverAddress);

                                startServer();
                            } else if (info.groupFormed) {
                                myRole = Role.CAMERA;
                                Log.d(TAG, "I am not the group owner. Owner: " + receiverAddress);

                                setServerAddress(receiverAddress);
//                                startClient();
                                startCamera();
                            }

                        }
                    });
                }

            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                Log.d(TAG, "wifi p2p device changed");
            }
        }
    }

    private void startServer() {

        Intent intent = new Intent(this, ReceiverService.class);
        startService(intent);
    }

    private void setServerAddress(String address) {
        cameraService.setReceiverAddress(address);
    }

    private void startCamera() {

        if (cameraService.getState().equals(CameraService.State.STOPPED)) {

            Intent intent = new Intent(this, CameraService.class);
            startService(intent);
        }
    }

    private ServiceConnection cameraConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {

            Log.d(TAG, "onServiceConnected: " + name.toString());

            cameraService = ((CameraService.cameraServiceBinder) binder).getService();
            cameraService.registerOnStartCameraCallback(onStartCameraCallback);
            cameraService.registerOnStopCameraCallback(onStopCameraCallback);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {

            //Log.d(TAG, "onServiceDisconnected");
        }
    };

    private ServiceConnection receiverConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {

            receiverService = ((ReceiverService.receiverServiceBinder) binder).getService();
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
        void onFrameReceived() {
            Log.d(TAG, "onFrameReceivedCallback.onFrameReceived");
        }
    };

    private void stopCamera() {
        cameraService.stopCamera();
    }

    private void checkConnection() {

        manager.requestConnectionInfo(channel, new WifiP2pManager.ConnectionInfoListener() {
            @Override
            public void onConnectionInfoAvailable(WifiP2pInfo info) {
                Log.d(TAG, "onConnectionInfoAvailable: " + info.toString());
                TextView connectionTextView = findViewById(R.id.connection_textview);
                connectionTextView.setText(info.toString());
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);

        receiver = new BroadcastReceiver(manager, channel, MainActivity.this);

        final Button cameraButton = findViewById(R.id.camera_button);
        cameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "cameraButton onClick");
                startCamera();
            }
        });

        final Button stopCameraButton = findViewById(R.id.stop_camera_button);
        stopCameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "stopCameraButton onClick");
                stopCamera();
            }
        });

        final Button receiverButton = findViewById(R.id.receiver_button);
        receiverButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
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
        Log.d(TAG, "mainActivity thread id: " + String.valueOf(Thread.currentThread().getId()));
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
