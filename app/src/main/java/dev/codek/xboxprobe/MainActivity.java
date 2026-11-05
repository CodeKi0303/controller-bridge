package dev.codek.xboxprobe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.*;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import dev.codek.xboxprobe.core.ControllerProfile;

/** Foreground game launcher. Profiles are prepared before a single explicit launch. */
public final class MainActivity extends Activity implements InputManager.InputDeviceListener {
    public static final String EXTRA_DESTINATION="destination",EXTRA_AUXILIARY="auxiliary";
    private static final int REQUEST_CONNECTION=80;
    private static final int BG=0xff0c121b,CARD=0xff17222f,TEXT=0xffedf4fc,MUTED=0xff9aafc4,ACCENT=0xff78dfbd;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService inventory=Executors.newSingleThreadExecutor();
    private final LauncherAction actions=new LauncherAction();
    private final RecoveryRequest recovery=new RecoveryRequest();
    private ProfileStore store;
    private ProbeSessionService session;
    private boolean bound,notificationPending;
    private volatile boolean foreground,destroyed;
    private int pageIndex,inventoryGeneration;
    private long handoffInFlight;
    private String autoAttemptDevice="",shownSource="";
    private String localNotice="";
    private GameCatalog.Catalog catalog;
    private TextView connectionText,sessionText,noticeText,libraryCount;
    private LinearLayout content,sourcePanel;
    private AdaptiveScreenLayout adaptive;
    private AdaptiveNavigationLayout navigation;
    private String auxiliary="";
    private int returnDestination;
    private String libraryQuery="";
    private LinearLayout sessionCard;
    private Button setupAction,stopAction,cancelAction;
    private EditText search;

    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            session=((ProbeSessionService.LocalBinder)binder).service();
            LauncherAction.Snapshot action=actions.snapshot();
            if(action.token>0){
                ProbeSessionService.LaunchState live=session.launchState();
                if(live.token!=action.token||!live.packageName.equals(action.packageName)||"HANDED_OFF".equals(live.phase)
                        ||"CANCELLED".equals(live.phase)||"ERROR".equals(live.phase)||"BLOCKED".equals(live.phase))actions.clearIfGeneration(action.generation);
            }
            drainPendingAction();maybeAutoConnect();refreshSession();
        }
        @Override public void onServiceDisconnected(ComponentName name){
            session=null;localNotice="입력 서비스 연결이 종료됐습니다. 다시 연결해 주세요.";refreshSession();
        }
    };
    private final Runnable refresh=new Runnable(){@Override public void run(){
        if(!foreground||destroyed)return;
        if(session!=null)session.refreshStatus();
        refreshSession();ui.postDelayed(this,600);
    }};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        if(state!=null){
            try{actions.restore(state.getLong("actionGeneration",0),LauncherAction.Kind.valueOf(state.getString("actionKind","NONE")),
                    state.getString("requestedPackage",""),state.getString("pendingProfileJson",""),state.getLong("launchToken",0));}
            catch(RuntimeException ignored){actions.clear();}
            try{recovery.restore(state.getLong("recoverySequence",0),state.getLong("recoveryId",0),state.getLong("recoveryOwner",-1));}
            catch(RuntimeException ignored){recovery.cancel();actions.clear();}
            // A stopped process cannot inherit an unapproved launch merely from saved UI state.
            if(actions.snapshot().token==0&&!recovery.isPending())actions.clear();
            if(recovery.isPending()&&recovery.ownerGeneration()!=actions.snapshot().generation){recovery.cancel();actions.clear();}
            pageIndex=state.getInt("pageIndex",0)==2?2:0;auxiliary=state.getString("auxiliary","");returnDestination=state.getInt("returnDestination",pageIndex);
            libraryQuery=state.getString("libraryQuery","");
        }
        try{ProfileStore loaded=new ProfileStore(this);loaded.snapshot();store=loaded;}
        catch(RuntimeException error){
            LinearLayout fail=new LinearLayout(this);fail.setOrientation(LinearLayout.VERTICAL);fail.setPadding(dp(24),dp(48),dp(24),dp(24));fail.setBackgroundColor(BG);
            label(fail,"저장된 프로필을 읽을 수 없습니다",22,TEXT);label(fail,error.getMessage(),14,MUTED);
            label(fail,"기존 설정은 보존했습니다. 연결된 컨트롤러의 입력은 계속 확인할 수 있습니다.",14,MUTED);
            button(fail,"컨트롤러 테스트",v->openControllerTest(null));setContentView(fail);return;
        }
        LinearLayout connectionCard=card(null);sessionCard=connectionCard;
        connectionText=label(connectionCard,"컨트롤러 확인 중",14,ACCENT);
        sessionText=label(connectionCard,"입력 서비스 대기",13,TEXT);
        noticeText=label(connectionCard,localNotice,12,MUTED);
        sourcePanel=column(connectionCard);sourcePanel.setVisibility(View.GONE);
        LinearLayout sessionButtons=actionRow(connectionCard,112);
        setupAction=compactButton(sessionButtons,"연결 준비",v->{revokePendingAction();actions.beginConnect();openConnectionSetup("준비 후 계속");});
        stopAction=compactButton(sessionButtons,"변환 중지",v->stopAll());
        cancelAction=compactButton(sessionButtons,"준비 취소",v->{revokePendingAction();notice("실행 준비를 취소했습니다.");refreshSession();});
        content=column(null);adaptive=new AdaptiveScreenLayout(this,content,column(null));adaptive.setSinglePane(true);adaptive.setBackgroundColor(BG);
        navigation=new AdaptiveNavigationLayout(this,adaptive,"게임",pageIndex,this::showPage,this::showOverflow);
        setContentView(navigation);
        if(state==null)readDestination(getIntent());
        renderPage();refreshConnection();loadCatalog();
        if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction()))handleAttach(getIntent());
    }

    @Override protected void onStart(){super.onStart();if(store==null)return;
        bound=bindService(new Intent(this,ProbeSessionService.class),connection,BIND_AUTO_CREATE);
        getSystemService(InputManager.class).registerInputDeviceListener(this,ui);
    }
    @Override protected void onResume(){super.onResume();foreground=true;
        if(store!=null){ui.removeCallbacks(refresh);ui.post(refresh);refreshConnection();drainPendingAction();maybeAutoConnect();}
    }
    @Override protected void onPause(){foreground=false;ui.removeCallbacks(refresh);super.onPause();}
    @Override protected void onStop(){
        LauncherAction.Snapshot pending=actions.snapshot();
        if(!recovery.isPending()&&!notificationPending&&!isChangingConfigurations()&&pending.kind!=LauncherAction.Kind.NONE){
            if(session!=null&&pending.token>0)session.cancelGameLaunch(pending.token);
            actions.clearIfGeneration(pending.generation);localNotice="화면을 떠나 실행 준비를 취소했습니다. 게임 실행을 다시 눌러 주세요.";
        }
        if(store!=null)getSystemService(InputManager.class).unregisterInputDeviceListener(this);
        if(bound){unbindService(connection);bound=false;}session=null;super.onStop();
        // Do not cancel a handed-off launch here: its virtual input must stay alive in the game.
    }
    @Override protected void onDestroy(){destroyed=true;inventoryGeneration++;ui.removeCallbacks(refresh);
        inventory.shutdownNow();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);
        LauncherAction.Snapshot pending=actions.snapshot();state.putLong("launchToken",pending.token);state.putString("requestedPackage",pending.packageName);
        state.putString("pendingProfileJson",pending.profileJson);state.putLong("actionGeneration",pending.generation);state.putString("actionKind",pending.kind.name());
        RecoveryRequest.Snapshot waiting=recovery.snapshot();state.putLong("recoverySequence",waiting.sequence);state.putLong("recoveryId",waiting.requestId);state.putLong("recoveryOwner",waiting.ownerGeneration);
        state.putInt("pageIndex",pageIndex);state.putString("auxiliary",auxiliary);state.putInt("returnDestination",returnDestination);state.putString("libraryQuery",libraryQuery);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(store!=null){readDestination(intent);renderPage();handleAttach(intent);}}
    @Override public void onInputDeviceAdded(int id){refreshConnection();maybeAutoConnect();}
    @Override public void onInputDeviceChanged(int id){refreshConnection();maybeAutoConnect();}
    @Override public void onInputDeviceRemoved(int id){refreshConnection();}

    private void handleAttach(Intent intent){
        if(!UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction()))return;
        UsbDevice supplied=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra(UsbManager.EXTRA_DEVICE,UsbDevice.class):intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if(supplied==null)return;
        UsbDevice current=getSystemService(UsbManager.class).getDeviceList().get(supplied.getDeviceName());
        if(current==null||current.getVendorId()!=supplied.getVendorId()||current.getProductId()!=supplied.getProductId())return;
        refreshConnection();maybeAutoConnect();
    }
    // Android's public inventory is a connection hint, not authority to open a physical source.
    // UHID outputs also appear here. The source catalog alone excludes owned outputs by location
    // and binds the user's selection to captured capabilities and an opened device identity.
    private List<InputDevice> connectedControllers(){
        List<InputDevice> found=new ArrayList<>();
        for(int id:InputDevice.getDeviceIds()){
            InputDevice device=InputDevice.getDevice(id);
            if(device!=null&&(device.supportsSource(InputDevice.SOURCE_GAMEPAD)||device.supportsSource(InputDevice.SOURCE_JOYSTICK)))found.add(device);
        }
        found.sort(Comparator.comparingInt(InputDevice::getId));return found;
    }
    private void refreshConnection(){if(connectionText==null)return;
        try{
            List<InputDevice> controllers=connectedControllers();
            if(controllers.isEmpty()){setText(connectionText,"USB 또는 Bluetooth 게임패드를 연결해 주세요");autoAttemptDevice="";return;}
            StringBuilder description=new StringBuilder("감지된 컨트롤러 · ").append(controllers.size()).append("개");
            for(InputDevice device:controllers)description.append("\n").append(device.getName());
            setText(connectionText,description.toString());
        }catch(RuntimeException error){setText(connectionText,"게임패드 연결 상태를 확인하지 못했습니다: "+error.getMessage());}
    }
    private void maybeAutoConnect(){
        if(!foreground||session==null||store==null||!auxiliary.isEmpty()||!store.isAutoAttachEnabled()||recovery.isPending()
                ||actions.snapshot().kind!=LauncherAction.Kind.NONE||session.isSessionActive()||!hasInputPermission())return;
        try{
            List<InputDevice> controllers=connectedControllers();if(controllers.isEmpty())return;
            StringBuilder key=new StringBuilder();for(InputDevice device:controllers)key.append(device.getId()).append(':').append(device.getDescriptor()).append('\n');
            if(key.toString().equals(autoAttemptDevice))return;
            autoAttemptDevice=key.toString();actions.beginConnect();drainPendingAction();
        }catch(RuntimeException error){notice("자동 연결 확인 오류: "+error.getMessage());}
    }
    private boolean hasInputPermission(){return ConnectionSetupContract.isReady(this);}
    private void openConnectionSetup(String continuation){
        if(recovery.isPending())return;
        long request=recovery.begin(actions.snapshot().generation);
        try{startActivityForResult(ConnectionSetupContract.intent(this,continuation,request),REQUEST_CONNECTION);}
        catch(RuntimeException failure){long owner=recovery.ownerGeneration();recovery.cancel();actions.clearIfGeneration(owner);notice("연결 준비 화면을 열지 못했습니다.");}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=REQUEST_CONNECTION||!recovery.isPending())return;
        long request=recovery.requestId(),owner=recovery.ownerGeneration();
        long returned=ConnectionSetupContract.requestId(data);if(returned>0&&returned!=request)return;
        boolean accepted=ConnectionSetupContract.accepted(resultCode,data,request)&&owner==actions.snapshot().generation;
        if(accepted&&recovery.accept(request)&&hasInputPermission()){
            notice("연결 준비를 마쳤습니다. 요청한 작업을 계속합니다.");drainPendingAction();
        }else{
            recovery.cancel();actions.clearIfGeneration(owner);notice("연결 준비를 닫았습니다. 게임이나 프로필을 선택해 다시 시작할 수 있습니다.");
        }
        refreshSession();
    }
    private void openControllerTest(ControllerProfile profile){
        revokePendingAction();Intent test=new Intent(this,ControllerTestActivity.class);
        if(profile!=null)test.putExtra(ControllerTestActivity.EXTRA_PROFILE_ID,profile.getId());
        startActivity(test);
    }
    private void requestPermissionOrContinue(){
        if(!foreground)return;
        if(hasInputPermission()){drainPendingAction();return;}
        LauncherAction.Snapshot action=actions.snapshot();
        String continuation=action.kind==LauncherAction.Kind.GAME?appLabel(action.packageName)+" 실행 계속":"준비 후 계속";
        openConnectionSetup(continuation);
    }
    private boolean ensureNotifications(){
        if(notificationPending)return false;
        if(Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
                ||getSharedPreferences("launcher_permissions",MODE_PRIVATE).getBoolean("notificationsAsked",false))return true;
        notificationPending=true;
        getSharedPreferences("launcher_permissions",MODE_PRIVATE).edit().putBoolean("notificationsAsked",true).apply();
        try{requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},77);return false;}
        catch(RuntimeException error){notificationPending=false;throw error;}
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==77){notificationPending=false;drainPendingAction();}
    }
    private void drainPendingAction(){
        if(!foreground||store==null||notificationPending||recovery.isPending())return;LauncherAction.Snapshot action=actions.snapshot();
        if(action.kind==LauncherAction.Kind.GAME)startPendingLaunch();
        else if(action.kind==LauncherAction.Kind.CONNECT&&hasInputPermission())connectController(action.generation);
    }
    private void connectController(long generation){if(!foreground)return;
        try{if(!ensureNotifications())return;startForegroundService(new Intent(this,ProbeSessionService.class).setAction(ProbeSessionService.CONNECT));actions.clearIfGeneration(generation);}
        catch(RuntimeException error){notice("연결 시작 오류: "+error.getMessage());}
    }
    private void stopAll(){
        revokePendingAction();
        startService(new Intent(this,ProbeSessionService.class).setAction(ProbeSessionService.STOP));
        notice("입력을 중립으로 돌리고 세션을 종료합니다.");
    }
    private void revokePendingAction(){LauncherAction.Snapshot old=actions.snapshot();recovery.cancel();actions.clear();shownSource="";
        if(old.token>0&&session!=null)session.cancelGameLaunch(old.token);
    }
    private void launchGame(GameCatalog.GameEntry game,String profileId){
        revokePendingAction();
        // Source selection and preparation errors must remain visible when a
        // game button was pressed far down the library.
        adaptive.post(()->{if(foreground&&!destroyed)adaptive.reveal(sessionCard);});
        try{
            ControllerProfile profile=store.getProfile(profileId);
            if(profile==null)throw new IllegalArgumentException("선택한 프로필이 없습니다.");
            if(GameCatalog.launchIntent(this,game.packageName)==null)throw new IllegalStateException("앱이 삭제되었거나 실행할 수 없습니다.");
            store.assignGame(game.packageName,profileId);store.setGamePinned(game.packageName,true);
            actions.beginGame(game.packageName,ProfileCodec.encode(profile));notice(game.label+" · "+profile.getLabel()+" 준비 중…");
            if(profile.getKind()==ControllerProfile.Kind.DIRECT||hasInputPermission())startPendingLaunch();else requestPermissionOrContinue();
        }catch(RuntimeException error){notice("게임 실행 준비 오류: "+error.getMessage());}
    }
    private void startPendingLaunch(){
        LauncherAction.Snapshot action=actions.snapshot();
        if(!foreground||notificationPending||recovery.isPending()||session==null||action.kind!=LauncherAction.Kind.GAME||action.profileJson.isEmpty()||action.token!=0)return;
        try{
            ControllerProfile profile=ProfileCodec.decode(action.profileJson);
            if(profile.getKind()!=ControllerProfile.Kind.DIRECT&&!hasInputPermission())return;
            if(profile.getKind()!=ControllerProfile.Kind.DIRECT&&!ensureNotifications())return;
            startForegroundService(new Intent(this,ProbeSessionService.class).setAction(ProbeSessionService.LAUNCH_SESSION));
            long token=session.prepareGameLaunch(action.packageName,action.profileJson);
            if(!actions.markPrepared(action.generation,token))session.cancelGameLaunch(token);shownSource="";
        }catch(RuntimeException error){actions.clearIfGeneration(action.generation);notice("프로필 준비 오류: "+error.getMessage());}
    }
    private void refreshSession(){
        if(sessionText==null)return;
        boolean active=session!=null&&session.isSessionActive();
        boolean pending=actions.snapshot().kind!=LauncherAction.Kind.NONE;
        setupAction.setVisibility(!hasInputPermission()&&!active&&!pending?View.VISIBLE:View.GONE);
        stopAction.setVisibility(active?View.VISIBLE:View.GONE);
        cancelAction.setVisibility(pending&&!recovery.isPending()?View.VISIBLE:View.GONE);
        setText(noticeText,localNotice);
        noticeText.setVisibility(localNotice.isEmpty()?View.GONE:View.VISIBLE);
        if(session==null){setText(sessionText,"연결 상태 확인 중…");return;}
        ProbeSessionService.LaunchState state=session.launchState();
        LauncherAction.Snapshot action=actions.snapshot();
        String text=hasInputPermission()?"준비 완료 · 게임을 선택해 주세요.":"연결 준비가 필요합니다. 게임과 프로필은 미리 설정할 수 있습니다.";
        if(action.kind==LauncherAction.Kind.GAME&&action.token==0){text="게임 실행을 위한 권한·연결 확인 대기";}
        else if(state.token>0){
            text=(state.profileLabel.isEmpty()?"":state.profileLabel+" · ")+phaseLabel(state.phase);
            if(!state.error.isEmpty())text+="\n"+state.error;
            if("HANDED_OFF".equals(state.phase)&&!state.direct){
                try{JSONObject actual=new JSONObject(session.status());String failure=actual.optString("lastError");
                    if(!failure.isEmpty())text+="\n입력 오류: "+failure;
                    else if(!actual.optBoolean("deviceOpened"))text+="\n가상 입력이 중지되었습니다. 게임 실행을 다시 눌러 준비하세요.";
                }catch(Exception ignored){/* Keep the service's reported launch phase. */}
            }
        }else if(session.connected())text="입력 서비스 연결됨 · 게임을 선택해 주세요.";
        else if(session.isSessionActive())text=shortSessionStatus(session.status());
        setText(sessionText,text);
        if(state.needsSource&&state.token==action.token&&foreground)showSourceChoice(state);
        else{sourcePanel.setVisibility(View.GONE);shownSource="";}
        if(state.ready&&state.token==action.token&&state.packageName.equals(action.packageName)&&foreground&&!recovery.isPending()){
            queueGameHandoff(action,session);
        }
    }
    private void queueGameHandoff(LauncherAction.Snapshot action,ProbeSessionService owner){
        if(handoffInFlight==action.token)return;handoffInFlight=action.token;
        // Rechecking a remote Binder must not block the Activity's main thread.
        inventory.execute(()->{
            boolean consumed=false;String failure="";
            try{LauncherAction.Snapshot latest=actions.snapshot();
                if(foreground&&!destroyed&&latest.generation==action.generation&&latest.token==action.token)consumed=owner.consumeGameLaunchReady(action.token);
            }catch(RuntimeException error){failure=String.valueOf(error.getMessage());}
            final boolean ready=consumed;final String error=failure;
            ui.post(()->{
                if(handoffInFlight==action.token)handoffInFlight=0;
                LauncherAction.Snapshot current=actions.snapshot();
                if(destroyed||!foreground||session!=owner||current.generation!=action.generation||current.token!=action.token){
                    // Prepared resources belong to this token even when handoff
                    // was skipped before consumeGameLaunchReady could run.
                    owner.cancelGameLaunch(action.token);
                    if(!foreground&&!destroyed)actions.clearIfGeneration(action.generation);return;
                }
                if(!error.isEmpty()){owner.cancelGameLaunch(action.token);actions.clearIfGeneration(action.generation);notice("입력 준비 확인 오류: "+error);return;}
                if(!ready)return;
                try{
                    Intent launch=GameCatalog.launchIntent(this,action.packageName);
                    if(launch==null)throw new IllegalStateException("앱이 삭제되었거나 실행할 수 없습니다.");
                    startActivity(launch);actions.clearIfGeneration(action.generation);
                    notice("게임 실행됨 · 다음 게임 실행 또는 입력 종료까지 프로필을 유지합니다.");
                }catch(RuntimeException problem){owner.cancelGameLaunch(action.token);actions.clearIfGeneration(action.generation);notice("게임 실행 오류: "+problem.getMessage());}
            });
        });
    }
    private String shortSessionStatus(String raw){try{JSONObject json=new JSONObject(raw);
        String error=json.optString("lastError");return !error.isEmpty()?error:json.optBoolean("deviceOpened")?"입력 세션 실행 중":"입력 서비스 연결됨";
    }catch(Exception ignored){return "연결 상태를 다시 확인해 주세요.";}}
    private static String phaseLabel(String phase){switch(phase){
        case "CLEANING":return "이전 입력 장치 정리 중";case "CONNECTING":return "입력 서비스 연결 중";
        case "LISTING":return "게임패드 확인 중";case "NEEDS_SOURCE":return "입력 원본을 선택해 주세요";
        case "STARTING":return "프로필 적용 중";case "WAITING_READY":return "Android 입력 준비 확인 중";
        case "READY":return "게임 실행 준비 완료";case "HANDED_OFF":return "프로필 적용됨 · 입력 종료까지 유지";
        case "CANCELLED":return "실행 취소 / 세션 종료";case "ERROR":return "실행 준비 실패";
        case "BLOCKED":return "이전 장치 정리 확인 필요";default:return "게임을 선택해 주세요";
    }}
    private void showSourceChoice(ProbeSessionService.LaunchState state){
        sourcePanel.setVisibility(View.VISIBLE);String key=state.token+":"+state.sourcesJson;
        if(key.equals(shownSource))return;shownSource=key;sourcePanel.removeAllViews();
        try{
            JSONObject data=new JSONObject(state.sourcesJson);JSONArray items=data.getJSONArray("sources");
            label(sourcePanel,"변환할 게임패드를 선택하세요. 스틱과 버튼에서 손을 뗀 상태로 시작합니다.",13,MUTED);
            String[] names=new String[items.length()+1];names[0]="입력 장치 선택";
            for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);int bus=item.optInt("bus",-1);names[i+1]=(i+1)+". "+item.optString("name")+" · "+(bus==5?"Bluetooth":bus==3?"USB":"연결 방식 미확인");}
            Spinner sources=spinner(sourcePanel,names);
            button(sourcePanel,"이 장치로 준비 계속",view->{int position=sources.getSelectedItemPosition()-1;
                if(position<0){toast("입력 장치를 선택해 주세요");return;}
                try{if(session!=null&&actions.snapshot().token==state.token)session.selectGameSource(state.token,items.getJSONObject(position).getString("token"));}
                catch(Exception error){notice("원본 선택 오류: "+error.getMessage());}});
            if(items.length()==0)label(sourcePanel,"패드의 USB 연결 또는 Bluetooth 페어링을 확인한 뒤 목록을 새로 조회하세요. Android가 게임패드로 인식해야 합니다.",13,MUTED);
            JSONArray errors=data.optJSONArray("errors");if(errors!=null&&errors.length()>0)label(sourcePanel,"일부 입력 장치를 확인하지 못했습니다. 사용할 패드를 선택하거나 연결 후 다시 조회해 주세요.",12,MUTED);
            button(sourcePanel,"게임패드 목록 새로 조회",view->{if(session!=null&&actions.snapshot().token==state.token)session.refreshGameSources(state.token);});
        }catch(Exception error){label(sourcePanel,"입력 목록을 읽지 못했습니다: "+error.getMessage(),13,MUTED);}
    }

    private void loadCatalog(){if(store==null)return;int generation=++inventoryGeneration;
        Set<String> pinned=new LinkedHashSet<>(store.getPinnedGames());pinned.addAll(store.getGameAssignments().keySet());
        inventory.execute(()->{try{GameCatalog.Catalog found=GameCatalog.load(getApplicationContext(),pinned);
            ui.post(()->{if(destroyed||generation!=inventoryGeneration)return;catalog=found;if(pageIndex==0)renderPage();});
        }catch(RuntimeException error){ui.post(()->{if(!destroyed&&generation==inventoryGeneration){notice("앱 목록 오류: "+error.getMessage());if(libraryCount!=null)libraryCount.setText("앱 목록을 다시 조회해 주세요.");}});}});
    }
    private void readDestination(Intent intent){
        if(!intent.hasExtra(EXTRA_DESTINATION)&&!intent.hasExtra(EXTRA_AUXILIARY))return;
        revokePendingAction();int destination=intent.getIntExtra(EXTRA_DESTINATION,pageIndex);
        String requested=intent.getStringExtra(EXTRA_AUXILIARY);intent.removeExtra(EXTRA_DESTINATION);intent.removeExtra(EXTRA_AUXILIARY);
        if("settings".equals(requested)||"help".equals(requested)||"about".equals(requested)){
            returnDestination=destination>=0&&destination<=2?destination:pageIndex;auxiliary=requested;
        }else{auxiliary="";if(destination==1){openControllerTest(null);return;}pageIndex=destination==2?2:0;}
    }
    private void showPage(int page){
        revokePendingAction();auxiliary="";
        if(page==1){openControllerTest(null);return;}
        pageIndex=page==2?2:0;renderPage();adaptive.post(()->adaptive.reveal(content));
    }
    private void showAuxiliary(String page){revokePendingAction();if(auxiliary.isEmpty())returnDestination=pageIndex;auxiliary=page;renderPage();adaptive.post(()->adaptive.reveal(content));}
    private void closeAuxiliary(){auxiliary="";if(returnDestination==1){renderPage();openControllerTest(null);}else showPage(returnDestination);}
    @Override public void onBackPressed(){
        if(!auxiliary.isEmpty()){closeAuxiliary();return;}
        if(pageIndex!=0){showPage(0);return;}
        revokePendingAction();super.onBackPressed();
    }
    private void showOverflow(){
        PopupMenu menu=new PopupMenu(this,navigation.getOverflowAnchor());
        menu.getMenu().add(0,1,0,"앱 설정");menu.getMenu().add(0,2,1,"도움말");menu.getMenu().add(0,3,2,"앱 정보 · 라이선스");
        menu.setOnMenuItemClickListener(item->{showAuxiliary(item.getItemId()==1?"settings":item.getItemId()==2?"help":"about");return true;});menu.show();
    }
    private void renderPage(){if(content==null)return;content.removeAllViews();search=null;libraryCount=null;
        navigation.setSelectedDestination(pageIndex);navigation.setBackAction(auxiliary.isEmpty()?null:this::closeAuxiliary);
        navigation.setTitle("settings".equals(auxiliary)?"앱 설정":"help".equals(auxiliary)?"도움말":"about".equals(auxiliary)?"앱 정보":pageIndex==2?"프로필":"게임");
        try{if("settings".equals(auxiliary))renderSettings();else if("help".equals(auxiliary))renderHelp();else if("about".equals(auxiliary))renderAbout();else if(pageIndex==2)renderProfiles();else renderLibrary();}
        catch(RuntimeException error){label(content,"설정을 표시할 수 없습니다: "+error.getMessage(),14,MUTED);}
    }
    private void renderLibrary(){
        if(sessionCard.getParent() instanceof ViewGroup)((ViewGroup)sessionCard.getParent()).removeView(sessionCard);
        content.addView(sessionCard,new LinearLayout.LayoutParams(-1,-2));refreshConnection();refreshSession();
        label(content,"내 게임",21,TEXT);
        label(content,"프로필을 선택하고 게임을 실행하세요.",13,MUTED);
        LinearLayout actions=actionRow(content,124);compactButton(actions,"게임 추가",v->showAppPicker());
        Button more=compactButton(actions,"목록 메뉴",null);more.setOnClickListener(v->{PopupMenu menu=new PopupMenu(this,more);menu.getMenu().add("목록 새로고침").setOnMenuItemClickListener(item->{loadCatalog();return true;});menu.show();});
        search=edit(content,"게임 검색",libraryQuery);libraryCount=label(content,catalog==null?"설치된 앱을 확인하는 중…":"",12,MUTED);
        LinearLayout games=column(content);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){libraryQuery=s.toString();renderGames(games,libraryQuery);}public void afterTextChanged(Editable e){}});
        renderGames(games,libraryQuery);
    }
    private void renderGames(LinearLayout parent,String query){
        parent.removeAllViews();if(catalog==null)return;
        ProfileCodec.StoreDocument snapshot=store.snapshot();
        List<GameCatalog.GameEntry> candidates=new ArrayList<>();
        for(GameCatalog.GameEntry game:catalog.allLaunchable)if(!query.trim().isEmpty()||GameProfileSelection.inLibrary(game,snapshot))candidates.add(game);
        List<GameCatalog.GameEntry> visible=GameCatalog.filter(candidates,query);
        libraryCount.setText(visible.size()+"개의 게임");
        if(visible.isEmpty())label(parent,"표시할 게임이 없습니다. ‘게임 추가’에서 전체 앱을 찾아 등록할 수 있습니다.",14,MUTED);
        AdaptiveGridLayout grid=new AdaptiveGridLayout(this);parent.addView(grid,new LinearLayout.LayoutParams(-1,-2));
        for(GameCatalog.GameEntry game:visible){
            LinearLayout tile=card(grid);LinearLayout heading=row(tile);
            ImageView icon=new ImageView(this);icon.setImageDrawable(game.icon);icon.setContentDescription(game.label);heading.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(48)));
            LinearLayout names=column(null);LinearLayout.LayoutParams nameLayout=new LinearLayout.LayoutParams(0,-2,1);nameLayout.leftMargin=dp(12);heading.addView(names,nameLayout);
            label(names,game.label,17,TEXT).setTypeface(null,android.graphics.Typeface.BOLD);
            List<ControllerProfile> profiles=snapshot.profiles;
            String selected=GameProfileSelection.resolveId(game,snapshot);
            int initial=profileIndex(profiles,selected);
            Spinner choice=spinner(tile,profileLabels(profiles));choice.setSelection(initial);
            final int[] previous={initial};
            choice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int position,long id){
                String chosen=profiles.get(position).getId();
                if(previous[0]!=position){previous[0]=position;try{store.assignGame(game.packageName,chosen);}catch(RuntimeException error){toast(error.getMessage());}}
            }public void onNothingSelected(AdapterView<?> p){}});
            LinearLayout buttons=actionRow(tile,120);
            Button more=compactButton(buttons,"더보기",null);more.setContentDescription(game.label+" 더보기");
            more.setOnClickListener(v->{PopupMenu menu=new PopupMenu(this,more);menu.getMenu().add("공통 프로필 편집").setOnMenuItemClickListener(item->{openEditor(profiles.get(choice.getSelectedItemPosition()));return true;});menu.show();});
            styleButton(compactButton(buttons,"게임 실행",v->launchGame(game,profiles.get(choice.getSelectedItemPosition()).getId())),true);
        }
        if(!catalog.errors.isEmpty())label(parent,"일부 앱의 이름·아이콘을 읽지 못했습니다. 표시 가능한 정보로 목록을 구성했습니다.",12,MUTED);
    }
    private void renderProfiles(){
        label(content,"입력 프로필",21,TEXT);label(content,"이름·ID·버튼 매핑을 설정하고 테스트하세요.",13,MUTED);
        AdaptiveGridLayout grid=new AdaptiveGridLayout(this);content.addView(grid,new LinearLayout.LayoutParams(-1,-2));
        for(ControllerProfile profile:store.listProfiles()){
            LinearLayout tile=card(grid);label(tile,profile.getLabel(),18,TEXT);
            label(tile,kindLabel(profile.getKind()),13,ACCENT);
            label(tile,profile.getKind()==ControllerProfile.Kind.DIRECT?"현재 게임패드 입력 사용 · 가상 장치 없음":
                    profile.getDeviceName(),13,MUTED);
            LinearLayout actions=actionRow(tile,96);compactButton(actions,"편집",v->openEditor(profile));
            compactButton(actions,"테스트",v->openControllerTest(profile));
        }
        label(content,"프로필을 적용하면 별도의 가상 입력 장치가 만들어집니다. 게임 지원 여부는 게임마다 다릅니다.",12,MUTED);
    }
    private void openEditor(ControllerProfile profile){revokePendingAction();
        List<String> affected=catalog==null?null:GameProfileSelection.affectedGames(catalog.allLaunchable,store.snapshot(),profile.getId());
        ProfileEditorDialog.show(this,store,profile,affected,()->{renderPage();notice("공통 프로필 저장됨 · 다음 게임 실행 또는 프로필 적용부터 반영됩니다.");});}
    private void renderSettings(){
        label(content,"앱 동작",21,TEXT);LinearLayout settings=card(content);
        Switch auto=new Switch(this);auto.setText("패드 연결 시 서비스 준비");auto.setTextColor(TEXT);auto.setMinHeight(dp(56));auto.setChecked(store.isAutoAttachEnabled());settings.addView(auto);
        auto.setOnCheckedChangeListener((button,checked)->{try{store.setAutoAttachEnabled(checked);if(checked){autoAttemptDevice="";maybeAutoConnect();}}catch(RuntimeException error){toast(error.getMessage());}});
        label(settings,"앱이 열려 있고 연결 권한이 준비된 경우에만 서비스를 준비합니다. 설정 화면을 자동으로 열지는 않습니다.",12,MUTED);
        label(settings,"기본 프로필",14,TEXT);List<ControllerProfile> profiles=store.listProfiles();Spinner defaults=spinner(settings,profileLabels(profiles));int initial=profileIndex(profiles,store.getDefaultProfileId());defaults.setSelection(initial);final int[] previous={initial};
        defaults.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int position,long id){if(previous[0]!=position){previous[0]=position;try{store.setDefaultProfileId(profiles.get(position).getId());}catch(RuntimeException error){toast(error.getMessage());}}}public void onNothingSelected(AdapterView<?> p){}});
        LinearLayout advanced=card(content);Button advancedToggle=button(advanced,"고급 연결 설정  +",null);
        LinearLayout details=column(advanced);boolean showAdvanced=ConnectionMode.get(this)==ConnectionMode.SHIZUKU;details.setVisibility(showAdvanced?View.VISIBLE:View.GONE);
        advancedToggle.setText(showAdvanced?"고급 연결 설정  −":"고급 연결 설정  +");
        advancedToggle.setOnClickListener(v->{boolean show=details.getVisibility()!=View.VISIBLE;details.setVisibility(show?View.VISIBLE:View.GONE);advancedToggle.setText(show?"고급 연결 설정  −":"고급 연결 설정  +");});
        label(details,"입력 서비스 시작 방식",14,TEXT);
        Spinner backend=spinner(details,new String[]{"앱 자체 연결 · 무선 디버깅","Shizuku"});
        int selectedBackend=ConnectionMode.get(this)==ConnectionMode.STANDALONE?0:1;backend.setSelection(selectedBackend);final int[] oldBackend={selectedBackend};
        backend.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> p,View v,int position,long id){
                if(position==oldBackend[0])return;
                if(session!=null&&session.isSessionActive()){backend.setSelection(oldBackend[0]);toast("입력 종료 후 연결 방식을 변경해 주세요.");return;}
                revokePendingAction();ConnectionMode.set(MainActivity.this,position==0?ConnectionMode.STANDALONE:ConnectionMode.SHIZUKU);oldBackend[0]=position;
            }
            public void onNothingSelected(AdapterView<?> p){}
        });
        button(details,"Shizuku 사용 안내",v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://shizuku.rikka.app/guide/setup/"))));
        button(details,"앱 알림 설정",v->startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName())));
    }
    private void renderHelp(){
        label(content,"필요한 단계부터 이어서",21,TEXT);
        LinearLayout help=card(content);
        label(help,"처음 연결",18,TEXT);label(help,"게임 화면의 연결 준비에서 필요한 권한과 서비스 시작을 안내합니다. 자체 연결은 Android 무선 디버깅의 페어링을 사용합니다.",14,MUTED);
        label(help,"다시 사용할 때",18,TEXT);label(help,"앱이 현재 상태를 다시 확인합니다. 서비스가 종료됐다면 다시 시작하고, 이미 유효한 페어링은 그대로 사용합니다. 연결 포트가 바뀌었으면 새 값을 입력하세요.",14,MUTED);
        label(help,"입력이 다를 때",18,TEXT);label(help,"컨트롤러 화면에서 장치 이름과 입력을 확인하세요. 원본 장치와 변환 결과는 다른 장치입니다. 이름과 ID가 일치해도 게임별 지원 여부는 다를 수 있습니다.",14,MUTED);
        button(help,"연결 안내 열기",v->{revokePendingAction();actions.beginConnect();openConnectionSetup("준비 후 계속");});
        button(help,"컨트롤러에서 확인",v->openControllerTest(null));
    }
    private void renderAbout(){
        label(content,"Controller Bridge",24,TEXT);label(content,"버전 "+BuildConfig.VERSION_NAME,14,MUTED);
        label(content,"USB·Bluetooth 게임패드 입력을 선택한 가상 컨트롤러로 전달합니다. 실제 패드 이름이나 펌웨어를 변경하지 않습니다.",14,TEXT);
        label(content,"오픈소스와 라이선스",18,TEXT);button(content,"라이선스 보기",v->showLicenses());
    }
    private String appLabel(String packageName){
        try{return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(packageName,0)).toString();}
        catch(PackageManager.NameNotFoundException|RuntimeException missing){return packageName;}
    }
    private void showAppPicker(){if(catalog==null){toast("앱 목록을 읽는 중입니다");return;}
        LinearLayout body=column(null);body.setPadding(dp(16),dp(8),dp(16),0);EditText query=edit(body,"앱 이름 또는 패키지 검색","");
        ListView list=new ListView(this);list.setMinimumHeight(dp(96));body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        List<GameCatalog.GameEntry> visible=new ArrayList<>(catalog.allLaunchable);
        BaseAdapter adapter=new BaseAdapter(){public int getCount(){return visible.size();}public Object getItem(int p){return visible.get(p);}public long getItemId(int p){return p;}
            public View getView(int p,View old,ViewGroup parent){GameCatalog.GameEntry game=visible.get(p);LinearLayout item=row(null);item.setPadding(dp(8),dp(10),dp(8),dp(10));
                ImageView icon=new ImageView(MainActivity.this);icon.setImageDrawable(game.icon);item.addView(icon,new LinearLayout.LayoutParams(dp(36),dp(36)));
                LinearLayout text=column(null);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);params.leftMargin=dp(12);item.addView(text,params);label(text,game.label,15,TEXT);label(text,game.packageName,10,MUTED);return item;}};
        list.setAdapter(adapter);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("게임 추가 · 전체 앱").setView(body).setNegativeButton("닫기",null).create();
        list.setOnItemClickListener((parent,view,position,id)->{GameCatalog.GameEntry game=visible.get(position);try{store.setGamePinned(game.packageName,true);dialog.dismiss();showPage(0);toast(game.label+" 추가됨");}catch(RuntimeException error){toast(error.getMessage());}});
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){visible.clear();visible.addAll(GameCatalog.filter(catalog.allLaunchable,s.toString()));adapter.notifyDataSetChanged();}public void afterTextChanged(Editable e){}});dialog.show();
        dialog.getWindow().setLayout(-1,-1);AdaptiveDialogWindow.attach(this,dialog,640);
    }
    private void showLicenses(){try{
        String[] files=getAssets().list("licenses");if(files==null)return;
        files=Arrays.stream(files).filter(name->!name.endsWith(".json")).sorted().toArray(String[]::new);
        final String[] notices=files;
        AlertDialog licenses=new AlertDialog.Builder(this).setTitle("오픈소스 라이선스").setItems(notices,(dialog,index)->{
            try(java.io.InputStream input=getAssets().open("licenses/"+notices[index])){
                ScrollView scroll=new ScrollView(this);LinearLayout page=column(null);page.setPadding(dp(20),dp(8),dp(20),dp(20));scroll.addView(page);
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
                TextView body=label(page,new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8),13,TEXT);body.setTextIsSelectable(true);
                AlertDialog license=new AlertDialog.Builder(this).setTitle(notices[index]).setView(scroll).setPositiveButton("닫기",null).create();
                license.show();AdaptiveDialogWindow.attach(this,license,760);
            }catch(java.io.IOException error){toast("라이선스를 읽지 못했습니다.");}
        }).setNegativeButton("닫기",null).create();licenses.show();AdaptiveDialogWindow.attach(this,licenses,640);
    }catch(java.io.IOException error){toast("라이선스 목록을 읽지 못했습니다.");}}
    private static int profileIndex(List<ControllerProfile> profiles,String id){for(int i=0;i<profiles.size();i++)if(profiles.get(i).getId().equals(id))return i;return 0;}
    private static String[] profileLabels(List<ControllerProfile> profiles){String[] labels=new String[profiles.size()];for(int i=0;i<labels.length;i++)labels[i]=profiles.get(i).getLabel();return labels;}
    private static String kindLabel(ControllerProfile.Kind kind){switch(kind){case DIRECT:return "게임패드 직접 입력";case KEYBOARD:return "키보드 변환";case XBOX:return "Xbox 형식";default:return "표준 게임패드 형식";}}
    private void notice(String text){localNotice=text;if(noticeText!=null){noticeText.setText(text);noticeText.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);}}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void setText(TextView view,String text){if(!text.contentEquals(view.getText()))view.setText(text);}
    private LinearLayout column(LinearLayout parent){LinearLayout result=new LinearLayout(this);result.setOrientation(LinearLayout.VERTICAL);if(parent!=null)parent.addView(result,new LinearLayout.LayoutParams(-1,-2));return result;}
    private LinearLayout row(LinearLayout parent){LinearLayout result=new LinearLayout(this);result.setOrientation(LinearLayout.HORIZONTAL);result.setGravity(Gravity.CENTER_VERTICAL);if(parent!=null)parent.addView(result,new LinearLayout.LayoutParams(-1,-2));return result;}
    private LinearLayout actionRow(LinearLayout parent,int minimumWidth){LinearLayout result=new AdaptiveActionRow(this,minimumWidth);if(parent!=null)parent.addView(result,new LinearLayout.LayoutParams(-1,-2));return result;}
    private LinearLayout card(ViewGroup parent){LinearLayout result=column(null);result.setPadding(dp(16),dp(14),dp(16),dp(14));GradientDrawable shape=new GradientDrawable();shape.setColor(CARD);shape.setCornerRadius(dp(16));result.setBackground(shape);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(12);if(parent!=null)parent.addView(result,params);return result;}
    private TextView label(LinearLayout parent,String text,int size,int color){TextView view=new TextView(this);view.setText(text);view.setTextColor(color);view.setTextSize(size);view.setPadding(0,dp(4),0,dp(7));parent.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;}
    private EditText edit(LinearLayout parent,String hint,String value){EditText edit=new EditText(this);edit.setSingleLine(true);edit.setTextColor(TEXT);edit.setHintTextColor(MUTED);edit.setHint(hint);edit.setText(value);parent.addView(edit,new LinearLayout.LayoutParams(-1,-2));return edit;}
    private void styleButton(Button button,boolean primary){
        button.setAllCaps(false);button.setTextSize(14);button.setTypeface(null,android.graphics.Typeface.BOLD);
        button.setMinHeight(dp(48));button.setMinimumHeight(dp(48));button.setMinWidth(0);button.setMinimumWidth(0);
        button.setTextColor(primary?BG:TEXT);button.setPadding(dp(12),dp(8),dp(12),dp(8));
        GradientDrawable shape=new GradientDrawable();shape.setColor(primary?ACCENT:0xff223345);shape.setCornerRadius(dp(13));
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff),shape,null));button.setStateListAnimator(null);
    }
    private Button button(LinearLayout parent,String text,View.OnClickListener listener){Button view=new Button(this);view.setText(text);styleButton(view,false);view.setOnClickListener(listener);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,dp(5),0,dp(5));parent.addView(view,params);return view;}
    private Button compactButton(LinearLayout parent,String text,View.OnClickListener listener){Button button=new Button(this);button.setText(text);styleButton(button,false);button.setOnClickListener(listener);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);params.setMargins(dp(3),dp(6),dp(3),dp(6));parent.addView(button,params);return button;}
    private Spinner spinner(LinearLayout parent,String[] names){Spinner spinner=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,names){
        @Override public View getView(int position,View old,ViewGroup owner){TextView view=(TextView)super.getView(position,old,owner);view.setTextColor(TEXT);view.setTextSize(14);view.setPadding(dp(12),dp(10),dp(12),dp(10));view.setMinHeight(dp(48));return view;}
        @Override public View getDropDownView(int position,View old,ViewGroup owner){TextView view=(TextView)super.getDropDownView(position,old,owner);view.setTextColor(TEXT);view.setTextSize(14);view.setPadding(dp(16),dp(12),dp(16),dp(12));view.setMinHeight(dp(48));return view;}
    };adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(adapter);spinner.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(CARD));parent.addView(spinner,new LinearLayout.LayoutParams(-1,-2));return spinner;}
}
