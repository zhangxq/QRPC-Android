package com.innotech.qrpc;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CommonUtils {

    //通过PackageInfo得到的想要启动的应用的包名
    private static PackageInfo getPackageInfo(Context context) {
        try {
            //通过PackageManager可以得到PackageInfo
            PackageManager pManager = context.getPackageManager();
            return pManager.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_CONFIGURATIONS);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    //获取版本名
    public static String getVersionName(Context context) {
        PackageInfo info = getPackageInfo(context);
        if (info != null)
            return info.versionName;
        else
            return "0.0.0";
    }

    /**
     * 根据本地原图url生成尺寸列表
     */
    public static String[] calcResizesForImg(String url) {
        int maxSize = 1600;
        int minSize = 120;
        int[] size = CommonUtils.getImageWidthHeight(url);
        int width = size[0];
        int height = size[1];
        // 图片小于200K，不生成缩略图
        File file = new File(url);
        if (file.length() / 1024 < 200)
            return new String[]{width + "_" + height};
        // 生成缩略图
        int largerSize = width;
        if (height > largerSize)
            largerSize = height;
        if (largerSize > maxSize) {
            double ratio1 = maxSize * 1.0 / largerSize;
            double ratio2 = minSize * 1.0 / largerSize;
            return new String[]{width + "_" + height,
                    (int) (width * ratio1) + "_" + (int) (height * ratio1),
                    (int) (width * ratio2) + "_" + (int) (height * ratio2)};
        } else if (largerSize >= minSize && largerSize <= maxSize) {
            double ratio2 = minSize * 1.0 / largerSize;
            return new String[]{width + "_" + height,
                    (int) (width * ratio2) + "_" + (int) (height * ratio2)};
        } else {
            return new String[]{width + "_" + height};
        }
    }

    /**
     * int to byte[] 支持 1或者 4 个字节
     *
     * @param i
     * @param len
     * @return
     */
    public static byte[] big_intToByte(int i, int len) {
        byte[] abyte = new byte[len];
        if (len == 1) {
            abyte[0] = (byte) (0xff & i);
        } else if (len == 2) {
            abyte[0] = (byte) ((i >>> 8) & 0xff);
            abyte[1] = (byte) (i & 0xff);
        } else {
            abyte[0] = (byte) ((i >>> 24) & 0xff);
            abyte[1] = (byte) ((i >>> 16) & 0xff);
            abyte[2] = (byte) ((i >>> 8) & 0xff);
            abyte[3] = (byte) (i & 0xff);
        }
        return abyte;
    }

    public static int big_bytesToInt(byte[] bytes) {
        int addr = 0;
        if (bytes.length == 1) {
            addr = bytes[0] & 0xFF;
        } else if (bytes.length == 2) {
            addr = bytes[0] & 0xFF;
            addr = (addr << 8) | (bytes[1] & 0xff);
        } else {
            addr = bytes[0] & 0xFF;
            addr = (addr << 8) | (bytes[1] & 0xff);
            addr = (addr << 8) | (bytes[2] & 0xff);
            addr = (addr << 8) | (bytes[3] & 0xff);
        }
        return addr;
    }

    /**
     * 将字节数组转为long<br>
     * 如果input为null,或offset指定的剩余数组长度不足8字节则抛出异常
     *
     * @param input
     * @param offset       起始偏移量
     * @param littleEndian 输入数组是否小端模式
     * @return
     */
    public static long longFrom8Bytes(byte[] input, int offset, boolean littleEndian) {
        long value = 0;
        // 循环读取每个字节通过移位运算完成long的8个字节拼装
        for (int count = 0; count < 8; ++count) {
            int shift = (littleEndian ? count : (7 - count)) << 3;
            value |= ((long) 0xff << shift) & ((long) input[offset + count] << shift);
        }
        return value;
    }


    public byte[] longToByteArray(long l, boolean littleEndian) {
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            int i1 = (littleEndian ? i : (7 - i)) << 3;
            bytes[i] = (byte) ((l >> i1) & 0xff);
        }
        return bytes;
    }

    /**
     * 是否主进程
     *
     * @param context
     * @return
     */
    public static boolean isMainProcess(Context context) {
        if(context != null){
            String mainProcessName = context.getPackageName();
            int myPid = android.os.Process.myPid();
            ActivityManager am = ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE));
            List<ActivityManager.RunningAppProcessInfo> processInfos = am.getRunningAppProcesses();
            if (processInfos != null) {
                for (ActivityManager.RunningAppProcessInfo info : processInfos) {
                    if (info.pid == myPid && mainProcessName.equals(info.processName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * android在不加载图片的前提下获得图片的宽高
     */
    public static int[] getImageWidthHeight(String path) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        /*
         * 最关键在此，把options.inJustDecodeBounds = true;
         * 这里再decodeFile()，返回的bitmap为空，但此时调用options.outHeight时，已经包含了图片的高了
         */
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options); // 此时返回的bitmap为null
        // options.outHeight为原始图片的高
        return new int[]{options.outWidth, options.outHeight};
    }

    /**
     * 获取屏幕宽度
     */
    public static int getScreenWidth() {
        Resources resources = Resources.getSystem();
        DisplayMetrics dm = resources.getDisplayMetrics();
        return dm.widthPixels;
    }

    /**
     * 判断应用是否在后台
     *
     * @param context：上下文
     * @return boolean
     */
    private static boolean isBackground(Context context) {
        ActivityManager activityManager = (ActivityManager) context
                .getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> appProcesses = activityManager
                .getRunningAppProcesses();
        if (appProcesses == null) {
            LogUtils.e("isBackground method getRunningAppProcesses is null");
            return true;
        }
        for (ActivityManager.RunningAppProcessInfo appProcess : appProcesses) {
            if (appProcess.processName.equals(context.getPackageName())) {
                if (appProcess.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    Log.i(context.getPackageName(), "后台"
                            + appProcess.processName);
                    return true;
                } else {
                    Log.i(context.getPackageName(), "前台"
                            + appProcess.processName);
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 判断是否适合打开服务
     * 1、服务是否已存在
     * 2、8.0及以上版本需要判断是否在后台
     *
     * @return boolean
     */
    public static boolean isCanRunService(Context context, String serviceName) {
        boolean isCan = false;
        if (!isServiceRunning(context, serviceName)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {//8.0以下版本
                isCan = true;
            } else if (!isBackground(context)) {//8.0及以上版本，判断是否在后台
                isCan = true;
            }
        }
        return isCan;
    }

    /**
     * 判断服务是否开启
     *
     * @return boolean
     */
    private static boolean isServiceRunning(Context context, String ServiceName) {
        if (("").equals(ServiceName) || ServiceName == null)
            return false;
        try {
            ActivityManager myManager = (ActivityManager) context
                    .getSystemService(Context.ACTIVITY_SERVICE);
            ArrayList<ActivityManager.RunningServiceInfo> runningService = (ArrayList<ActivityManager.RunningServiceInfo>) myManager
                    .getRunningServices(30);
            if (runningService != null && runningService.size() > 0) {
                for (int i = 0; i < runningService.size(); i++) {
                    if (runningService.get(i).service.getClassName().toString()
                            .equals(ServiceName)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // no content
        }
        return false;
    }
}
