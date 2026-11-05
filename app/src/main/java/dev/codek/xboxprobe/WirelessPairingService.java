package dev.codek.xboxprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** User-started, bounded notification input while Android's pairing dialog stays visible. */
public final class WirelessPairingService extends Service {
    static final String CHANNEL="wireless-pairing", BEGIN="begin_notification_pairing";
    static final String CALLBACK="ready_callback", RESULT_KEY="pairing_port_code";
    private static final String OWNER="pairing_request_owner";
    private static final String REPLY="dev.codek.xboxprobe.PAIR_REPLY", CANCEL="dev.codek.xboxprobe.PAIR_CANCEL";
    private static final int NOTIFICATION_ID=760;
    private static final long WAIT_MS=300_000, IO_MS=30_000;
    private static volatile WirelessPairingService live;
    private static volatile String lastStatus="";
    private static final PairingStartOwnership starts=new PairingStartOwnership();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private volatile Session session;
    private int lastStartId;

    private static final class Session {
        final String id=UUID.randomUUID().toString();
        final PairingStartOwnership.Request owner;
        final WirelessSetupGate.Lease lease;
        final long expires=SystemClock.elapsedRealtime()+WAIT_MS;
        volatile boolean cancelled;
        boolean running,processing;
        WirelessAdbClient client;
        PairingNotificationInput input;
        PendingIntent reply,cancel;
        Future<?> deadline;
        Session(WirelessSetupGate.Lease lease,PairingStartOwnership.Request owner){this.lease=lease;this.owner=owner;}
        synchronized void cancelResources(){
            cancelled=true;lease.cancel();
            if(deadline!=null)deadline.cancel(false);
            if(client!=null)client.close();
            if(!running&&input!=null)input.close();
        }
        void revokeActions(){if(reply!=null)reply.cancel();if(cancel!=null)cancel.cancel();}
    }

