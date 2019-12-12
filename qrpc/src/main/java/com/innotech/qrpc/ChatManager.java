package com.innotech.qrpc;

import android.os.Handler;
import android.os.Message;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

public class ChatManager {
    /**
     * 登录状态
     */
    public static final int LOGIN_DEFAULT = 0; // 登录默认状态
    public static final int LOGIN_SUCCESS = 1; // 登录成功状态
    public static final int LOGIN_FAIL = 2; // 登录失败状态

    public static IAppEnvironment iAppEnvironment;
    // 重连次数，默认5次
    private int totalReconnectTime = 5;
    // 当前重连次数
    private int re_connect_time;
    // 长连接状态
    private volatile int socket_state;
    // 登录状态
    private int loginState;
    private static ChatManager instance;
    private Socket mSocket;
    //socket输入流
    private InputStream mInputStream;
    //socket输出流
    private DataOutputStream mDataOutputStream;
    // read线程
    private Thread readThread;
    // write线程
    private Thread writeThread;
    // 是否结束写线程
    private AtomicBoolean stopWrite;
    /**
     * write LinksocketThreadedBlockingQueue
     * 要写给服务端的信息先放入Queue中，再从Queue中取出进行处理
     * 防止多线程同时写产生批量写失败
     */
    private LinkedBlockingQueue<WriteData> writeQueue;
    //
    private LinkedBlockingQueue<Boolean> reconnectQueue;
    // 记录发送的信息
    private ConcurrentHashMap<Long, WriteData> requestInfoMap;
    // 处理socket操作
    private SocketHandler sHandler;

    private StatusReceiver statusReceiver;
    private SendErrorReceiver sendErrorReceiver;
    private CmdRespReceiver cmdRespReceiver;
    private IpAddressProvider ipAddressProvider;

    public static ChatManager getInstance() {
        if (instance == null) {
            synchronized (ChatManager.class) {
                if (instance == null) {
                    instance = new ChatManager();
                }
            }
        }
        return instance;
    }

    /**
     * 创建对象
     * 初始化参数
     */
    private ChatManager() {
        socket_state = SocketState.STATE_DEFAULT;
        writeQueue = new LinkedBlockingQueue<>();
        reconnectQueue = new LinkedBlockingQueue<>(1);
        requestInfoMap = new ConcurrentHashMap<>();
        sHandler = new SocketHandler();
    }

