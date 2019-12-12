##集团im android socket流协议

> 详见 ChatManager类

获取单例
```
public static ChatManager getInstance();
```

初始化配置
```
/**
  * 设置可重连次数
  * @param time 需要大于0
  */
 public void setReconnectTime(int time);

/**
  * ip port 提供者
  */
public interface IpAddressProvider {
        
/**
  * 设置ip port提供者
  * @param provider
  */
	void getAddress(int re_connect_time, TCallback<IpAddress> callback);
}

/**
  * 设置ip port提供者
  * @param provider
  */
public void setIpAddressProvider(IpAddressProvider provider);
```
监听设置
```
// 长连接连接和断开的回调
public interface StatusReceiver {

	// 连接成功
	void onConnect();

	// 连接断开
	void onDisConnect();
}

/**
  * 设置长连接状态回调
  * @param receiver
  */
public void setStatusReceiver(StatusReceiver receiver);

public interface SendErrorReceiver {
/**
  * 发送消息失败时调用
  * @param command 发消息操作码
  * @param data 发消息，消息结构
  */
	void onSendError(int command, WriteData data);
}

/**
  * 设置消息发送失败回调
  * @param receiver
  */
public void setSendErrorReceiver(SendErrorReceiver receiver);

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
  * 设置收到消息回调
  * @param receiver
  */
public void setCmdRespReceiver(CmdRespReceiver receiver);
```

长连接操作
```
/**
  * 第一次连接及重连
  */
public void reConnect()；

/**
  * 多次重连
  * 清除重连次数，重新进行多次重连
  */
public void forceReconnect()；

/**
  * 结束长连接
  */
public synchronized void endSocket()；
```
发送数据
```
public void sendJsonToServer(int cmd, String json)

/**
  * 发送数据
  * @param cmd 操作码
  * @param json json格式数据
  * @param callback 成功或者失败回调
  */
public void sendJsonToServer(int cmd, String json, TCallback callback);
```

登录状态
```
public static final int LOGIN_DEFAULT = 0; // 登录默认状态
public static final int LOGIN_SUCCESS = 1; // 登录成功状态
public static final int LOGIN_FAIL = 2; // 登录失败状态
/**
  * 设置登录状态
  * @param loginState
  */
public void setLoginState(int loginState);

/**
  * 是否是登录中
  */
public boolean inLogin()；
```
