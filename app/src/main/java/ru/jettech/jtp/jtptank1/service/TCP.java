package ru.jettech.jtp.jtptank1.service;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
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

public class TCP {
    private static final String TAG = "JTPTank1.TCPClient"; //NON-NLS
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

    private final class ServerKickTask extends TimerTask implements Runnable {
        public void run() {
            try {
                if (mSendSlot.tryAcquire(SLOT_WAIT_TIME, TimeUnit.MILLISECONDS)) {
                    try {
                        TCP.this.connect();
                    } finally {
                        mSendSlot.release();
                    }
                }
            } catch (InterruptedException e) {
                Log.i(TAG, "TCPClient: wait interrupted");
            }
        }
    }

    TCP(int tankId, String serverAddr, int serverPort) {
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

    private boolean init() {
        try {
            mSelector = Selector.open();
            mChannel = SocketChannel.open();
            mChannel.configureBlocking(false);

            int ops = KEY_CONNECT | KEY_WRITE | KEY_READ;
            mChannelKey = mChannel.register(mSelector, ops);
            Log.i(TAG, "configure finished");
            return true;

        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean isConnected() {
        return (mConnected.get() > 0);
    }

    private void setConnected(boolean value) {
        int v = value ? 1 : 0;
        mConnected.set(v);
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
//                    if (bytesRead > 0) {
//                        Log.w(TAG, bytesRead + " bytes read !!! checkConnection");
//                        mLastActTime = System.currentTimeMillis();
//                    } else {
//                        Log.w(TAG, "Zero bytes read !!! checkConnection");
//                         throw new IOException("Server connection error");
//                    }
//                } catch (SocketTimeoutException e) {
//                    Log.i(TAG, "Socket timeout");
            // mLastActTime = System.currentTimeMillis();
//                }


            return true;
        } catch (Exception e) {
            Log.e(TAG, "checkConnection(): Error", e);
            return false;
        }
    }

    private void sendGoodBy() {
        try {
            byte[] msg = "jtp_end".getBytes();
            //TODO send data process
            setCurrentOperation(KEY_WRITE | KEY_READ);
            select(ByteBuffer.wrap(msg));

        } catch (Exception e) {
            Log.e(TAG, "goodBy: Error", e);
        }
    }

    private void setCurrentOperation(int op) {
        if (mChannelKey != null) {
            mChannelKey.interestOps(op);
        }
    }

    private void select(ByteBuffer buffer) {
        try {
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
                        Log.i(TAG, "before read");
                        read();
                        Log.i(TAG, "after read");
                    }
                    if (mChannelKey.isWritable() && mChannelKey.isValid()) {
                        Log.i(TAG, "before write");
                        write(buffer);
                        Log.i(TAG, "before write");
                    }
                }
                mSelector.selectedKeys().clear();

            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
        }
    }

    private void write(ByteBuffer buffer) {
        try {
            Log.i(TAG, "trying to send data");
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
            buffer.rewind();
            int bytesWrite = mChannel.write(buffer);
            Log.i(TAG, "bytes write " + bytesWrite);
            mChannelKey.interestOps(mChannelKey.interestOps() & ~KEY_WRITE);
        } catch (IOException e) {
            e.printStackTrace();
        }
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
            String convertedString = "";
            for (byte c :
                    buffer.array()) {
                convertedString += (char) c;
            }
            String msg = Arrays.toString(buffer.array());
            System.out.println("server echo: " + convertedString);

            Log.i(TAG, "bytes read " + bytesRead);
            mChannelKey.interestOps(mChannelKey.interestOps() & ~KEY_READ);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void disconnect() {

        mServerKicker.cancel();
        mServerKicker = new Timer();

        if (isConnected()) {
            try {
                sendGoodBy();
                setConnected(false);
                Log.e(TAG + mServerAddr, "disconnect: Close Socket");
                mChannel.close();
            } catch (Exception e) {
                Log.e(TAG, "C: Error", e);
            }
        }
    }


    private void connect() {
        long prevActInterval = System.currentTimeMillis() - mLastActTime;
        if (isConnected())
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
                mChannel = SocketChannel.open(mServerAddress);
                try {
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

    private void internalSendData(byte[] buff) {
        /*try {
            try {
                Log.i(TAG, "Before sendMessage()");
                if (mSocketWriter != null) Log.i(TAG, mSocketWriter.toString());
                if (mSocketWriter != null) {
                    ByteBuffer b = ByteBuffer.wrap(buff);
                    b.order(ByteOrder.LITTLE_ENDIAN);
                    Log.i(TAG, buff.length + " - " +
                                    // b.getChar(0) + b.getChar(2) + "," +
                                    b.getFloat(0) + "," +
                                    b.getFloat(4) + "," +
                                    b.getFloat(8) + "," +
                                    b.getLong(12) + "," +
                                    b.getInt(20) + "," +
                                    b.getInt(24) + "," +
                                    b.getInt(28) + "," +
                                    b.getFloat(32) // + "," +
                            // b.getChar(40) + b.getChar(42)
                    );
                    mSocketWriter.write(buff);
                    mSocketWriter.flush();
                    mLastActTime = System.currentTimeMillis();
                    // sleep(10);
                    Log.i(TAG, "After sendMessage()");
                } else {
                    throw new IOException("Socket writer error");
                }
            } catch (Exception e) {
                setConnected(false);
                Log.e(TAG + mServerAddr, "internalSendData: Close Socket");
                mChannel.close();
                Log.e(TAG, "C: Error", e);
            }
        } catch (IOException e) {
            Log.e(TAG, "C: Error", e);
        }*/
    }

    private void internalCheckResponse() {
        /*try {
            try {
                byte[] buffer = new byte[1024];
                try {
                    int bytesRead = mSocketReader.read(buffer);
                    mLastActTime = System.currentTimeMillis();
                    if (bytesRead > 0) {
                        Log.w(TAG, bytesRead + " bytes read");
                    } else {
                        Log.w(TAG, "Zero bytes read");
                    }
                } catch (SocketTimeoutException e) {
                    Log.i(TAG, "Socket timeout");
                    mLastActTime = System.currentTimeMillis();
                }
            } catch (Exception e) {
                setConnected(false);
                Log.e(TAG + mServerAddr, "internalCheckResponse: Close Socket");
                mChannel.close();
                Log.e(TAG, "C: Error", e);
            }
        } catch (IOException e) {
            Log.e(TAG, "C: Error", e);
        }*/
    }

    private void doSendData(byte[] buff) {


        connect();
        if (!isConnected()) return;
        setCurrentOperation(KEY_WRITE | KEY_READ);
        select(ByteBuffer.wrap(buff));
//                    internalSendData(buff);
//                    if (!isConnected()) return;
//                    internalCheckResponse();


    }

    /*private final class WorkThread implements Runnable {
        private byte[] mBuffer;

        WorkThread(byte[] buffer) {
            mBuffer = buffer;
        }

        public void run() {
            TCP.this.doSendData(mBuffer);
        }
    }*/

    public void sendData(byte[] buff) {
        Log.i(TAG, " --- " + mServerAddr + " - " + mActive);
        if (!mActive) return;
        doSendData(buff);
        /*Thread worker = new Thread(new WorkThread(buff));
        worker.start();*/
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
}
