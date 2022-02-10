package com.android.modemnotifier;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.text.TextUtils;
import android.util.Log;
import android.view.WindowManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.app.NotificationChannel;
import android.os.Message;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
/**
 * ModemNotifier application to notify
 * Modem assert/WCN assert/System info dump/Calibration check
 * @author SPRD
 */
public class ModemNotifierApplication extends Application {
    private static final String MTAG = "ModemNotifierApplication";
    private static final Boolean DEBUG = true;
    private Context mContext;
    private static final String MODEM_STAT_CHANGE =
            "com.android.modemassert.MODEM_STAT_CHANGE";
    //extra data key in intent for modem state broadcast
    private static final String MODEM_STAT = "modem_stat";
    private static final String MODEM_INFO= "modem_info";
    //values of extra data in intent.
    private static final String MODEM_ALIVE = "modem_alive";
    private static final String MODEM_ASSERT = "modem_assert";

    /** name of sockets*/
    private static final String MODEM_SOCKET_NAME = "modemd";
    private static final String SLOG_SOCKET_NAME = "slogmodem";
    private static final String WCN_MODEM_SOCKET_NAME = "wcnd";

    //notification id to cancel
    private static final int MODEM_NOTIFIER_ID = 1;
    private static final int WCND_NOTIFIER_ID = 2;
    private static final int MODEM_BLOCK_ID = 3;
    private static final int AGDSP_ASSERT_ID = 4;

    private static final int MSG_DUMP_START = 0;
    private static final int MSG_DUMP_END = 1;

    private static final boolean IS_DEBUGGABLE = SystemProperties.getInt(
            "ro.debuggable", 0) == 1;
    @Override
    public void onCreate() {
        super.onCreate();
        if(DEBUG) Log.d(MTAG, "onCreate()...");
        mContext = getApplicationContext();
        // create socket threads
        createSocketThreads();
    }

    private void createSocketThreads(){
        SocketThread modemStatThread = new SocketThread("modemStatThread", MODEM_SOCKET_NAME, ""){
            @Override
            protected void handleInputMsg(String info){
                handleModemStateMsg(info);
            }
        };
        modemStatThread.start();

        SocketThread slogThread = new SocketThread("slogThread", SLOG_SOCKET_NAME, initSsdaModeMsg()){
            @Override
            protected void handleInputMsg(String info){
                handleSlogMsg(info);
            }
        };
        slogThread.start();

        SocketThread wcnStatThread = new SocketThread("wcnStatThread", WCN_MODEM_SOCKET_NAME, ""){
            @Override
            protected void handleInputMsg(String info){
                handleWcnMsg(info);
            }
        };
        wcnStatThread.start();
    }

    /**
     * Handle ModemState Socket input message
     * @param info message from socket server
     */
    private void handleModemStateMsg(String info){
        if (info.contains("Modem Alive")) {
            sendModemStatBroadcast(MODEM_ALIVE,info);
            hideNotification(MODEM_NOTIFIER_ID);
            hideNotification(MODEM_BLOCK_ID);
        } else if (info.contains("Modem Assert")) {
            String value = SystemProperties.get("persist.vendor.sys.modemreset", "default");
            if(DEBUG) Log.d(MTAG, " modemreset ? : " + value);
            if(!value.equals("1")){
                showNotification(MODEM_NOTIFIER_ID,"modem assert",info);
            }
            sendModemStatBroadcast(MODEM_ASSERT, info);
        } else if (info.contains("Modem Blocked")) {
            showNotification(MODEM_BLOCK_ID,"modem block", info);
        } else if (info.contains("AGDSP Assert")) {
            showNotification(AGDSP_ASSERT_ID, "agdsp assert", info);
        } else {
            if(DEBUG) Log.d(MTAG, "do nothing with info :" + info);
        }
    }

