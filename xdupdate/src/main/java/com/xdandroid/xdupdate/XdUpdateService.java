package com.xdandroid.xdupdate;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import rx.Observable;
import rx.Subscriber;
import rx.schedulers.Schedulers;

/**
 * Created by XingDa on 2016/04/24.
 */
public class XdUpdateService extends Service {

    protected Notification.Builder builder;
    protected NotificationManager manager;
    protected volatile int fileLength;
    protected volatile int length;
    protected DeleteReceiver deleteReceiver;
    protected File file;
    protected volatile boolean interrupted;

    protected static final int TYPE_FINISHED = 0;
    protected static final int TYPE_DOWNLOADING = 1;

    protected class DeleteReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            interrupted = true;
            handler.sendEmptyMessage(TYPE_FINISHED);
            manager.cancel(2);
            if (file != null && file.exists()) {
                file.delete();
            }
            stopSelf();
        }
    }

    @SuppressLint("HandlerLeak")
    protected Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (hasMessages(TYPE_DOWNLOADING)) {
                removeMessages(TYPE_DOWNLOADING);
            }
            if (hasMessages(TYPE_FINISHED)) {
                removeMessages(TYPE_FINISHED);
            }
            switch (msg.what) {
                case TYPE_DOWNLOADING:
                    if (interrupted) {
                        manager.cancel(2);
                    } else {
                        manager.notify(2, builder.setContentText(XdUpdateUtils.formatToMegaBytes(length)+"M/"+ XdUpdateUtils.formatToMegaBytes(fileLength)+"M").setProgress(fileLength, length, false).getNotification());
                        sendEmptyMessageDelayed(TYPE_DOWNLOADING, 512);
                    }
                    break;
                case TYPE_FINISHED:
                    manager.cancel(2);
                    break;
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final XdUpdateBean xdUpdateBean = (XdUpdateBean) intent.getSerializableExtra("xdUpdateBean");
        int iconResId = intent.getIntExtra("appIcon", 0);
        if (xdUpdateBean == null || iconResId == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        deleteReceiver = new DeleteReceiver();
        getApplicationContext().registerReceiver(deleteReceiver,new IntentFilter("com.xdandroid.xdupdate.DeleteUpdate"));
        builder = new Notification.Builder(XdUpdateService.this)
                .setProgress(0, 0, false)
                .setAutoCancel(false)
                .setTicker(XdUpdateUtils.getApplicationName(getApplicationContext())+ xdUpdateBean.getVersionName()+ XdConstants.getDownloadingText())
                .setSmallIcon(iconResId)
                .setContentTitle(XdUpdateUtils.getApplicationName(getApplicationContext())+ xdUpdateBean.getVersionName()+ XdConstants.getDownloadingText() +"...")
                .setContentText("")
                .setDeleteIntent(PendingIntent.getBroadcast(getApplicationContext(),3,new Intent("com.xdandroid.xdupdate.DeleteUpdate"),PendingIntent.FLAG_CANCEL_CURRENT));
        manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Observable.create(new Observable.OnSubscribe<Response>() {
            @Override
            public void call(Subscriber<? super Response> subscriber) {
                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder().url(xdUpdateBean.getUrl()).build();
                Response response;
                try {
                    response = client.newCall(request).execute();
                    if (response.isSuccessful()) {
                        subscriber.onNext(response);
                    } else {
                        subscriber.onError(new IOException(response.code() + " : " + response.body().string()));
                    }
                } catch (Throwable e) {
                    subscriber.onError(e);
                }
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(new Subscriber<Response>() {
                    @Override
                    public void onCompleted() {

                    }

                    @Override
                    public void onError(Throwable e) {
                        if (XdConstants.isDebugMode()) e.printStackTrace(System.err);
                    }

                    @Override
                    public void onNext(Response response) {
                        InputStream is = null;
                        FileOutputStream fos = null;
                        try {
                            is = response.body().byteStream();
                            fileLength = (int) response.body().contentLength();
                            file = new File(getExternalCacheDir(),"update.apk");
                            if (file.exists()) file.delete();
                            fos = new FileOutputStream(file);
                            byte[] buffer = new byte[8192];
                            int hasRead;
                            handler.sendEmptyMessage(TYPE_DOWNLOADING);
                            interrupted = false;
                            while ((hasRead = is.read(buffer)) > 0) {
                                if (interrupted) return;
                                fos.write(buffer, 0 , hasRead);
                                length = length + hasRead;
                            }
                            handler.sendEmptyMessage(TYPE_FINISHED);
                            length = 0;
                            if (file.exists()) {
                                if (XdUpdateUtils.getMd5ByFile(file).equalsIgnoreCase(xdUpdateBean.getMd5())) {
                                    Uri uri = Uri.fromFile(file);
                                    Intent intent = new Intent(Intent.ACTION_VIEW);
                                    intent.setDataAndType(uri, "application/vnd.android.package-archive");
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    startActivity(intent);
                                } else {
                                    file.delete();
                                }
                            }
                        } catch (Throwable e) {
                            if (XdConstants.isDebugMode()) e.printStackTrace(System.err);
                            sendBroadcast(new Intent("com.xdandroid.xdupdate.DeleteUpdate"));
                        } finally {
                            XdUpdateUtils.closeQuietly(fos);
                            XdUpdateUtils.closeQuietly(is);
                            stopSelf();
                        }
                    }
                });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (deleteReceiver != null) {
            getApplicationContext().unregisterReceiver(deleteReceiver);
        }
    }
}
