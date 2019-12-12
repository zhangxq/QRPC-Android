package com.innotech.qrpc;

import android.util.Log;

/**
 * innotech push需使用该类提供的方法进行日志打印，
 * 方便动态的设置调试模式
 * 方便通过统一的tag进行查询
 */

public class LogUtils {

    public static final String TAG = "it_chat";

    private static boolean isDebug() {
        if (ChatManager.getEnvironment() == null) {
            if (!BuildConfig.DEBUG) {
                return false;
            } else {
                return true;
            }
        } else {
            if (ChatManager.getEnvironment() == IAppEnvironment.Environment.RELEASE) {
                return false;
            } else {
                return true;
            }
        }
    }

    public static void eDebug(String msg){
        if (isDebug()) {
            Log.e(TAG, msg);
        }
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
    }

    public static void eLongDebug(String msg){
        if (isDebug()) {
            if (msg.length() > 4000) {
                for (int i = 0; i < msg.length(); i += 4000) {
                    if (i + 4000 < msg.length())
                        eDebug("getJsonByData：" + msg.substring(i, i + 4000));
                    else
                        eDebug("getJsonByData：" + msg.substring(i, msg.length()));
                }
            } else
                eDebug("getJsonByData：" + msg);
        }
    }

    public static void eLong(String msg) {
        if (msg.length() > 4000) {
            for (int i = 0; i < msg.length(); i += 4000) {
                if (i + 4000 < msg.length())
                    e("getJsonByData：" + msg.substring(i, i + 4000));
                else
                    e("getJsonByData：" + msg.substring(i, msg.length()));
            }
        } else
            e("getJsonByData：" + msg);
    }

}