    // For Slogmodem dump system info
    private  ProgressDialog mProgressDialog;

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_DUMP_START:
                if(DEBUG) Log.d(MTAG, "SLOG_DUMP_start!");
                mProgressDialog = new ProgressDialog(getApplicationContext());
                mProgressDialog.setTitle(getText(R.string.dumping_title));
                mProgressDialog.setIndeterminate(true);
                mProgressDialog.setCancelable(false);
                mProgressDialog.setMessage(getText(R.string.dumping_message));
                mProgressDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                mProgressDialog.show();
                break;
            case MSG_DUMP_END:
                if (mProgressDialog != null) {
                    if(DEBUG) Log.d(MTAG, " dismiss mProgressDialog in main thread!");
                    mProgressDialog.dismiss();
                }
                break;
            default:
               if(DEBUG) Log.d(MTAG, "can not be run ....");
               break;
            }
        }
    };

    /**
     * Handle Slog Socket input message
     * @param info message from socket server
     */
    private void handleSlogMsg(String info){
        if (info.contains("CP_DUMP_START")) {
            if (DEBUG) Log.d(MTAG, "show dialog!");
            mHandler.sendEmptyMessage(MSG_DUMP_START);
        } else if (info.contains("CP_DUMP_END")) {
            if (DEBUG) Log.d(MTAG, "SLOG_DUMP_END_ACTION");
            mHandler.sendEmptyMessage(MSG_DUMP_END);
        } else {
            if (DEBUG) Log.d(MTAG, "do nothing with info: " + info);
        }
    }

    /**
     * Handle WCN Socket input message
     * @param info message from socket server
     */
    private void handleWcnMsg(String info){
        if (info.contains("WCN-CP2-ALIVE") || info.contains("WCN-GE2-ALIVE")) {
            sendModemStatBroadcast(MODEM_ALIVE, info);
            hideNotification(WCND_NOTIFIER_ID);
            hideNotification(MODEM_BLOCK_ID);
        } else if(info.contains("WCN-CP2-EXCEPTION") || info.contains("WCN-GE2-EXCEPTION")) {
            showNotification(WCND_NOTIFIER_ID, "wcnd assert", info);
            sendModemStatBroadcast(MODEM_ASSERT, info);
        } else {
            if(DEBUG) Log.d(MTAG, "do nothing with info :" + info);
        }
    }

    private void showNotification(int notificationId, String title, String info) {
        if(DEBUG) Log.d(MTAG, "show Notification.");
        if(IS_DEBUGGABLE){
            NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            String channelId = "modem_notifier";
            NotificationChannel mChannel = new NotificationChannel(channelId, "Modem State Change", importance);
            mChannel.enableVibration(true);
            mChannel.setVibrationPattern(new long[]{0, 10000});
            mChannel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI,
                                     Notification.AUDIO_ATTRIBUTES_DEFAULT);
            manager.createNotificationChannel(mChannel);
            final Notification.Builder builder = new Notification.Builder(getApplicationContext());
            builder.setOngoing(true);
            builder.setPriority(Notification.PRIORITY_HIGH);
            builder.setSmallIcon(R.drawable.modem_notifier);
            builder.setContentTitle(title);
            builder.setContentText(info);
            Intent notificationIntent = new Intent(this, ModemInfoActivity.class);
            notificationIntent.putExtra("notifierInfo", info);
            PendingIntent contentIntent =  PendingIntent.getActivity(mContext, 0, notificationIntent,
                     PendingIntent.FLAG_CANCEL_CURRENT);
            builder.setContentIntent(contentIntent);
            builder.setChannelId(channelId);
            Notification notification = builder.build();
            notification.flags |= Notification.FLAG_NO_CLEAR;
            manager.notify(notificationId, notification);
        }
    }

    private void hideNotification(int notificationId) {
        if(DEBUG) Log.d(MTAG, "hideNotification");
        if(IS_DEBUGGABLE){
            NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            manager.cancel(notificationId);
        }
    }

    private void sendModemStatBroadcast(String modemStat,String info) {
        if(DEBUG) Log.d(MTAG, "sendModemStatBroadcast : " + modemStat);
        Intent intent = new Intent(MODEM_STAT_CHANGE);
        intent.putExtra(MODEM_STAT, modemStat);
        intent.putExtra(MODEM_INFO, info);
        intent.addFlags(0x01000000); //Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
        sendBroadcast(intent);
    }

    private String initSsdaModeMsg() {
        String ssdaMode = "5MODE";
        if(DEBUG) Log.d(MTAG, "ssdaMode: "+ ssdaMode);

        return "SUBSCRIBE " + ssdaMode + " DUMP";
    }
}