    /**
     * 开启线程，无线循环检测长连接的状态，如果非正常连接状态，则建立连接
     * 重连之前需要检测读/写线程是否都关闭掉了，socket是否close掉了。
     */
    private void initSocket() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean[] isFail = {false};
                try {
                    // 检测长连接状态
                    if (socket_state != SocketState.STATE_CONNECTION) {
                        ipAddressProvider.getAddress(re_connect_time, new TCallback<IpAddress>() {
                            @Override
                            public void onSuccess(IpAddress s) {
                                connect(s);
                            }

                            @Override
                            public void onFailure(String msg) {
                                isFail[0] = true;
                            }
                        });
                    }
                } catch (Exception e) {
                    isFail[0] = true;
                    LogUtils.e("方法:initSocket,run exception:" + e.getMessage());
                    IMReport.getInstance().report("方法:initSocket,run exception:" + e.getMessage());
                } finally {
                    reconnectQueue.poll();
                    if (isFail[0]) {
                        LogUtils.e("获取长连接出现异常，进行重连");
                        IMReport.getInstance().report("获取长连接出现异常，进行重连");
                        endSocketReConnect();
                    }
                }
            }
        }).start();
    }

    /**
     * 结束长连接
     */
    public synchronized void endSocket() {
        socket_state = SocketState.STATE_DISCONNECT;
        // 检查读线程是否关闭，若存活，则终止掉
        if (readThread != null) {
            if (readThread.isAlive()) {
                readThread.interrupt();
            }
            readThread = null;
        }
        // 检查写线程是否关闭，若存活，则终止掉
        if (writeThread != null) {
            stopWrite.compareAndSet(false, true);
            if (writeThread.isAlive()) {
                writeThread.interrupt();
            }
            writeThread = null;
        }
        // 检查socket是否存在，以及是否close
        if (mSocket != null) {
            if (!mSocket.isClosed()) {
                try {
                    mSocket.close();
                } catch (IOException e) {
                    //
                }
            }
            mSocket = null;
        }
        // 处理写队列中的内容
        if (writeQueue != null && writeQueue.size() > 0) {
            WriteData[] dataList = writeQueue.toArray(new WriteData[0]);
            for (WriteData data : dataList) {
                if (sendErrorReceiver != null) {
                    sendErrorReceiver.onSendError(data.getCommand(), data);
                }
            }
        }
    }

    // 结束长连接，尝试重连
    private synchronized void endSocketReConnect() {
        endSocket();
        notifyDisconnect();
    }

    /**
     * 建立长连接，开启读/写线程
     */
    private void connect(IpAddress ipAddress) {
        try {
            // 建立长连接，设置输入输出流
            mSocket = new Socket(ipAddress.getIp(), ipAddress.getPort());
            LogUtils.eDebug("Successful connection to the server(" + ipAddress.getIp() + ":" + ipAddress.getPort() + ")");
            LogUtils.e("Successful connection to the server");
            mInputStream = mSocket.getInputStream();
            mDataOutputStream = new DataOutputStream(mSocket.getOutputStream());
            socket_state = SocketState.STATE_CONNECTION;
            // 开启读线程
            read();
            // 开启写线程之前需要把writeQueue清空
            if (writeQueue != null && writeQueue.size() > 0) {
                writeQueue.clear();
            }
            // 开启写线程
            stopWrite = new AtomicBoolean(false);
            writeData();
            notifyConnect();
        } catch (Exception e) {
            LogUtils.e("方法:connect, 异常:" + e.getMessage());
            IMReport.getInstance().report("方法:connect, 异常:" + e.getMessage());
        }
    }

    /**
     * 读取数据
     */
    private void read() {
        readThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    // 每次循环线判断一下外部是否有中断线程
                    boolean isInterrupted = Thread.currentThread().isInterrupted();
                    if (isInterrupted) {
                        LogUtils.e("方法:read,读线程:" + Thread.currentThread().getName() + "终止");
                        IMReport.getInstance().report("方法:read,读线程:" + Thread.currentThread().getName() + "终止");
                        break;
                    }
                    try {
                        // 读取长连接信息
                        byte[] lenB = readByLen(16);
                        // lenB为Null，说明读到-1或者读异常
                        // 需要切换长连接状态，重置连接
                        if (lenB == null) {
                            LogUtils.e("方法:read,读到-1或者读异常(16)");
                            sHandler.sendEmptyMessage(0);
                            IMReport.getInstance().report("方法:read,读到-1或者读异常(16)");
                            break;
                        }
                        int len = getLenByData(lenB);
                        long memory = Runtime.getRuntime().maxMemory() - (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
                        // 包长度大于闲置空间时，需要重置连接
                        if (len - 12 > memory) {
                            System.gc();
                            long newMemory = Runtime.getRuntime().maxMemory() - (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
                            if (len - 12 > newMemory) {
                                LogUtils.e("方法:read,包长度大于闲置空间");
                                sHandler.sendEmptyMessage(0);
                                IMReport.getInstance().report("方法:read,包长度大于闲置空间");
                                break;
                            }
                        }
                        long requestId = getRequestIDByData(lenB);
                        int command = getCommandByData(lenB);
                        String json = "";
                        if (len - 12 > 0) {
                            byte[] lenJ = readByLen(len - 12);
                            if (lenJ == null) {
                                LogUtils.e("方法:read,读到-1或者读异常(" + (len - 12) + ")");
                                sHandler.sendEmptyMessage(0);
                                IMReport.getInstance().report("方法:read,读到-1或者读异常(" + (len - 12) + ")");
                                break;
                            }
                            boolean isGZip = getGZip(lenB);
                            if (isGZip){
                                ByteArrayOutputStream out = new ByteArrayOutputStream();
                                ByteArrayInputStream in = new ByteArrayInputStream(lenJ);
                                try {
                                    GZIPInputStream ungzip = new GZIPInputStream(in);
                                    byte[] buffer = new byte[256];
                                    int n;
                                    while ((n = ungzip.read(buffer)) >= 0) {
                                        out.write(buffer, 0, n);
                                    }
                                    ungzip.close();
                                    in.close();
                                    out.flush();
                                    out.close();
                                } catch (IOException e) {
                                    LogUtils.e("gzip uncompress error."+e);
                                }
                                json = out.toString();
                            }else{
                                json = new String(lenJ);
                            }

                            LogUtils.eLongDebug("readData json:" + json);
                        }
                        // 匹配发送时的信息
                        WriteData writeData = requestInfoMap.get(requestId);
                        requestInfoMap.remove(requestId);
                        if (cmdRespReceiver != null) {
                            cmdRespReceiver.onResponse(command, writeData, json);
                        }
                        re_connect_time = 0;
                    } catch (Exception e) {
                        if (! (e instanceof InterruptedException)) {
                            sHandler.sendEmptyMessage(0);
                        }
                        LogUtils.e("方法:read,读线程异常:" + e.getMessage());
                        IMReport.getInstance().report("方法:read,读线程异常:" + e.getMessage());
                    }
                }
            }
        });
        readThread.start();
    }

    /**
     * 写数据
     */
    private void writeData() {
        writeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (!stopWrite.get()) {
                        boolean isInterrupted = Thread.currentThread().isInterrupted();
                        if (isInterrupted) {
                            LogUtils.e("方法:writeData,写线程:" + Thread.currentThread().getName() + "终止");
                            IMReport.getInstance().report("方法:writeData,写线程:" + Thread.currentThread().getName() + "终止");
                            break;
                        }
                        WriteData writeData = writeQueue.take();
                        if (isConnecting()) {
                            try {
                                mDataOutputStream.write(writeData.getData());
                                writeData.getResultQueue().put(true);
                                re_connect_time = 0;
                                LogUtils.e("write success");
                            } catch (Exception e) {
                                writeData.getResultQueue().put(false);
                                if (! (e instanceof InterruptedException)) {
                                    sHandler.sendEmptyMessage(0);
                                }
                                LogUtils.e("方法:writeData,写异常:" + e.getMessage());
                                IMReport.getInstance().report("方法:writeData,写异常:" + e.getMessage());
                            }
                        } else {
                            writeData.getResultQueue().put(false);
                            LogUtils.e("方法:writeData,写之前检测到长连接已断开");
                            sHandler.sendEmptyMessage(0);
                            IMReport.getInstance().report("方法:writeData,写之前检测到长连接已断开");
                        }
                    }
                } catch (Exception e) {
                    if (! (e instanceof InterruptedException)) {
                        sHandler.sendEmptyMessage(0);
                    }
                    LogUtils.e("方法:writeData,写异常:" + e.getMessage());
                    IMReport.getInstance().report("方法:writeData,写异常:" + e.getMessage());
                }
            }
        });
        writeThread.start();
    }

    /**
     * 是否连接中
     */
    private boolean isConnecting() {
        return mSocket != null && mSocket.isConnected() && !mSocket.isClosed() && !mSocket.isInputShutdown();
    }

    public void sendJsonToServer(int cmd, String json) {
        sendJsonToServer(cmd, json, null);
    }

    /**
     * 发送数据
     * @param cmd 指令码
     * @param json json格式数据
     * @param callback 成功或者失败回调
     */
    public void sendJsonToServer(int cmd, String json, TCallback callback) {
        LogUtils.eDebug("send " + json + " to server with cmd " + cmd);
        LogUtils.e("send " + "to server with cmd " + cmd);
        WriteData writeData = new WriteData(cmd, json, callback);
        if (socket_state == SocketState.STATE_CONNECTION) {
            try {
                requestInfoMap.put(CommonUtils.longFrom8Bytes(writeData.getRequestsID(), 0, false), writeData);
                writeQueue.put(writeData);
            } catch (Exception e) {
                LogUtils.e("方法:sendJsonToServer,异常:" + e.getMessage());
                // 放队列出现异常，直接进行返回处理。
                if (sendErrorReceiver != null) {
                    sendErrorReceiver.onSendError(cmd, writeData);
                }
                IMReport.getInstance().report("方法:sendJsonToServer,异常:" + e.getMessage());
            }
        } else {
            // 发送消息时，长连接非连接状态，直接进行返回处理。
            if (sendErrorReceiver != null) {
                sendErrorReceiver.onSendError(cmd, writeData);
            }
        }
    }

    /**
     * 获取服务端回包的信息
     * 4字节剩余包长
     */
    private int getLenByData(byte[] data) {
        byte[] bytes = new byte[4];
        System.arraycopy(data, 0, bytes, 0, 4);
        return CommonUtils.big_bytesToInt(bytes);
    }

    /**
     * 获取服务端回包的信息
     * 8字节requestID
     */
    private long getRequestIDByData(byte[] data) {
        byte[] bytes = new byte[8];
        System.arraycopy(data, 4, bytes, 0, 8);
        return CommonUtils.longFrom8Bytes(bytes, 0, false);
    }

    /**
     * 获取服务端回包的信息
     * 3字节命令的值
     * 第一位已被服务端用于其他用途，默认补充为0，为了凑齐4字节进行计算
     */
    private int getCommandByData(byte[] data) {
        byte[] bytes = new byte[4];
        bytes[0] = 0;
        System.arraycopy(data, 13, bytes, 1, 3);
        return CommonUtils.big_bytesToInt(bytes);
    }

    private boolean getGZip(byte[] data){
        byte b = data[12];
        return ((b >> 5) & 0x1)==1;
    }

    /**
     * 读取长度为len的字符数组
     *
     * @param len：长度
     * @return 字符数组
     */
    private byte[] readByLen(int len) {
        byte[] result = new byte[len];
        int readLen = 0;
        try {
            while (true) {
                int curReadLen;
                if (result.length - readLen < 1024) {
                    curReadLen = mInputStream.read(result, readLen, result.length - readLen);
                } else {
                    curReadLen = mInputStream.read(result, readLen, 1024);
                }
                readLen += curReadLen;
                if (readLen == len) break;
                if (curReadLen == -1) {
                    result = null;
                    break;
                }
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    class SocketHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case 0:
                    setLoginState(LOGIN_DEFAULT);
                    endSocketReConnect();
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * 登录中
     */
    public boolean inLogin() {
        LogUtils.e("inLogin: +" + socket_state);
        return loginState == LOGIN_SUCCESS;
    }

    /**
     * 重连
     */
    public void reConnect() {
        if (reconnectQueue.offer(true)) {
            initSocket();
        }
    }

    /**
     * 多次重连
     * 清除重连次数，重新进行多次重连
     */
    public void forceReconnect(){
        if (reconnectQueue.offer(true)) {
            re_connect_time = 0;
            initSocket();
        }
    }

    // 连接成功
    private void notifyConnect() {
        if (statusReceiver != null) {
            statusReceiver.onConnect();
        }
    }

    /**
     * 设置可重连次数
     * @param time 需要大于0
     */
    public void setReconnectTime(int time) {
        if (time > 0) {
            this.totalReconnectTime = time;
        }
    }

    // 3次重连失败后，通知长连接断开
    private synchronized void notifyDisconnect() {
        if (re_connect_time >= totalReconnectTime) {
            LogUtils.e("已重连次数：" + re_connect_time + "，不再重连，进入onDisConnect回调。");
            if (statusReceiver != null) {
                statusReceiver.onDisConnect();
            }
        }else{
            LogUtils.e("已重连次数：" + re_connect_time + "，进行下次重连。");
            re_connect_time++;
            reConnect();
        }
    }

    /**
     * 设置长连接状态回调
     * @param receiver
     */
    public void setStatusReceiver(StatusReceiver receiver) {
        this.statusReceiver = receiver;
    }

    // 长连接连接和断开的回调
    public interface StatusReceiver {

        // 连接成功
        void onConnect();

        // 连接断开
        void onDisConnect();
    }

    /**
     * 设置消息发送失败回调
     *
     * @param receiver
     */
    public void setSendErrorReceiver(SendErrorReceiver receiver) {
        this.sendErrorReceiver = receiver;
    }

    public interface SendErrorReceiver {
        /**
         * 发送消息失败时调用
         * @param command 发消息指令码
         * @param data 发消息，消息结构
         */
        void onSendError(int command, WriteData data);
    }

    /**
     * 设置收到消息回调
     *
     * @param receiver
     */
    public void setCmdRespReceiver(CmdRespReceiver receiver) {
        this.cmdRespReceiver = receiver;
    }


    public interface CmdRespReceiver {
        /**
         * 收到消息后调用
         * @param command 收消息指令码
         * @param data 收到的消息对应的请求消息结构
         * @param json 收到的消息
         */
        void onResponse(int command, WriteData data, final String json);
    }

    /**
     * 设置登录状态
     * @param loginState
     */
    public void setLoginState(int loginState) {
        this.loginState = loginState;
    }

    public static IAppEnvironment.Environment getEnvironment() {
        if (iAppEnvironment == null) {
            return null;
        }
        return iAppEnvironment.getEnvironment();
    }

    /**
     * 设置ip port提供者
     * @param provider
     */
    public void setIpAddressProvider(IpAddressProvider provider) {
        this.ipAddressProvider = provider;
    }

    /**
     * ip port 提供者
     */
    public interface IpAddressProvider {
        /**
         * 获取ip port组装成IpAddress，通过callback回调返回
         * @param re_connect_time 重连次数，以此确定重连延迟时间
         * @param callback 成功和错误回调
         */
        void getAddress(int re_connect_time, TCallback<IpAddress> callback);
    }
}