    static void ensureChannel(Context context){
        NotificationChannel channel=new NotificationChannel(CHANNEL,"무선 디버깅 페어링",NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("설정의 페어링 창을 유지하면서 포트와 코드를 입력합니다.");
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    static boolean notificationsVisible(Context context){
        ensureChannel(context);NotificationManager manager=context.getSystemService(NotificationManager.class);
        NotificationChannel channel=manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled()&&channel!=null&&channel.getImportance()!=NotificationManager.IMPORTANCE_NONE;
    }
    static boolean isActive(){return starts.hasRequest();}
    static String status(){return lastStatus;}
    static PairingStartOwnership.Request start(Context context,ResultReceiver callback){
        PairingStartOwnership.Request request=starts.begin();
        try{context.startForegroundService(new Intent(context,WirelessPairingService.class).setAction(BEGIN)
                .putExtra(OWNER,request.id).putExtra(CALLBACK,callback));}
        catch(RuntimeException failure){starts.end(request);throw failure;}
        return request;
    }
    static PairingStartOwnership.Request restoreRequest(String ownerId){return starts.find(ownerId);}
    static void cancelFromApp(PairingStartOwnership.Request request){
        if(!starts.end(request))return;
        WirelessPairingService service=live;Session selected=service==null?null:service.session;
        if(selected!=null&&selected.owner==request)service.finish(selected,"알림 페어링을 취소했습니다.");
    }
    static void cancelFromApp(){cancelFromApp(starts.current());}
    static void acceptNotification(Intent intent){
        WirelessPairingService service=live;if(service==null||intent==null)return;
        Session selected=service.session;if(selected==null||selected.cancelled)return;
        if(CANCEL.equals(intent.getAction())&&service.address(selected,"cancel").equals(intent.getData())){
            service.finish(selected,"알림 페어링을 취소했습니다.");return;
        }
        if(!REPLY.equals(intent.getAction())||!service.address(selected,"reply").equals(intent.getData())||selected.processing)return;
        if(SystemClock.elapsedRealtime()>=selected.expires){service.finish(selected,"입력 대기 시간이 끝났습니다. 앱에서 알림 페어링을 다시 시작하세요.");return;}
        Bundle results=RemoteInput.getResultsFromIntent(intent);
        if(results==null)return;
        PairingNotificationInput input;
        try{input=PairingNotificationInput.parse(results.getCharSequence(RESULT_KEY));}
        catch(IllegalArgumentException error){service.show(selected,"입력 형식: 페어링 포트 + 공백 + 6자리 코드. 예: 37123 001234",true);return;}
        finally{results.remove(RESULT_KEY);intent.setClipData(null);}
        service.pair(selected,input);
    }

    @Override public void onCreate(){super.onCreate();live=this;ensureChannel(this);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        lastStartId=startId;
        ResultReceiver callback=intent==null?null:intent.getParcelableExtra(CALLBACK);
        if(intent==null||!BEGIN.equals(intent.getAction())){if(session==null)stopSelfResult(startId);return START_NOT_STICKY;}
        PairingStartOwnership.Request owner=starts.claim(intent.getStringExtra(OWNER));
        if(owner==null){
            ready(callback,false,"페어링 시작 요청이 취소되었습니다.");if(session==null)stopSelfResult(startId);return START_NOT_STICKY;
        }
        if(session!=null){starts.end(owner);ready(callback,false,"알림 페어링이 이미 대기 중입니다.");return START_NOT_STICKY;}
        if(!notificationsVisible(this)){starts.end(owner);ready(callback,false,"앱 알림과 ‘무선 디버깅 페어링’ 알림 채널을 허용해 주세요.");stopSelfResult(startId);return START_NOT_STICKY;}
        WirelessSetupGate.Lease lease=WirelessSetupGate.SHARED.tryAcquire();
        if(lease==null){starts.end(owner);ready(callback,false,"다른 연결 작업을 정리 중입니다. 잠시 뒤 다시 시도하세요.");stopSelfResult(startId);return START_NOT_STICKY;}
        Session selected=new Session(lease,owner);
        if(owner.isClosed()){selected.cancelResources();ready(callback,false,"페어링 시작 요청이 취소되었습니다.");stopSelfResult(startId);return START_NOT_STICKY;}
        session=selected;
        lastStatus="설정에서 페어링 코드 창을 연 뒤, 알림의 ‘포트·코드 입력’을 누르세요.";
        try{
            Notification waiting=notification(selected,lastStatus,true,true);
            if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION_ID,waiting,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION_ID,waiting);
        }catch(RuntimeException failure){session=null;starts.end(owner);selected.cancelResources();selected.revokeActions();ready(callback,false,"페어링 알림을 시작하지 못했습니다. 알림 권한을 확인하세요.");stopSelfResult(startId);return START_NOT_STICKY;}
        main.postDelayed(()->{if(session==selected&&!selected.processing)finish(selected,"입력 대기 시간이 끝났습니다. 앱에서 다시 시작하세요.");},WAIT_MS);
        ready(callback,true,lastStatus);return START_NOT_STICKY;
    }
    private static void ready(ResultReceiver callback,boolean success,String message){if(callback!=null){Bundle values=new Bundle();values.putString("message",message);callback.send(success?1:0,values);}}
    private Uri address(Session selected,String action){return new Uri.Builder().scheme("controllerbridge").authority("pairing").appendPath(selected.id).appendPath(action).build();}
    private Notification notification(Session selected,String message,boolean waiting,boolean ongoing){
        PendingIntent open=PendingIntent.getActivity(this,761,new Intent(this,WirelessDebuggingActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("Controller Bridge · 페어링").setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(open).setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_SECRET).setCategory(Notification.CATEGORY_STATUS).setAllowSystemGeneratedContextualActions(false);
        if(waiting){
            if(selected.reply==null)selected.reply=PendingIntent.getBroadcast(this,0,new Intent(this,PairingReplyReceiver.class).setAction(REPLY).setData(address(selected,"reply")),PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0));
            RemoteInput input=new RemoteInput.Builder(RESULT_KEY).setLabel("포트 코드 · 예: 37123 001234").setAllowFreeFormInput(true).build();
            Notification.Action.Builder action=new Notification.Action.Builder(android.R.drawable.ic_menu_edit,"포트·코드 입력",selected.reply)
                    .addRemoteInput(input).setAllowGeneratedReplies(false).setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY);
            if(Build.VERSION.SDK_INT>=31)action.setAuthenticationRequired(true);
            builder.addAction(action.build());
        }
        if(ongoing){
            if(selected.cancel==null)selected.cancel=PendingIntent.getBroadcast(this,0,new Intent(this,PairingReplyReceiver.class).setAction(CANCEL).setData(address(selected,"cancel")),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"취소",selected.cancel).build());
        }
        return builder.build();
    }
    private void show(Session selected,String message,boolean waiting){
        if(session!=selected||selected.cancelled)return;lastStatus=message;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,notification(selected,message,waiting,true));
    }
    private void pair(Session selected,PairingNotificationInput input){
        if(session!=selected||selected.cancelled||selected.processing){input.close();return;}
        selected.processing=true;selected.input=input;
        if(selected.reply!=null)selected.reply.cancel();
        // A fresh notification has no submitted text or RemoteInput history.
        show(selected,"페어링 중입니다. 설정의 코드 창을 열어 둬 주세요.",false);
        selected.deadline=timer.schedule(()->{
            selected.cancelResources();
            main.post(()->{if(session==selected)finish(selected,"페어링 시간이 초과되었습니다. 새 코드로 다시 시도하세요.");});
        },IO_MS,TimeUnit.MILLISECONDS);
        try{worker.submit(()->{
            synchronized(selected){
                if(selected.cancelled||!selected.lease.enter()){input.close();return;}
                selected.running=true;
            }
            String result;
            try{
                WirelessAdbIdentity identity=WirelessAdbIdentity.loadOrCreate();
                synchronized(selected){if(selected.cancelled)throw new IOException("Cancelled");selected.client=new WirelessAdbClient(identity);}
                selected.client.pair(input.port,input.code);
                result="페어링 완료. 앱으로 돌아가 무선 디버깅 첫 화면의 연결 포트를 입력하세요.";
            }catch(LinkageError failure){result="이 기기에서 페어링 라이브러리를 불러오지 못했습니다.";}
            catch(Exception failure){result=WirelessAdbFailure.userMessage(failure);android.util.Log.w("BridgeWireless",result);}
            finally{
                synchronized(selected){if(selected.client!=null)selected.client.close();input.close();selected.lease.finish();}
            }
            String message=result;main.post(()->{if(session==selected&&!selected.cancelled)finish(selected,message);});
        });}catch(RuntimeException failure){finish(selected,"페어링 작업을 시작하지 못했습니다. 다시 시도하세요.");}
    }
    private void finish(Session selected,String message){
        if(session!=selected)return;
        starts.end(selected.owner);selected.cancelResources();selected.revokeActions();session=null;lastStatus=message;
        stopForeground(STOP_FOREGROUND_DETACH);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,notification(selected,message,false,false));
        stopSelfResult(lastStartId);
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        Session selected=session;session=null;
        if(selected!=null){starts.end(selected.owner);selected.cancelResources();selected.revokeActions();stopForeground(STOP_FOREGROUND_REMOVE);lastStatus="페어링이 종료되었습니다. 앱에서 다시 시작하세요.";}
        if(live==this)live=null;
        main.removeCallbacksAndMessages(null);worker.shutdownNow();timer.shutdownNow();super.onDestroy();
    }
}
