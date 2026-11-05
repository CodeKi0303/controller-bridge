package dev.codek.xboxprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import rikka.shizuku.Shizuku;

/** Shared, user-mediated connection preparation. Readiness never resumes a caller implicitly. */
public final class WirelessDebuggingActivity extends Activity {
    private static final AtomicInteger NEXT_SHIZUKU_REQUEST=new AtomicInteger(7900);
    private static final int BG=0xff0c121b,CARD=0xff17222f,TEXT=0xffedf4fc,
            MUTED=0xff9aafc4,ACCENT=0xff78dfbd,LINE=0xff304354;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private static final class Work {
        final WirelessSetupGate.Lease lease;
        Work(WirelessSetupGate.Lease lease){this.lease=lease;}
        volatile WirelessAdbClient client;
        volatile String nonce;
        volatile boolean cancelled;
        volatile boolean awaitingHandoff;
        volatile Future<?> task,deadline;
        synchronized void cancel(){
            cancelled=true;
            lease.cancel();
            if(deadline!=null)deadline.cancel(false);
            if(task!=null)task.cancel(true);
            if(client!=null)client.close();
            if(nonce!=null)StandaloneBackend.cancelBootstrap(nonce);
        }
    }
    private volatile Work active;
    private volatile boolean destroyed;
    private boolean closing;
    private EditText pairPort,code,connectPort;
    private TextView status,readiness,modeDescription;
    private Button pair,start,forget,stop,cancel,notificationPair,continueAction,shizukuPermission;
    private LinearLayout standalonePrimary,standaloneHelp,shizukuPrimary,shizukuHelp;
    private ConnectionMode mode;
    private long continuationId;
    private String continuationLabel;
    private PairingStartOwnership.Request notificationOwner;
    private boolean guideExpanded,advancedExpanded;
    private boolean notificationPermissionPending;
    private boolean shizukuPermissionPending;
    private final int shizukuPermissionRequest=NEXT_SHIZUKU_REQUEST.incrementAndGet();
    private boolean foreground;
    private long notificationRequest;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionResult=(request,result)->{
        if(request!=shizukuPermissionRequest||!shizukuPermissionPending||destroyed||closing)return;
        shizukuPermissionPending=false;
        status.setText(result==PackageManager.PERMISSION_GRANTED
                ?"입력 권한을 허용했습니다. 연결 준비 상태를 확인하세요."
                :"입력 권한이 허용되지 않았습니다. 계속하려면 아래에서 권한을 허용하세요.");
        refreshReadiness();
    };
    private String shownNotificationStatus="";
    private final Runnable refreshNotification=new Runnable(){@Override public void run(){
        if(destroyed||closing||!foreground)return;
        updateMode();
        if(mode==ConnectionMode.STANDALONE){
            String message=WirelessPairingService.status();
            if(active==null&&!message.isEmpty()&&!message.equals(shownNotificationStatus)){shownNotificationStatus=message;status.setText(message);}
        }
        refreshReadiness();ui.postDelayed(this,400);
    }};
    private static final String PREFS="wireless_adb_setup";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        continuationId=ConnectionSetupContract.requestId(getIntent());
        continuationLabel=ConnectionSetupContract.continuationLabel(getIntent());
        setResult(RESULT_CANCELED,ConnectionSetupContract.cancelledResult(continuationId));
        mode=ConnectionMode.get(this);
        if(saved!=null)notificationOwner=WirelessPairingService.restoreRequest(saved.getString("notificationOwner"));
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResult);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        LinearLayout help=new LinearLayout(this);help.setOrientation(LinearLayout.VERTICAL);
        AdaptiveScreenLayout screen=new AdaptiveScreenLayout(this,page,help);screen.setBackgroundColor(BG);
        setContentView(screen);
        button(page,"‹ 돌아가기",v->finish());
        title(page,"연결 준비",27);
        modeDescription=text(page,"",14);

        LinearLayout stateCard=card(page);
        readiness=text(stateCard,"",14);readiness.setTypeface(null,Typeface.BOLD);
        status=text(stateCard,initialStatus(),15);
        shownNotificationStatus=WirelessPairingService.status();
        if(mode==ConnectionMode.STANDALONE&&WirelessPairingService.isActive()&&!shownNotificationStatus.isEmpty())
            status.setText(shownNotificationStatus);
        status.setTextColor(TEXT);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        // Explicit management can stop the displayed global task; closing this screen only cancels its own request.
        cancel=button(stateCard,"진행 중인 연결 작업 취소",v->{notificationRequest++;notificationPermissionPending=false;WirelessPairingService.cancelFromApp();notificationOwner=null;cancelOperation();status.setText("작업을 취소했습니다.");});cancel.setVisibility(View.GONE);
        continueAction=button(stateCard,continuationLabel,v->continueRequestedAction());primary(continueAction);
        continueAction.setVisibility(continuationId>0&&!continuationLabel.isEmpty()?View.VISIBLE:View.GONE);
        if(continuationId>0&&!continuationLabel.isEmpty())text(stateCard,"준비가 끝나면 위 버튼으로 계속하세요. 돌아가기만 하면 요청은 취소됩니다.",12);

        standalonePrimary=column(page);standaloneHelp=column(help);
        shizukuPrimary=column(page);shizukuHelp=column(help);

        LinearLayout connectCard=card(standalonePrimary);
        title(connectCard,"연결 포트",18);
        text(connectCard,"무선 디버깅 기본 화면의 ‘IP 주소 및 포트’에서 마지막 숫자만 입력하세요.",14);
        connectPort=number(connectCard,"예: 42855",false);
        connectPort.setContentDescription("연결 포트. 무선 디버깅 기본 화면의 IP 주소 및 포트에서 마지막 숫자");
        String rememberedPort=getSharedPreferences(PREFS,MODE_PRIVATE).getString("connectPort","");
        connectPort.setText(rememberedPort);
        start=button(connectCard,"입력 서비스 시작",v->startHelper());primary(start);
        button(connectCard,"무선 디버깅 설정 열기",v->openDeveloperSettings());
        text(connectCard,"무선 디버깅을 다시 켜면 연결 포트가 바뀔 수 있습니다.",12);

        guideExpanded=saved!=null?saved.getBoolean("guideExpanded"):rememberedPort.isEmpty();
        Button guideToggle=button(standaloneHelp,"",null);
        LinearLayout guide=column(standaloneHelp);
        guide.setVisibility(guideExpanded?View.VISIBLE:View.GONE);
        guideToggle.setText(guideExpanded?"처음 연결하는 방법  ▴":"처음 연결하는 방법  ▾");
        guideToggle.setOnClickListener(v->{guideExpanded=!guideExpanded;guide.setVisibility(guideExpanded?View.VISIBLE:View.GONE);guideToggle.setText(guideExpanded?"처음 연결하는 방법  ▴":"처음 연결하는 방법  ▾");});
        guideStep(guide,1,"무선 디버깅 켜기","Wi-Fi에 연결한 뒤 개발자 옵션에서 무선 디버깅을 켜세요.");
        LinearLayout pairingCard=guideStep(guide,2,"알림으로 페어링하기","아래 버튼을 누른 뒤 설정에서 ‘코드로 기기 페어링’을 여세요. 코드 창을 유지한 채 알림의 ‘포트·코드 입력’에 답장합니다.");
        notificationPair=button(pairingCard,"알림에서 페어링 시작",v->requestNotificationPairing());
        primary(notificationPair);
        text(pairingCard,"페어링 포트 + 공백 + 6자리 코드. 알림 본문 대신 ‘포트·코드 입력’을 누르세요.",13);
        LinearLayout finalStep=guideStep(guide,3,"연결 포트로 시작하기","페어링 완료 후 무선 디버깅 기본 화면으로 돌아오세요. 그 화면의 포트를 앱의 연결 포트 입력란에 넣고 서비스를 시작합니다.");
        button(finalStep,"연결 포트 입력으로 이동",v->{connectPort.requestFocus();screen.reveal(connectPort);});

        advancedExpanded=saved!=null&&saved.getBoolean("advancedExpanded");
        Button advancedToggle=button(standaloneHelp,"",null);
        LinearLayout advanced=card(standaloneHelp);advanced.setVisibility(advancedExpanded?View.VISIBLE:View.GONE);
        advancedToggle.setText(advancedExpanded?"연결 관리 · 다른 입력 방법  ▴":"연결 관리 · 다른 입력 방법  ▾");
        advancedToggle.setOnClickListener(v->{advancedExpanded=!advancedExpanded;advanced.setVisibility(advancedExpanded?View.VISIBLE:View.GONE);advancedToggle.setText(advancedExpanded?"연결 관리 · 다른 입력 방법  ▴":"연결 관리 · 다른 입력 방법  ▾");});
        button(advanced,"페어링 알림 설정",v->openNotificationSettings());
        title(advanced,"분할 화면에서 직접 입력",17);
        text(advanced,"설정과 이 앱을 분할 화면으로 함께 띄워 페어링 코드 창을 열어 두세요.",13);
        text(advanced,"페어링 포트",13);pairPort=number(advanced,"코드 창의 포트",false);pairPort.setContentDescription("페어링 코드 창의 포트");
        text(advanced,"6자리 페어링 코드",13);code=number(advanced,"6자리 코드",true);code.setSaveEnabled(false);code.setContentDescription("6자리 페어링 코드");
        pair=button(advanced,"직접 입력한 코드로 페어링",v->pair());
        title(advanced,"서비스 관리",17);
        stop=button(advanced,"입력 서비스 종료",v->stopHelper());
        forget=button(advanced,"이 앱의 페어링 초기화",v->{AlertDialog confirmation=new AlertDialog.Builder(this)
                .setTitle("ADB 인증키 삭제")
                .setMessage("입력 서비스를 종료하고 인증키를 삭제합니다. 다음 시작 때 다시 페어링해야 합니다. 설정의 무선 디버깅 → 페어링된 기기에서도 ControllerBridge 항목을 삭제해 주세요.")
                .setNegativeButton("취소",null).setPositiveButton("삭제",(d,w)->forgetIdentity()).create();
            confirmation.show();AdaptiveDialogWindow.attach(this,confirmation,560);
        });
        text(standaloneHelp,"재부팅·앱 업데이트 후에는 입력 서비스를 다시 시작하세요.",12);

        LinearLayout shizukuCard=card(shizukuPrimary);
        title(shizukuCard,"Shizuku 연결",18);
        text(shizukuCard,"Shizuku 앱에서 무선 디버깅 방식으로 서버를 시작한 뒤 Controller Bridge의 입력 권한을 허용하세요.",14);
        button(shizukuCard,"Shizuku 앱 열기",v->openShizuku());
        shizukuPermission=button(shizukuCard,"입력 권한 허용",v->requestShizukuPermission());primary(shizukuPermission);
        LinearLayout shizukuGuide=card(shizukuHelp);
        title(shizukuGuide,"준비 순서",18);
        text(shizukuGuide,"1  Shizuku가 없다면 공식 안내에서 설치하세요.\n2  Shizuku 앱에서 무선 디버깅 방식으로 서버를 시작하세요.\n3  이 화면으로 돌아와 입력 권한을 허용하세요.",14);
        button(shizukuGuide,"Shizuku 설치·시작 안내",v->openShizukuGuide());
        text(shizukuGuide,"재부팅 후에는 Shizuku를 다시 시작해야 할 수 있습니다. 연결 방식은 앱 설정에서 변경할 수 있습니다.",13);
        showMode();refreshReadiness();
    }
    private String initialStatus(){
        if(connectionReady())return continuationId>0&&!continuationLabel.isEmpty()
                ?"준비되었습니다. 계속 버튼을 누르면 요청한 작업으로 돌아갑니다.":"입력 서비스를 사용할 준비가 되었습니다.";
        return mode==ConnectionMode.STANDALONE?"이미 페어링했다면 연결 포트를 입력하고 시작하세요."
                :"Shizuku를 시작하고 이 앱의 입력 권한을 허용하세요.";
    }
    private void showMode(){
        boolean standalone=mode==ConnectionMode.STANDALONE;
        standalonePrimary.setVisibility(standalone?View.VISIBLE:View.GONE);
        standaloneHelp.setVisibility(standalone?View.VISIBLE:View.GONE);
        shizukuPrimary.setVisibility(standalone?View.GONE:View.VISIBLE);
        shizukuHelp.setVisibility(standalone?View.GONE:View.VISIBLE);
        modeDescription.setText(standalone?"연결 방식: 앱에서 직접 연결 · 무선 디버깅"
                :"연결 방식: Shizuku · 별도 앱의 서버와 권한 사용");
    }
    private void updateMode(){
        ConnectionMode selected=ConnectionMode.get(this);
        if(selected==mode)return;
        cancelOwnedWork();mode=selected;showMode();status.setText(initialStatus());
    }
    private boolean connectionReady(){return ConnectionSetupContract.isReady(this);}
    private boolean connectionBusy(){
        return active!=null||(mode==ConnectionMode.STANDALONE&&WirelessPairingService.isActive());
    }
    private void refreshReadiness(){
        ConnectionSetupContract.Readiness state=ConnectionSetupContract.readiness(this);
        String label;
        switch(state.state){
            case READY: label="● 입력 서비스 준비됨";break;
            case STANDALONE_START_REQUIRED: label="○ 입력 서비스 시작 전";break;
            case SHIZUKU_START_REQUIRED: label="○ Shizuku 시작 필요";break;
            case SHIZUKU_UPDATE_REQUIRED: label="○ Shizuku 업데이트 필요";break;
            case SHIZUKU_ADB_REQUIRED: label="○ Shizuku를 무선 디버깅 방식으로 시작해 주세요";break;
            case SHIZUKU_PERMISSION_REQUIRED: label="○ 입력 권한 허용 필요";break;
            default: label="○ 연결 상태를 확인하지 못했습니다";break;
        }
        if(!label.contentEquals(readiness.getText()))readiness.setText(label);
        readiness.setTextColor(state.isReady()?ACCENT:MUTED);
        setBusy(connectionBusy());
    }
    private void continueRequestedAction(){
        if(destroyed||closing||continuationId<=0||continuationLabel.isEmpty())return;
        updateMode();
        if(connectionBusy()||!connectionReady()){
            refreshReadiness();status.setText("연결 준비가 끝난 뒤 다시 계속해 주세요.");return;
        }
        ConnectionSetupContract.complete(this,continuationId);
    }
    private void openShizuku(){
        try{
            Intent launch=getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if(launch!=null){startActivity(launch);return;}
            status.setText("Shizuku 앱을 찾지 못했습니다. 공식 안내에서 설치한 뒤 돌아오세요.");
            openShizukuGuide();
        }catch(RuntimeException failure){status.setText("Shizuku 앱을 열어 서버를 시작한 뒤 돌아오세요.");}
    }
    private void openShizukuGuide(){
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://shizuku.rikka.app/guide/setup/")));}
        catch(RuntimeException failure){status.setText("브라우저에서 shizuku.rikka.app의 시작 안내를 확인하세요.");}
    }
    private void requestShizukuPermission(){
        if(destroyed||closing||mode!=ConnectionMode.SHIZUKU||shizukuPermissionPending)return;
        ConnectionSetupContract.Readiness state=ConnectionSetupContract.readiness(this);
        if(state.isReady()){status.setText(initialStatus());refreshReadiness();return;}
        if(state.state!=ConnectionSetupContract.State.SHIZUKU_PERMISSION_REQUIRED){
            status.setText("먼저 Shizuku 앱에서 무선 디버깅 방식으로 서버를 시작하세요.");refreshReadiness();return;
        }
        try{shizukuPermissionPending=true;Shizuku.requestPermission(shizukuPermissionRequest);}
        catch(RuntimeException failure){shizukuPermissionPending=false;status.setText("입력 권한을 요청하지 못했습니다. Shizuku의 실행 상태를 확인하세요.");}
        refreshReadiness();
    }
    private void openDeveloperSettings(){try{startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));}catch(RuntimeException e){status.setText("설정 → 개발자 옵션 → 무선 디버깅을 열어 주세요.");}}
    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);out.putBoolean("guideExpanded",guideExpanded);out.putBoolean("advancedExpanded",advancedExpanded);
        if(notificationOwner!=null)out.putString("notificationOwner",notificationOwner.id);
    }
    private void requestNotificationPairing(){
        if(destroyed||closing||mode!=ConnectionMode.STANDALONE)return;
        if(active!=null){status.setText("진행 중인 연결 작업을 먼저 취소해 주세요.");return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            notificationPermissionPending=true;requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},77);return;
        }
        if(!WirelessPairingService.notificationsVisible(this)){status.setText("알림 설정에서 앱 알림과 ‘무선 디버깅 페어링’ 채널을 허용해 주세요.");openNotificationSettings();return;}
        status.setText("페어링 입력 알림을 준비하고 있습니다…");
        final long request=++notificationRequest;
        try{notificationOwner=WirelessPairingService.start(this,new ResultReceiver(ui){@Override protected void onReceiveResult(int resultCode,Bundle result){
            if(destroyed||closing||isFinishing()||!foreground||request!=notificationRequest)return;
            status.setText(result.getString("message","페어링 알림 상태를 확인하세요."));
            if(resultCode==1)try{startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));}catch(RuntimeException error){status.setText("알림을 준비했습니다. 설정에서 무선 디버깅 → 페어링 코드로 기기 페어링을 여세요.");}
        }});}catch(RuntimeException failure){status.setText("페어링 알림을 시작하지 못했습니다. 앱을 다시 열고 알림 권한을 확인하세요.");}
    }
    private void openNotificationSettings(){
        WirelessPairingService.ensureChannel(this);
        try{startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));}
        catch(RuntimeException error){status.setText("휴대폰 설정 → 애플리케이션 → Controller Bridge → 알림을 허용하세요.");}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(destroyed||closing)return;
        if(request==77&&notificationPermissionPending){notificationPermissionPending=false;
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)requestNotificationPairing();
            else status.setText("알림 입력에는 알림 허용이 필요합니다. 알림 설정에서 허용하거나 분할 화면의 직접 입력을 사용하세요.");
        }
    }
    @Override protected void onResume(){super.onResume();foreground=true;ui.removeCallbacks(refreshNotification);ui.post(refreshNotification);}
    @Override protected void onPause(){foreground=false;notificationRequest++;ui.removeCallbacks(refreshNotification);super.onPause();}
    private void pair() {
        if(destroyed||closing||mode!=ConnectionMode.STANDALONE)return;
        final int port;final byte[] secret;
        try {port=WirelessAdbClient.port(pairPort.getText().toString().trim());secret=WirelessAdbClient.pairingCode(code.getText().toString().trim());}
        catch(IllegalArgumentException error){status.setText(error.getMessage());return;}
        code.setText("");
        submit("페어링 중…",token->{
            try {
                WirelessAdbClient client=client(token);
                client.pair(port,secret);
                return "페어링했습니다. 무선 디버깅 첫 화면의 연결 포트로 입력 서비스를 시작하세요.";
            } finally {java.util.Arrays.fill(secret,(byte)0);}
        });
    }
    private void startHelper() {
        if(destroyed||closing||mode!=ConnectionMode.STANDALONE)return;
        if(StandaloneBackend.isAvailable()){status.setText(initialStatus());refreshReadiness();return;}
        final int port;
        try{port=WirelessAdbClient.port(connectPort.getText().toString().trim());}
        catch(IllegalArgumentException error){status.setText(error.getMessage());return;}
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("connectPort",Integer.toString(port)).apply();
        submit("입력 서비스 시작 중…",token->{
            StandaloneBackend.BootstrapRequest request=StandaloneBackend.prepareBootstrap(this);
            synchronized(token){
                if(!current(token)){StandaloneBackend.cancelBootstrap(request.nonce);throw new IOException("Cancelled");}
                token.nonce=request.nonce;
            }
            try {
                checkCurrent(token);client(token).start(port,request);token.awaitingHandoff=true;
                long until=SystemClock.elapsedRealtime()+12000;
                while(!StandaloneBackend.isAvailable()&&SystemClock.elapsedRealtime()<until){checkCurrent(token);Thread.sleep(50);}
                checkCurrent(token);
                if(!StandaloneBackend.isAvailable())throw WirelessAdbFailure.at(WirelessAdbFailure.Stage.START_HANDOFF,
                        new java.net.SocketTimeoutException());
                return continuationId>0&&!continuationLabel.isEmpty()?"입력 서비스가 준비되었습니다. 계속 버튼으로 요청한 작업을 이어가세요."
                        :"입력 서비스가 준비되었습니다. 돌아가서 사용할 프로필을 선택하세요.";
            } finally {StandaloneBackend.cancelBootstrap(request.nonce);token.nonce=null;}
        });
    }
    private void forgetIdentity() {
        if(!canStopHelper())return;
        submit("인증키 삭제 중…",token->{
            checkCurrent(token);if(StandaloneBackend.hasSessions())return stopInputsFirst();
            StandaloneBackend.stopLauncher();checkCurrent(token);WirelessAdbIdentity.forget();
            return "입력 서비스를 종료하고 인증키를 삭제했습니다. 설정의 페어링된 기기 항목도 삭제해 주세요.";
        });
    }
    private void stopHelper() {
        if(!canStopHelper())return;
        submit("입력 서비스 종료 중…",token->{
            checkCurrent(token);if(StandaloneBackend.hasSessions())return stopInputsFirst();
            StandaloneBackend.stopLauncher();return "입력 서비스를 종료했습니다.";
        });
    }
    private boolean canStopHelper(){if(!StandaloneBackend.hasSessions())return true;status.setText(stopInputsFirst());return false;}
    private static String stopInputsFirst(){return "컨트롤러 화면에서 ‘입력 종료’를 먼저 눌러 주세요. 사용 중인 입력은 여기에서 종료하지 않습니다.";}
    private interface Operation {String run(Work token)throws Exception;}
    private void submit(String message,Operation operation) {
        if(destroyed||closing||mode!=ConnectionMode.STANDALONE)return;
        cancelOperation();WirelessSetupGate.Lease lease=WirelessSetupGate.SHARED.tryAcquire();
        if(lease==null){status.setText("다른 페어링·연결 작업이 진행 중이거나 정리 중입니다. 알림 작업을 마친 뒤 다시 시도하세요.");return;}
        Work token=new Work(lease);active=token;setBusy(true);status.setText(message);
        token.deadline=timer.schedule(()->{
            // SO_TIMEOUT cannot interrupt blocked writes. Close the owned socket
            // on this independent watchdog even if the main thread is busy.
            if(current(token)){
                token.cancel();
                WirelessAdbFailure.Stage stage=token.awaitingHandoff?WirelessAdbFailure.Stage.START_HANDOFF:
                        token.client==null?WirelessAdbFailure.Stage.PREPARATION:token.client.currentStage();
                String timedOut=WirelessAdbFailure.userMessage(WirelessAdbFailure.at(stage,new java.net.SocketTimeoutException()));
                runOnUiThread(()->{if(!destroyed&&active==token){active=null;setBusy(false);status.setText(timedOut);}});
            }
        },30,TimeUnit.SECONDS);
        try{token.task=worker.submit(()->{
            if(!lease.enter())return;
            String result;
            try{result=operation.run(token);}
            catch(LinkageError error){result="이 기기에서 페어링 라이브러리를 불러오지 못했습니다. CPU 형식을 지원하는 최신 APK를 다시 설치해 주세요.";}
            catch(Exception error){result=failure(error);}
            finally{if(token.client!=null)token.client.close();lease.finish();}
            final String displayed=result;
            runOnUiThread(()->{if(current(token)){token.deadline.cancel(false);active=null;setBusy(false);status.setText(displayed);}});
        });}catch(RuntimeException failure){cancelOperation();lease.finish();status.setText("연결 작업을 시작하지 못했습니다. 다시 시도하세요.");}
    }
    private WirelessAdbClient client(Work token)throws Exception {
        checkCurrent(token);WirelessAdbIdentity identity=WirelessAdbIdentity.loadOrCreate();checkCurrent(token);
        WirelessAdbClient client=new WirelessAdbClient(identity);
        synchronized(token){if(!current(token)){client.close();throw new IOException("Cancelled");}token.client=client;}
        return client;
    }
    private boolean current(Work token){return !destroyed&&!closing&&active==token&&!token.cancelled;}
    private void checkCurrent(Work token)throws IOException{if(!current(token)||Thread.currentThread().isInterrupted())throw new IOException("Cancelled");}
    private void cancelOperation(){
        Work token=active;active=null;if(token!=null)token.cancel();
        if(pair!=null)setBusy(false);
    }
    private void setBusy(boolean busy){
        pair.setEnabled(!busy);start.setEnabled(!busy);forget.setEnabled(!busy);stop.setEnabled(!busy);notificationPair.setEnabled(!busy);
        cancel.setEnabled(busy);cancel.setVisibility(busy?View.VISIBLE:View.GONE);
        if(continueAction!=null)continueAction.setEnabled(!closing&&!busy&&connectionReady());
        if(shizukuPermission!=null)shizukuPermission.setEnabled(!closing&&!shizukuPermissionPending
                &&ConnectionSetupContract.readiness(this).state==ConnectionSetupContract.State.SHIZUKU_PERMISSION_REQUIRED);
    }
    private static String failure(Exception error){
        String message=WirelessAdbFailure.userMessage(error);
        // Only fixed stage/advice and a bounded exception kind; never raw errors.
        android.util.Log.w("BridgeWireless",message);
        return message;
    }
    private void cancelOwnedWork(){
        notificationRequest++;notificationPermissionPending=false;shizukuPermissionPending=false;
        WirelessPairingService.cancelFromApp(notificationOwner);notificationOwner=null;cancelOperation();
    }
    @Override public void finish(){
        if(!closing){closing=true;cancelOwnedWork();if(code!=null)code.setText("");ui.removeCallbacksAndMessages(null);}
        super.finish();
    }
    @Override protected void onDestroy(){
        destroyed=true;ui.removeCallbacksAndMessages(null);
        if(isChangingConfigurations())cancelOperation();else cancelOwnedWork();
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResult);
        worker.shutdownNow();timer.shutdownNow();super.onDestroy();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius){GradientDrawable out=new GradientDrawable();out.setColor(color);out.setCornerRadius(dp(radius));return out;}
    private LinearLayout column(LinearLayout parent){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);parent.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;}
    private LinearLayout card(LinearLayout parent){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);view.setPadding(dp(18),dp(12),dp(18),dp(14));view.setBackground(shape(CARD,20));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(14);parent.addView(view,params);return view;}
    private TextView text(LinearLayout page,String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(MUTED);view.setPadding(0,dp(6),0,dp(6));view.setLineSpacing(dp(3),1);page.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;}
    private TextView title(LinearLayout page,String value,int size){TextView view=text(page,value,size);view.setTextColor(TEXT);view.setTypeface(null,Typeface.BOLD);return view;}
    private EditText number(LinearLayout page,String hint,boolean secret){EditText view=new EditText(this);view.setHint(hint);view.setSingleLine(true);view.setTextColor(TEXT);view.setHintTextColor(MUTED);view.setTextSize(20);view.setPadding(dp(14),dp(8),dp(14),dp(8));view.setMinHeight(dp(52));GradientDrawable border=shape(BG,12);border.setStroke(dp(1),LINE);view.setBackground(border);view.setBackgroundTintList(null);view.setInputType(InputType.TYPE_CLASS_NUMBER|(secret?InputType.TYPE_NUMBER_VARIATION_PASSWORD:0));view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(6);params.bottomMargin=dp(6);page.addView(view,params);return view;}
    private Button button(LinearLayout page,String label,View.OnClickListener action){Button view=new Button(this);view.setText(label);view.setAllCaps(false);view.setSingleLine(false);view.setTextSize(14);view.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xff6b7f90,TEXT}));view.setMinWidth(0);view.setMinimumWidth(0);view.setMinHeight(dp(48));view.setMinimumHeight(dp(48));view.setPadding(dp(14),dp(10),dp(14),dp(10));view.setBackground(shape(0xff243443,12));view.setBackgroundTintList(null);view.setElevation(0);view.setOnClickListener(action);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(8);page.addView(view,params);return view;}
    private void primary(Button button){button.setBackground(shape(ACCENT,12));button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xff537a70,BG}));button.setTypeface(null,Typeface.BOLD);}
    private LinearLayout guideStep(LinearLayout parent,int step,String heading,String body){LinearLayout view=card(parent);title(view,step+"  "+heading,18);text(view,body,14);WirelessGuideView illustration=new WirelessGuideView(this,step);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(10);view.addView(illustration,params);return view;}
}
