package ru.jettech.jtp.jtptank1.service;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.StrictMode;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ru.jettech.jtp.jtptank1.util.Constants;

public class TCPClient {
    /*
        VARIABLES
    */
    private static final String TAG = "JTPTank1.TCPClient"; //NON-NLS
    private static final String TAG_TEST = "TEST-TAG";
    private static final long CONNECT_LIFE_TIME = 5 * 1000; // 5sec
    private static final long SERVER_KICK_INTERVAL = 2 * 1000; // 2sec
    private static final long SLOT_WAIT_TIME = 200; // 0.2sec
    private static final long DISCONNECT_WAIT_TIME = 5 * 1000; // 5sec

    private static final int SEND_BUFFER_SIZE = Constants.SEND_PACKET_SIZE;
    private static final int RCV_BUFFER_SIZE = Constants.RCV_PACKET_SIZE;

    private static final int KEY_CONNECT = SelectionKey.OP_CONNECT;
    private static final int KEY_READ = SelectionKey.OP_READ;
    private static final int KEY_WRITE = SelectionKey.OP_WRITE;

    private InetSocketAddress mServerAddress;
    private Selector mSelector;
    private SelectionKey mChannelKey;

    private boolean mActive = false;
    private Semaphore mSendSlot;
    private int mTankId;
    private String mServerAddr;
    private int mServerPort;
    private AtomicInteger mConnected;
    private AtomicInteger mBatteryLevel;
    private SocketChannel mChannel;
    private long mLastActTime;
    private Timer mServerKicker;

    TCPClient(int tankId, String serverAddr, int serverPort) {
        mTankId = tankId;
        mServerAddr = serverAddr;
        mServerPort = serverPort;

        mConnected = new AtomicInteger(0);
        mBatteryLevel = new AtomicInteger(100);
        mSendSlot = new Semaphore(1);
        mServerKicker = new Timer();

        try {
            mServerAddress = new InetSocketAddress(mServerAddr, mServerPort);
            mActive = init();
        } catch (SecurityException e) {
            Log.w(TAG, "Security exception while resolving server address " + serverAddr);
        }

        if (mActive) {
            mServerKicker.schedule(new ServerKickTask(), SERVER_KICK_INTERVAL, SERVER_KICK_INTERVAL);
        }
    }

    /*
        INNER CLASSES
    */
    private final class ServerKickTask extends TimerTask implements Runnable {
        public void run() {
            try {
                if (mSendSlot.tryAcquire(SLOT_WAIT_TIME, TimeUnit.MILLISECONDS)) {
                    try {
                        TCPClient.this.connect();
                    } finally {
                        mSendSlot.release();
                    }
                }
            } catch (InterruptedException e) {
                Log.i(TAG, "TCPClient: wait interrupted");
                Thread.currentThread().interrupt();
            }
        }
    }

    private class SendMessageTask extends AsyncTask<ByteBuffer, Void, Void> {

        @Override
        protected Void doInBackground(ByteBuffer... byteBuffers) {
            Log.i(TAG, "buffer length " + byteBuffers[0].array().length);

            doSendData(byteBuffers[0].array(), KEY_WRITE);
            return null;
        }
    }


    /*
        GETTERS
           &
        SETTERS
    */
    public boolean isConnected() {
        return (mConnected.get() > 0);
    }

    private void setConnected(boolean value) {
        int v = value ? 1 : 0;
        mConnected.set(v);
    }

    /*
        INTERFACES
    */
    public void sendData(byte[] buff) {
        Log.i(TAG, " --- " + mServerAddr + " - " + mActive);
        if (!mActive) return;
        doSendData(buff);
    }

    public void setBatteryLevel(int newValue) {
        mBatteryLevel.set(newValue);
    }

