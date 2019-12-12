package com.innotech.qrpc;

public class IMReport {

    public static IReport iReport;

    private static class Holder {
        private static IMReport imReport = new IMReport();
    }

    private IMReport() {
    }

    public static IMReport getInstance() {
        return Holder.imReport;
    }

    public interface IReport {

        // error格式为json字符串
        void reportIMError(String error);
    }

    public static void registerIReport(IReport iReport) {
        IMReport.iReport = iReport;
    }

    public void report(String error) {
        if (IMReport.iReport == null) {
            LogUtils.e("IReport is null");
            return;
        }
        IMReport.iReport.reportIMError(error);
    }
}
