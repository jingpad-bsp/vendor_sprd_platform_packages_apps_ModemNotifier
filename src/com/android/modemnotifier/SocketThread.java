package com.android.modemnotifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

/**
 * Thread to establish socket connection
 * @author SPRD
 */
public class SocketThread extends Thread {
    private static final String MTAG = "ModemNotifier.SocketHandler";
    private static final boolean DEBUG = true;
    private final Object mObjectLock = new Object();
    private String SOCKET_NAME = "";
    private String mSocketInitMessage = "";
    private static final int BUF_SIZE = 512;

    //Socket connection retry intervals
    private static final int FIRST_RETRY_INTERVAL = 5 * 1000; //5 secs
    private static final int SECOND_RETRY_INTERVAL = 10 * 1000; // 10 secs
    private static final int THIRD_RETRY_INTERVAL = 30 * 1000; // 30 secs
    private static final int FOURTH_RETRY_INTERVAL = 60 *1000; // 1 min
    private static final int FIFTH_RETRY_INTERVAL = 5 * 60 *1000; //5 mins
    private int mRetryTimes = 0;
    private int mErrorCount = 0;

    /**
     * Socket client thread class
     * @param threadName the name of the new thread
     * @param socketName non-null name
     * @param initMessage the initial message to be sent to socket server
     */
    public SocketThread(String threadName, String socketName, String initMessage) {
        super(threadName);
        SOCKET_NAME = socketName;
        mSocketInitMessage = initMessage;
    }

    @Override
    public void run(){
        synchronized (mObjectLock) {
            LocalSocket socket = null;
            try {
                socket = new LocalSocket();
                LocalSocketAddress socketAddr = new LocalSocketAddress(SOCKET_NAME,
                        LocalSocketAddress.Namespace.ABSTRACT);
                byte[] buf = new byte[BUF_SIZE];
                if(DEBUG) Log.d(MTAG, " -runSocket " + SOCKET_NAME);
                connectToSocket(socket, socketAddr);
                for (;;) {
                    if(needStopSocket()) break;
                    int cnt = 0;
                    InputStream is = null;
                    try {
                        is = socket.getInputStream();
                        cnt = is.read(buf, 0, BUF_SIZE);
                        if(DEBUG) Log.d(MTAG, "read " + cnt + " bytes from " + SOCKET_NAME + ": \n" );
                    } catch (IOException e) {
                        Log.e(MTAG, "read exception " + SOCKET_NAME + "\n");
                        mErrorCount++;
                    }
                    if (cnt > 0) {
                        String info = "";
                        try {
                            info = new String(buf, 0, cnt, "US-ASCII");
                        } catch (UnsupportedEncodingException e) {
                            Log.e(MTAG, "UnsupportedEncodingException in " + SOCKET_NAME);
                        } catch (StringIndexOutOfBoundsException e) {
                            Log.e(MTAG, "StringIndexOutOfBoundsException in " + SOCKET_NAME);
                        }
                        if(DEBUG) Log.d(MTAG, "read something: "+ info);
                        if (TextUtils.isEmpty(info)) {
                            continue;
                        }
                        handleInputMsg(info);
                        continue;
                    } else if (cnt < 0) {
                        try {
                            is.close();
                            socket.close();
                        } catch (IOException e) {
                            Log.e(MTAG, "close exception " + SOCKET_NAME + "\n");
                            mErrorCount++;
                        }
                        if (mRetryTimes <= 5) {
                            socket = new LocalSocket();
                            connectToSocket(socket, socketAddr);
                        } else {
                            Log.e(MTAG, "After retry 5 times still cannot connect to socket server," +
                                    " stop retrying and kill the thread.");
                            // break from run(), thread will be GC.
                            break;
                        }
                    }
                }
            } catch (RuntimeException e) {
                Log.e(MTAG, "RuntimeException occured in " + SOCKET_NAME + ": " + e.getMessage() +"\n");
            }  catch (Exception ex) {
                Log.e(MTAG, "Exception occured in " + SOCKET_NAME + ": " + ex.getMessage() +"\n");
            }

            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ex2) {
            }
        }
    }

    /**
     * Try to connect to socket server.
     * If connection failed, will retry 5 times with incremental interval.
     * After 5 times fail, stop retrying and join the thread.
     * @param socket socket in the UNIX-domain namespace
     * @param socketAddr A UNIX-domain (AF_LOCAL) socket address
     */
    private void connectToSocket(LocalSocket socket, LocalSocketAddress socketAddr) {
        for (;;) {
            try {
                socket.connect(socketAddr);
                // send message to socket server if needed.
                sendInitOutputMsg(socket);
                // Unisoc add for bug924204
                subscribeWcnDumpMsg(socket);
                // socket successfully connected, reset retry times
                mRetryTimes = 0;
                break;
            } catch (IOException ioe) {
                // Retry 5 times
                mRetryTimes++;
                int retryInterval = FIRST_RETRY_INTERVAL;
                if (mRetryTimes == 2) retryInterval = SECOND_RETRY_INTERVAL;
                else if (mRetryTimes == 3) retryInterval = THIRD_RETRY_INTERVAL;
                else if (mRetryTimes == 4) retryInterval = FOURTH_RETRY_INTERVAL;
                else if (mRetryTimes == 5) retryInterval = FIFTH_RETRY_INTERVAL;
                else if (mRetryTimes > 5) break;
                Log.e(MTAG, "Connect to " + SOCKET_NAME + " failed, retry times = " +
                        mRetryTimes + ", retry interval = " + retryInterval + "ms");
                SystemClock.sleep(retryInterval);
                continue;
            }
        }
    }

    /**
     * Stop socket when retry connection more than 5 times
     * or catch exception more than 10 time in communication.
     * @return true to stop the socket
     */
    private boolean needStopSocket(){
        if (mRetryTimes > 5 || mErrorCount > 10){
            Log.e(MTAG, "Stop " + SOCKET_NAME + " socket! RetryTimes = "
                    + mRetryTimes + " mErrorCount = " + mErrorCount);
            return true;
        }
        return false;
    }

    /**
     * Send message to socket server as long as socket is connected.
     * @param socket a AF_LOCAL/UNIX domain stream socket
     */
    private void sendInitOutputMsg(LocalSocket socket){
        if (!TextUtils.isEmpty(mSocketInitMessage)) {
            try {
                OutputStream os = socket.getOutputStream();
                if(os != null) {
                    final StringBuilder cmdBuilder = new StringBuilder(mSocketInitMessage).append('\n');
                    final String cmd = cmdBuilder.toString();
                    os.write(cmd.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (IOException ioe) {
                Log.e(MTAG, "IOException occured in " + SOCKET_NAME + " when trying to send msg to server.");
            }
        }
    }

    /**
     * Unisoc add for bug924204 to subscribe CP2 dump
     */
    private void subscribeWcnDumpMsg(LocalSocket socket){
        if (SOCKET_NAME.equals("slogmodem")) {
            try {
                String subscribeMsg = "SUBSCRIBE WCN DUMP";
                OutputStream os = socket.getOutputStream();
                if(os != null) {
                    final StringBuilder cmdBuilder = new StringBuilder(subscribeMsg).append('\n');
                    final String cmd = cmdBuilder.toString();
                    os.write(cmd.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (IOException ioe) {
                Log.e(MTAG, "IOException occured in " + SOCKET_NAME + " when trying to send msg to server.");
            }
        }
    }

    /**
     * Sub class needs to override this method to handle
     * messages sent from socket server.
     * @param info socket message from server
     */
    protected void handleInputMsg(String info){
        // No default implementation
    }
}
