package com.intercom.video.twoway.Services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.intercom.video.twoway.Controllers.ProfileController;
import com.intercom.video.twoway.MainActivity;
import com.intercom.video.twoway.Network.NetworkConstants;
import com.intercom.video.twoway.Network.NetworkDiscovery;
import com.intercom.video.twoway.Network.Tcp;
import com.intercom.video.twoway.R;
import com.intercom.video.twoway.Utilities.ControlConstants;
import com.intercom.video.twoway.Utilities.Utilities;

/**
 * @Author Cole Risch, Sean Luther, Eric Van Gelder, Charles Toll, Alex Gusan, Robert V.
 * This class creates a Foreground service, the least likely type of service to be killed
 * by the android system.  Service starts on boot.  Network Discovery runs in this service so that the
 * device is discoverable at all times.
 */
public class ListenerService extends Service {
    private ProfileController profileController;
    private final static int SERVICE_ID = 12345; // we don't use this but the service wants to be assigned an
    private boolean listeningForConnections = false;
    private final Tcp serviceTcpEngine = new Tcp();
    // id when it is created
    private final IBinder mBinder = new LocalBinder();// Binder given to clients
    private NetworkDiscovery mNetworkDiscovery; // Network Discovery Object

    /**
     * Lifecycle method that is called when the service is created. Note that the service icon
     * and style are set in here.
     */
    @Override
    public void onCreate() {

        showNotification();
        super.onCreate();
    }

    /**
     * Lifecycle method that is called when service is to be started
     *
     * @param intent
     * @param flags
     * @param startId
     * @return
     */
    @Override
	public int onStartCommand(Intent intent, int flags, int startId) {
        startListeningForConnections();

        Utilities u = new Utilities(this); //TODO: this should be passed the Utilities object from MainActivity
        setNetworkDiscovery(new NetworkDiscovery(u));
        getNetworkDiscovery().setupNetworkDiscovery();

        // If we get killed, after returning from here, restart the service
        return START_STICKY;
    }

    @Override
    public void onDestroy()
    {
        getNetworkDiscovery().stopNetworkDiscovery();
        stopListeningForConnections();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * start listening for connections from other devices and decide what
     * to do based on what command they send us.
     * This is where we communicate back with the main activity via an intent
     * and start the main activity if it is dead and start a video connection etc
     */
    public void startListeningForConnections()
    {
        Thread listenForConnectionThread;
        if(!listeningForConnections) {
            listeningForConnections = true;
            listenForConnectionThread = new Thread() {
                @Override
                public void run() {
                    while (listeningForConnections) {
                        try {
                            int connectionStage = serviceTcpEngine.listenForConnection();

                            // extract just the ip address from ip address and port combo string
                            // this would be cooler if done with regular expressions
                            String remoteIpAddress = serviceTcpEngine.lastRemoteIpAddress;
                            String newRemoteAddress = remoteIpAddress.substring(1, remoteIpAddress.indexOf(":"));

                            // tells us to connect to the remote server and start feeding it our video
                            // then start our own remote server and tel the other device to connect
                            if (connectionStage == 1) {
                                sendCommandToActivity(ControlConstants.INTENT_COMMAND_START_STREAMING_FIRST,
                                        newRemoteAddress);
                            }

                            // tells us to connect to the remote server, this happens second after we have
                            // already started our own server and told them to connect
                            // the difference between this and INTENT_COMMAND_START_STREAMING_FIRST is that
                            // we dont start a new server and tell the other to connect because we already
                            // did that
                            if (connectionStage == 2) {
                                sendCommandToActivity(ControlConstants.INTENT_COMMAND_START_STREAMING_SECOND,
                                        newRemoteAddress);
                            }

                            if (connectionStage == NetworkConstants.PROFILE) {
                                // profileController can be null if service started on boot
                                try {
                                    profileController.sendDeviceInfoByIp(newRemoteAddress);
                                } catch (Exception e) {
                                    Log.e("TAG", "run: ", e);
                                }
                            }
                        } catch (Exception e) {
                            Log.e("TAG", "run: ", e);
                        }
                        // now just close the connection so we can listen for more
                        serviceTcpEngine.closeConnection();
                    }
                }
            };
            listenForConnectionThread.start();
        }
    }

    public NetworkDiscovery getNetworkDiscovery() {
        return mNetworkDiscovery;
    }

    public void setNetworkDiscovery(NetworkDiscovery networkDiscovery) {
        mNetworkDiscovery = networkDiscovery;
    }

    private void showNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startNewForeground();
        } else {
            startOldForeground();

        }


    }

    private void startNewForeground() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            String NOTIFICATION_CHANNEL_ID = "om.personal.audiostream.service";
            String channelName = "My Background Service";
            NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName,
                    NotificationManager.IMPORTANCE_NONE);
            chan.setLightColor(Color.BLUE);
            chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            assert manager != null;
            manager.createNotificationChannel(chan);

            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this,
                    NOTIFICATION_CHANNEL_ID);
            Notification notification = notificationBuilder.setOngoing(true)
                    .setContentTitle("listener service")
                    .setContentText("listener service")
                    .setTicker("listener service")
                    .setSmallIcon(R.drawable.service_icon)
                    .setPriority(NotificationManager.IMPORTANCE_MIN)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis())
                    .build();
            startForeground(SERVICE_ID, notification);
        }
    }

    private void startOldForeground() {
        Resources res = this.getResources();
        Intent notificationIntent = new Intent(this, MainActivity.class);
        Notification.Builder builder = new Notification.Builder(this);
        PendingIntent contentIntent = PendingIntent.getActivity(this,
                12345, notificationIntent,
                PendingIntent.FLAG_CANCEL_CURRENT);
        builder.setContentIntent(contentIntent)
                .setSmallIcon(R.drawable.service_icon)
                .setLargeIcon(BitmapFactory.decodeResource(res
                        , R.drawable.service_icon))
                .setTicker("listener service")
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true)
                .setContentTitle("listener service")
                .setContentText("listener service");
        Notification n = builder.build();
        startForeground(SERVICE_ID, n);

    }

    /**
     * Clean up threads and tcp, called in onDestroy
     */
    public void stopListeningForConnections()
    {
        listeningForConnections=false;
        serviceTcpEngine.closeConnection();
    }

    /**
     * Class used for the client Binder. Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        public ListenerService getService() {
            // Return this instance of LocalService so clients can call public
			// methods
			return ListenerService.this;
		}
	}

    /**
     * send a command to the activity
     * This is the services primary means of communicating with the activity
     * this also starts the activity if it has been killed and brings it to the foreground
     * @param command the command string we are sending the activity
     * @param extra any extra data we need to send the activity, usually an ip address of a remote device
     */
    public void sendCommandToActivity(String command, String extra)
    {
        Intent startMainActivityIntent = new Intent(getBaseContext(), MainActivity.class);
        startMainActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startMainActivityIntent.putExtra("COMMAND", command);
        startMainActivityIntent.putExtra("EXTRA_DATA", extra);

        getApplication().startActivity(startMainActivityIntent);
    }

    public void setProfileController(ProfileController pc)
    {
        if(pc == null) {
            Log.d("ListenerService", "ProfileController was NULL in setProfileController");
        }
        else {
            this.profileController = pc;
        }
    }
}