    public static boolean isNetworkConnected(Context context) {
        ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr == null) return true;
        NetworkInfo info = connMgr.getActiveNetworkInfo();
        return (info != null && info.isConnected());
    }

    /*
        FUNCTIONS
    */
    private boolean init() {
        try {
            mSelector = Selector.open();
            mChannel = SocketChannel.open();
            mChannel.configureBlocking(false);

            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);

            int ops = KEY_CONNECT | KEY_WRITE | KEY_READ;
            mChannelKey = mChannel.register(mSelector, ops);
            Log.i(TAG, "init and configure finished");
            return true;

        } catch (IOException e) {
            Log.e("init error", e.getMessage());
            return false;
        }
    }

    private void connect() {
        long prevActInterval = System.currentTimeMillis() - mLastActTime;

        if (
                isConnected() &&
                        ((prevActInterval > CONNECT_LIFE_TIME) ||
                                ((prevActInterval > SERVER_KICK_INTERVAL) && !checkConnection()))
        ) {
            try {
                setConnected(false);
                Log.e(TAG + mServerAddr, "connect: Close Socket timeout");
                mChannel.close();
            } catch (Exception e) {
                Log.e(TAG, "C1: Error", e);
            }
        }
        if (!isConnected()) {
            try {
                Log.i(TAG + mServerAddr, "Socket connection");

                try {
                    mChannel = SocketChannel.open(mServerAddress);
                    mLastActTime = System.currentTimeMillis();
                    setConnected(checkConnection());
                } catch (Exception e) {
                    Log.e(TAG + mServerAddr, "connect: Close Socket");
                    mChannel.close();
                    Log.e(TAG, "C2: Error", e);
                }
            } catch (IOException e) {
                Log.e(TAG, "C3: Error", e);
            }
        }
    }

    private void doSendData(byte[] buff) {

        connect();
        if (!isConnected()) return;
        Log.i(TAG_TEST, "connected");
        setCurrentOperation(KEY_WRITE | KEY_READ);
        Log.i(TAG_TEST, "set operations suc");

        select(ByteBuffer.wrap(buff));
    }

    private void doSendData(byte[] buff, int ops) {

        connect();
        if (!isConnected()) return;
        Log.i(TAG_TEST, "connected");
        setCurrentOperation(ops);
        Log.i(TAG_TEST, "set operations suc");

        select(ByteBuffer.wrap(buff));
    }

    private boolean checkConnection() {
        try {
            ByteBuffer buff = ByteBuffer.allocate(SEND_BUFFER_SIZE);
            buff.order(ByteOrder.LITTLE_ENDIAN);
            buff.clear();

            // buff.putChar('T'); buff.putChar('B'); // Beginning marker 'TB' 4 bytes

            buff.putFloat(0); // Latitude
            buff.putFloat(0); // Longitude
            buff.putFloat(0); // Altitude
            buff.putLong(0);  // TimeMilli
            buff.putInt(0);   // Packet num
            buff.putInt(mTankId); // TankId
            buff.putInt(mBatteryLevel.get());   // Battery level
            buff.putFloat(0); // Speed

            // buff.putChar('T'); buff.putChar('E'); // Beginning marker 'TE' 4 bytes

            buff.flip();

            setCurrentOperation(KEY_WRITE);
            select(buff);
            setCurrentOperation(KEY_READ);
            select(buff);

            return true;
        } catch (Exception e) {
            Log.e(TAG, "checkConnection(): Error", e);
            return false;
        }
    }

    private void setCurrentOperation(int op) {
        if (mChannelKey != null) {
            mChannelKey.interestOps(op);
        }
    }


    private void select(ByteBuffer buffer) {
        try {
            Log.d(TAG_TEST, "thread name: " + Thread.currentThread().getName());
            printBuff(buffer);
            int numConnections = mSelector.selectNow();

            if (numConnections > 0) {
                SelectionKey key;
                Iterator<SelectionKey> keys = mSelector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    key = keys.next();
                    keys.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    if (mChannelKey.isReadable() && mChannelKey.isValid()) {
                        Log.i(TAG_TEST, "readable");
                        Log.i(TAG, "before read");
                        read();
                        Log.i(TAG, "after read");
                    }
                    if (mChannelKey.isWritable() && mChannelKey.isValid()) {
                        Log.i(TAG_TEST, "writable");
                        Log.i(TAG, "before write");
                        write(buffer);
                        Log.i(TAG, "before write");
                    }
                }
                mSelector.selectedKeys().clear();

            }
        } catch (IOException e) {
            Log.e("select error", e.getMessage());
        }
    }

    private void write(ByteBuffer buffer) {
        try {
            Log.i(TAG, "trying to send data");
            if (buffer.limit() == 36) {
                Log.i(TAG, buffer.limit() + " - " +
                                // b.getChar(0) + b.getChar(2) + "," +
                                buffer.getFloat(0) + "," +
                                buffer.getFloat(4) + "," +
                                buffer.getFloat(8) + "," +
                                buffer.getLong(12) + "," +
                                buffer.getInt(20) + "," +
                                buffer.getInt(24) + "," +
                                buffer.getInt(28) + "," +
                                buffer.getFloat(32) // + "," +
                        // b.getChar(40) + b.getChar(42)
                );
            }

            buffer.rewind();
            int bytesWrite = mChannel.write(buffer);
            Log.i(TAG, "bytes write " + bytesWrite);

            if (bytesWrite == -1) {
                setConnected(false);
                //TODO do anything if connection is lost
            }
            mChannelKey.interestOps(mChannelKey.interestOps() & ~KEY_WRITE);
        } catch (IOException e) {
            Log.e("write error", e.getMessage());
        }
    }

    void printBuff(ByteBuffer buffer) {
        ByteBuffer bb = buffer;
        Log.i("BUFFER", Arrays.toString(bb.array()));

    }

    private void read() {
        try {
            Log.i(TAG, "trying to read data");
            ByteBuffer buffer = ByteBuffer.allocate(RCV_BUFFER_SIZE);
            int bytesRead = mChannel.read(buffer);
            mLastActTime = System.currentTimeMillis();
            if (bytesRead > 0) {
                Log.w(TAG, bytesRead + " bytes read");
            } else {
                Log.w(TAG, "Zero bytes read");
            }
            StringBuilder convertedString = new StringBuilder();

            for (byte c :
                    buffer.array()) {
                convertedString.append((char) c);
            }
            Log.i(TAG, "server echo: " + convertedString.toString());

            Log.i(TAG, "bytes read " + bytesRead);
            if (bytesRead == -1) {
                setConnected(false);
                //TODO do anything if connection is lost
            }
            mChannelKey.interestOps(mChannelKey.interestOps() & ~KEY_READ);

        } catch (IOException e) {
            Log.e("read error", e.getMessage());
        }
    }


    private void sendGoodBy() {
        try {

            byte[] msg = "jtp_end".getBytes();
            SendMessageTask task = new SendMessageTask();
            task.execute(ByteBuffer.wrap(msg));

            while (task.getStatus() != AsyncTask.Status.FINISHED) {

            }
            task.cancel(true);

        } catch (Exception e) {
            Log.e(TAG, "goodBy: Error", e);
        }
    }

    public void disconnect() {

        mServerKicker.cancel();
        mServerKicker = new Timer();

        if (isConnected()) {
            try {
                sendGoodBy();
                Log.e(TAG + mServerAddr, "disconnect: Close Socket");
                mChannel.close();
                setConnected(false);

            } catch (Exception e) {
                Log.e(TAG, "disconnect error", e);
            }
        }
    }


}