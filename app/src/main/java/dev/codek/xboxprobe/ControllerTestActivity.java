package dev.codek.xboxprobe;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.AdapterView;
import dev.codek.xboxprobe.core.ControllerProfile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Passive device inspection plus an explicit profile test. Entry does not
 * replace an existing game session; only Apply/Stop can change input output. */
public final class ControllerTestActivity extends Activity implements InputManager.InputDeviceListener {
    public static final String EXTRA_PROFILE_ID="profileId";
    private static final int SETUP_REQUEST=80;
    private static final int BG=0xff0c121b,CARD=0xff17222f,TEXT=0xffedf4fc,MUTED=0xff9aafc4,ACCENT=0xff78dfbd,RED=0xffff5d6c;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ControllerTestState controls=new ControllerTestState();
    private final List<Integer> deviceIds=new ArrayList<>();
    private final List<ControllerProfile> profiles=new ArrayList<>();
    private final Set<Integer> beforeApply=new HashSet<>();
    private Spinner deviceChoice,profileChoice;
    private TextView identity,comparison,pressed,history,status,profileDetail;
    private ControllerDiagramView diagram;
    private AdaptiveScreenLayout screenLayout;
    private AdaptiveNavigationLayout navigation;
    private LinearLayout sourcePanel,liveCard;
    private Button apply,stop,connectionSetup;
    private final RecoveryRequest recovery=new RecoveryRequest();
    private long profileGeneration,authorizedGeneration=-1;
    private String selectedProfileIdentity="",authorizedProfileId="",authorizedProfileJson="";
    private ProbeSessionService service;
    private boolean bound,changingDevices,consuming,handedOff,notificationPending,authorizedReady;
    private volatile boolean foreground,destroyed;
    private int selectedDevice=-1;
    private volatile long requestToken;
    private String shownSources="";
    private ControllerProfile requestedProfile;
    private String appliedProfileId="";
    private ControllerProfile.Kind appliedKind;
    private boolean outputStopped;

    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){service=((ProbeSessionService.LocalBinder)binder).service();refreshSession();continueAuthorizedApply();}
        @Override public void onServiceDisconnected(ComponentName name){service=null;status.setText("입력 서비스 연결이 종료되었습니다. 연결 설정을 확인해 주세요.");}
    };
    private final Runnable refresh=new Runnable(){@Override public void run(){
        if(!foreground||destroyed)return;
        if(service!=null)service.refreshStatus();
        refreshSession();main.postDelayed(this,500);
    }};

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        if(saved!=null){
            profileGeneration=saved.getLong("profileGeneration",0);selectedProfileIdentity=saved.getString("selectedProfileId","");
            authorizedGeneration=saved.getLong("authorizedGeneration",-1);authorizedProfileId=saved.getString("authorizedProfileId","");authorizedProfileJson=saved.getString("authorizedProfileJson","");
            recovery.restore(saved.getLong("recoverySequence",0),saved.getLong("recoveryRequest",0),saved.getLong("recoveryOwner",-1));
            // Only an outstanding setup activity can return an owned approval.
            // A prior permission dialog or already-consumed apply is not replayed.
            if(!recovery.isPending())clearResumeRequest();
        }
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        LinearLayout secondary=new LinearLayout(this);secondary.setOrientation(LinearLayout.VERTICAL);
        liveCard=card(page);
        LinearLayout live=liveCard;
        deviceChoice=spinner(live,new String[]{"연결된 장치 없음"});
        diagram=new ControllerDiagramView(this,controls);
        live.addView(diagram,new LinearLayout.LayoutParams(-1,-2));
        pressed=label(live,"버튼과 스틱을 조작해 보세요.",15,RED);
        history=label(live,"최대 동시 입력 0개",12,MUTED);
        button(live,"기록 초기화",v->{controls.reset();renderControls();},false);
        LinearLayout selected=card(secondary);
        label(selected,"선택한 입력 장치",18,TEXT);
        identity=label(selected,"게임패드를 USB 또는 Bluetooth로 연결해 주세요.",14,TEXT);
        comparison=label(selected,"확인할 장치를 선택하세요. 장치별 입력은 섞이지 않습니다.",12,MUTED);
        LinearLayout setup=card(secondary);
        label(setup,"프로필 출력 확인",18,TEXT);
        label(setup,"적용하면 현재 입력 프로필이 바뀝니다. 원본 패드는 연결한 채로 두세요.",13,MUTED);
        try{profiles.addAll(new ProfileStore(this).listProfiles());}catch(RuntimeException error){/* Keep passive tester available even when saved profiles cannot be loaded. */}
        String[] names=new String[profiles.size()];for(int i=0;i<names.length;i++)names[i]=profiles.get(i).getLabel();
        profileChoice=spinner(setup,names.length==0?new String[]{"프로필을 불러오지 못했습니다"}:names);
        String preferred=saved!=null?saved.getString("selectedProfileId"):getIntent().getStringExtra(EXTRA_PROFILE_ID);
        if(preferred!=null)for(int i=0;i<profiles.size();i++)if(profiles.get(i).getId().equals(preferred))profileChoice.setSelection(i);
        profileDetail=label(setup,"",13,MUTED);
        apply=button(setup,"프로필 적용 후 테스트",v->applyProfile(),true);apply.setEnabled(!profiles.isEmpty());
        status=label(setup,"기존 입력을 그대로 확인 중입니다.",14,TEXT);
        sourcePanel=new LinearLayout(this);sourcePanel.setOrientation(LinearLayout.VERTICAL);setup.addView(sourcePanel);sourcePanel.setVisibility(View.GONE);
        connectionSetup=button(setup,"연결 준비",v->requestConnectionSetup(),false);
        stop=button(setup,"변환 중지",v->{clearResumeRequest();cancelPending();startService(new Intent(this,ProbeSessionService.class).setAction(ProbeSessionService.STOP));status.setText("입력을 종료하고 장치를 정리합니다.");clearControls();},false);stop.setVisibility(View.GONE);
        label(page,"색은 Android가 받은 입력을 나타냅니다. 이름·ID가 일치해도 게임별 지원 여부는 별도로 확인해야 합니다.",12,MUTED);
        screenLayout=new AdaptiveScreenLayout(this,page,secondary);screenLayout.setBackgroundColor(BG);
        navigation=new AdaptiveNavigationLayout(this,screenLayout,"컨트롤러",AdaptiveNavigationLayout.CONTROLLER,
                destination->{if(destination!=AdaptiveNavigationLayout.CONTROLLER)navigateMain(destination,null);},this::showOverflow);
        setContentView(navigation);
        deviceChoice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                // Adapter replacement may post a stale selection callback. Only
                // the spinner's current position may change the observed device.
                if(!changingDevices&&position==deviceChoice.getSelectedItemPosition())selectDevice(position<deviceIds.size()?deviceIds.get(position):-1);
            }
            @Override public void onNothingSelected(AdapterView<?> parent){if(!changingDevices&&deviceIds.isEmpty())selectDevice(-1);}
        });
        profileChoice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){showProfile();}
            @Override public void onNothingSelected(AdapterView<?> parent){showProfile();}
        });
        if(saved!=null){
            selectedDevice=saved.getInt("selectedDevice",-1);requestToken=saved.getLong("requestToken",0);handedOff=saved.getBoolean("handedOff",false);
            appliedProfileId=saved.getString("appliedProfileId","");
            try{appliedKind=ControllerProfile.Kind.valueOf(saved.getString("appliedKind",""));}catch(IllegalArgumentException ignored){}
        }
        showProfile();refreshDevices();renderControls();
    }

    @Override protected void onStart(){super.onStart();bound=bindService(new Intent(this,ProbeSessionService.class),connection,BIND_AUTO_CREATE);getSystemService(InputManager.class).registerInputDeviceListener(this,main);}
    @Override protected void onResume(){super.onResume();foreground=true;refreshDevices();main.removeCallbacks(refresh);main.post(refresh);continueAuthorizedApply();}
    @Override protected void onPause(){foreground=false;main.removeCallbacks(refresh);if(!recovery.isPending()&&!notificationPending)clearResumeRequest();clearControls();super.onPause();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus)clearControls();}
    @Override protected void onStop(){cancelPending();getSystemService(InputManager.class).unregisterInputDeviceListener(this);if(bound){unbindService(connection);bound=false;}service=null;super.onStop();}
    @Override protected void onDestroy(){destroyed=true;main.removeCallbacksAndMessages(null);worker.shutdown();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);out.putInt("selectedDevice",selectedDevice);out.putLong("requestToken",handedOff?requestToken:0);out.putBoolean("handedOff",handedOff);out.putString("appliedProfileId",appliedProfileId);if(appliedKind!=null)out.putString("appliedKind",appliedKind.name());
        out.putString("selectedProfileId",selectedProfileIdentity);out.putLong("profileGeneration",profileGeneration);
        out.putString("authorizedProfileId",authorizedProfileId);out.putString("authorizedProfileJson",authorizedProfileJson);out.putLong("authorizedGeneration",authorizedGeneration);
        RecoveryRequest.Snapshot pending=recovery.snapshot();out.putLong("recoverySequence",pending.sequence);out.putLong("recoveryRequest",pending.requestId);out.putLong("recoveryOwner",pending.ownerGeneration);
    }
    @Override public void onBackPressed(){navigateMain(AdaptiveNavigationLayout.GAMES,null);}
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);clearResumeRequest();String id=intent.getStringExtra(EXTRA_PROFILE_ID);
        if(id!=null)for(int i=0;i<profiles.size();i++)if(id.equals(profiles.get(i).getId())){profileChoice.setSelection(i);break;}
    }
    private void navigateMain(int destination,String auxiliary){
        clearResumeRequest();cancelPending();Intent intent=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_DESTINATION,destination);
        if(auxiliary!=null)intent.putExtra(MainActivity.EXTRA_AUXILIARY,auxiliary);startActivity(intent);finish();
    }
    private void showOverflow(){
        PopupMenu menu=new PopupMenu(this,navigation.getOverflowAnchor());menu.getMenu().add(0,0,0,"앱 설정");menu.getMenu().add(0,1,1,"도움말");menu.getMenu().add(0,2,2,"앱 정보");
        menu.setOnMenuItemClickListener(item->{navigateMain(AdaptiveNavigationLayout.CONTROLLER,item.getItemId()==0?"settings":item.getItemId()==1?"help":"about");return true;});menu.show();
    }
    @Override public void onInputDeviceAdded(int id){refreshDevices();}
    @Override public void onInputDeviceChanged(int id){if(id==selectedDevice)clearControls();refreshDevices();}
    @Override public void onInputDeviceRemoved(int id){if(id==selectedDevice){selectedDevice=-1;controls.reset();}refreshDevices();}

    private static boolean relevant(InputDevice device){return device!=null&&(device.supportsSource(InputDevice.SOURCE_GAMEPAD)||device.supportsSource(InputDevice.SOURCE_JOYSTICK)||device.isExternal()&&device.getKeyboardType()==InputDevice.KEYBOARD_TYPE_ALPHABETIC);}
    private void refreshDevices(){
        if(deviceChoice==null)return;
        List<Integer> next=new ArrayList<>();List<String> names=new ArrayList<>();
        for(int id:InputDevice.getDeviceIds()){if(id<0)continue;InputDevice d=InputDevice.getDevice(id);if(relevant(d)){next.add(id);names.add(d.getName()+" · #"+id);}}
        boolean changed=!next.equals(deviceIds);deviceIds.clear();deviceIds.addAll(next);
        if(changed){changingDevices=true;setItems(deviceChoice,names.isEmpty()?new String[]{"연결된 장치 없음"}:names.toArray(new String[0]));changingDevices=false;}
        int selected=next.indexOf(selectedDevice);if(selected<0)selected=next.isEmpty()?-1:0;
        if(selected>=0){changingDevices=true;deviceChoice.setSelection(selected);changingDevices=false;selectDevice(next.get(selected));}else selectDevice(-1);
        maybeSelectNewOutput();
    }
    private void selectDevice(int id){
        if(id<0)id=-1;
        if(selectedDevice!=id){selectedDevice=id;controls.reset();}
        // Android resolves -1 to its virtual keyboard; our negative sentinel
        // means no selection and must never cross the platform lookup boundary.
        InputDevice d=id<0?null:InputDevice.getDevice(id);
        if(d==null){identity.setText("연결된 입력 장치가 없습니다.");comparison.setText("패드를 연결하면 여기에 표시됩니다.");comparison.setTextColor(MUTED);diagram.setAlpha(.4f);}
        else{
            boolean gamepad=d.supportsSource(InputDevice.SOURCE_GAMEPAD)||d.supportsSource(InputDevice.SOURCE_JOYSTICK);
            identity.setText(String.format(Locale.ROOT,"%s\nVID %04X · PID %04X · %s",d.getName(),d.getVendorId(),d.getProductId(),gamepad?"게임패드 입력":"키보드 입력"));
            ControllerProfile expected=selectedProfile();
            if(expected!=null&&expected.getKind()!=ControllerProfile.Kind.DIRECT){
                boolean match=matches(d,expected);comparison.setText(match?"선택한 프로필과 장치 이름·ID가 일치합니다.":"현재 장치는 선택한 프로필의 이름·ID와 다릅니다.");comparison.setTextColor(match?ACCENT:MUTED);
            }else{comparison.setText("Android가 인식한 장치 정보를 표시합니다.");comparison.setTextColor(MUTED);}
            diagram.setAlpha(gamepad?1f:.4f);
        }
        renderControls();
    }
    private void maybeSelectNewOutput(){
        if(!handedOff||requestedProfile==null||requestedProfile.getKind()==ControllerProfile.Kind.DIRECT)return;
        int found=-1,count=0;
        for(int id:deviceIds){InputDevice d=InputDevice.getDevice(id);if(!beforeApply.contains(id)&&matches(d,requestedProfile)){found=id;count++;}}
        if(count==1){requestedProfile=null;int position=deviceIds.indexOf(found);changingDevices=true;deviceChoice.setSelection(position);changingDevices=false;selectDevice(found);}
        else if(count>1){requestedProfile=null;status.setText("동일한 이름·ID의 새 장치가 여러 개입니다. 위 목록에서 확인할 장치를 선택하세요.");}
    }
    private static boolean matches(InputDevice d,ControllerProfile profile){return d!=null&&profile.getDeviceName().equals(d.getName())&&profile.getVendorId()==d.getVendorId()&&profile.getProductId()==d.getProductId();}
    private ControllerProfile selectedProfile(){int i=profileChoice==null?-1:profileChoice.getSelectedItemPosition();return i>=0&&i<profiles.size()?profiles.get(i):null;}
    private void showProfile(){
        ControllerProfile p=selectedProfile();if(profileDetail==null)return;String id=p==null?"":p.getId();
        if(!id.equals(selectedProfileIdentity)){profileGeneration++;selectedProfileIdentity=id;clearResumeRequest();cancelPending();}
        profileDetail.setText(p==null?"저장된 프로필을 확인해 주세요.":p.getKind()==ControllerProfile.Kind.DIRECT?"가상 장치를 만들지 않고 원본 입력을 사용합니다.":String.format(Locale.ROOT,"%s · %04X:%04X",p.getDeviceName(),p.getVendorId(),p.getProductId()));if(identity!=null)selectDevice(selectedDevice);
    }

    private boolean connectionReady(){
        return ConnectionSetupContract.isReady(this);
    }
    private void clearResumeRequest(){recovery.cancel();authorizedProfileJson="";authorizedProfileId="";authorizedGeneration=-1;notificationPending=false;authorizedReady=false;}
    private void authorizeProfile(ControllerProfile profile){clearResumeRequest();authorizedProfileJson=ProfileCodec.encode(profile);authorizedProfileId=profile.getId();authorizedGeneration=profileGeneration;}
    private void requestConnectionSetup(){if(!foreground)return;ControllerProfile profile=selectedProfile();if(profile==null)return;authorizeProfile(profile);openConnectionSetup(profile);}
    private void openConnectionSetup(ControllerProfile profile){
        long id=recovery.begin(profileGeneration);
        try{startActivityForResult(ConnectionSetupContract.intent(this,profile.getLabel()+" 적용 계속",id),SETUP_REQUEST);}
        catch(RuntimeException failure){clearResumeRequest();status.setText("연결 준비 화면을 열지 못했습니다. 다시 시도해 주세요.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=SETUP_REQUEST||!recovery.isPending())return;
        long returned=ConnectionSetupContract.requestId(data),expected=recovery.requestId();if(returned>0&&returned!=expected)return;
        boolean ownerMatches=recovery.ownerGeneration()==profileGeneration&&authorizedGeneration==profileGeneration&&authorizedProfileId.equals(selectedProfileIdentity);
        if(ownerMatches&&ConnectionSetupContract.accepted(result,data,expected)&&recovery.accept(returned)){authorizedReady=true;main.post(this::continueAuthorizedApply);}
        else{clearResumeRequest();status.setText("연결 준비를 마치지 않아 프로필을 적용하지 않았습니다.");}
    }
    private void applyProfile(){
        if(!foreground)return;ControllerProfile p=selectedProfile();if(p==null)return;authorizeProfile(p);
        if(p.getKind()!=ControllerProfile.Kind.DIRECT&&!connectionReady()){openConnectionSetup(p);return;}
        authorizedReady=true;
        continueAuthorizedApply();
    }
    private void continueAuthorizedApply(){
        if(!foreground||destroyed||!authorizedReady||recovery.isPending()||notificationPending||authorizedProfileJson.isEmpty())return;
        if(authorizedGeneration!=profileGeneration||!authorizedProfileId.equals(selectedProfileIdentity)){clearResumeRequest();return;}
        if(service==null){status.setText("입력 화면 연결을 준비 중입니다…");return;}
        ControllerProfile p;
        try{p=ProfileCodec.decode(authorizedProfileJson);}catch(RuntimeException invalid){clearResumeRequest();status.setText("적용할 프로필을 다시 선택해 주세요.");return;}
        if(p.getKind()!=ControllerProfile.Kind.DIRECT&&!connectionReady()){clearResumeRequest();status.setText("연결 준비 상태가 바뀌었습니다. 다시 준비해 주세요.");return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
                &&!getSharedPreferences("launcher_permissions",MODE_PRIVATE).getBoolean("notificationsAsked",false)){
            notificationPending=true;getSharedPreferences("launcher_permissions",MODE_PRIVATE).edit().putBoolean("notificationsAsked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},79);return;
        }
        try{
            String profileJson=authorizedProfileJson;clearResumeRequest();
            cancelPending();beforeApply.clear();for(int id:InputDevice.getDeviceIds())beforeApply.add(id);requestedProfile=p;
            appliedProfileId=p.getId();appliedKind=p.getKind();outputStopped=false;
            startForegroundService(new Intent(this,ProbeSessionService.class).setAction(ProbeSessionService.LAUNCH_SESSION));
            requestToken=service.prepareGameLaunch(getPackageName(),profileJson);handedOff=false;shownSources="";
            status.setText("프로필을 준비하고 있습니다…");refreshSession();
        }catch(RuntimeException error){status.setText("프로필을 준비하지 못했습니다. 연결 상태를 확인해 주세요.");}
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==79&&notificationPending){notificationPending=false;main.post(this::continueAuthorizedApply);}}
    private void cancelPending(){if(requestToken>0&&!handedOff&&service!=null)service.cancelGameLaunch(requestToken);if(!handedOff)requestToken=0;consuming=false;}
    private void refreshSession(){
        if(connectionSetup!=null)connectionSetup.setVisibility(connectionReady()?View.GONE:View.VISIBLE);
        if(stop!=null)stop.setVisibility(service!=null&&service.isSessionActive()?View.VISIBLE:View.GONE);
        if(status==null||service==null)return;
        ProbeSessionService.LaunchState state=service.launchState();
        boolean own=requestToken>0&&state.token==requestToken;
        apply.setEnabled(!profiles.isEmpty()&&!consuming);
        if(!own){sourcePanel.setVisibility(View.GONE);return;}
        status.setTextColor(TEXT);
        if(!state.error.isEmpty()){status.setText(state.error);sourcePanel.setVisibility(View.GONE);return;}
        if(state.needsSource){status.setText("입력 원본을 선택하세요.");showSources(state);return;}
        sourcePanel.setVisibility(View.GONE);
        if(state.ready&&!consuming&&!handedOff&&foreground)consumeReady(state.token);
        else if("HANDED_OFF".equals(state.phase)){handedOff=true;maybeSelectNewOutput();showOutputStatus();}
        else if("CANCELLED".equals(state.phase))status.setText("입력 준비가 종료되었습니다. 다시 적용할 수 있습니다.");
        else if(!handedOff)status.setText("프로필을 준비하고 있습니다…");
    }
    private void consumeReady(long token){
        consuming=true;ProbeSessionService owner=service;
        worker.execute(()->{
            boolean ready=false;
            try{if(foreground&&!destroyed&&requestToken==token)ready=owner.consumeGameLaunchReady(token);}catch(RuntimeException ignored){}
            final boolean accepted=ready;
            main.post(()->{
                consuming=false;
                if(destroyed||!foreground||service!=owner||requestToken!=token){if(accepted)owner.cancelGameLaunch(token);return;}
                if(accepted){
                    handedOff=true;refreshDevices();refreshSession();
                    screenLayout.post(()->{if(foreground&&!destroyed)screenLayout.reveal(diagram);});
                }
                else refreshSession();
            });
        });
    }
    private void showOutputStatus(){
        if(appliedKind==ControllerProfile.Kind.DIRECT){status.setText("원본 입력을 사용합니다. 위에서 장치를 선택해 확인하세요.");return;}
        try{
            JSONObject actual=new JSONObject(service.status());
            if(appliedProfileId.isEmpty()||!appliedProfileId.equals(actual.optString("configuredProfileId"))){status.setText("적용한 프로필의 입력 상태를 확인 중입니다…");return;}
            String error=actual.optString("lastError");
            if(!error.isEmpty()){showStoppedOutput("입력 오류: "+error);return;}
            String[] flags={"sessionClosing","deviceOpened","kernelStarted","consumerOpen"};
            for(String flag:flags)if(!(actual.opt(flag) instanceof Boolean)){status.setText("입력 상태를 확인 중입니다…");return;}
            if(actual.getBoolean("sessionClosing")||!actual.getBoolean("deviceOpened")||!actual.getBoolean("kernelStarted")||!actual.getBoolean("consumerOpen")){
                showStoppedOutput("입력이 중지되었습니다. 연결을 확인한 뒤 프로필을 다시 적용하세요.");return;
            }
            JSONObject bridge=actual.optJSONObject("bridge");
            if(bridge==null||!(bridge.opt("state") instanceof String)){status.setText("컨트롤러 연결 상태를 확인 중입니다…");return;}
            if(!"running".equals(bridge.getString("state"))){showStoppedOutput("컨트롤러 입력 전달이 멈췄습니다. 연결을 확인한 뒤 프로필을 다시 적용하세요.");return;}
            outputStopped=false;status.setText("프로필 입력 전달 중. 위에서 붉게 표시되는 입력을 확인하세요.");
        }catch(Exception ignored){status.setText("현재 입력 상태를 확인 중입니다…");}
    }
    private void showStoppedOutput(String message){
        status.setText(message);status.setTextColor(RED);
        // Clear once on a confirmed stop; stale/unknown snapshots never erase
        // the observer's live state or the retained peak chord.
        if(!outputStopped){outputStopped=true;clearControls();}
    }
    private void showSources(ProbeSessionService.LaunchState state){
        sourcePanel.setVisibility(View.VISIBLE);String key=state.token+":"+state.sourcesJson;if(key.equals(shownSources))return;shownSources=key;sourcePanel.removeAllViews();
        try{
            JSONObject catalog=new JSONObject(state.sourcesJson);JSONArray entries=catalog.getJSONArray("sources");
            String[] names=new String[entries.length()+1];names[0]="원본 컨트롤러 선택";
            for(int i=0;i<entries.length();i++){
                JSONObject entry=entries.getJSONObject(i);int bus=entry.optInt("bus",-1);
                String transport=bus==3?"USB":bus==5?"Bluetooth":"입력 장치";
                names[i+1]=entry.getString("name")+" · "+transport+" · "+(i+1)+"번";
            }
            Spinner choice=spinner(sourcePanel,names);
            button(sourcePanel,"이 컨트롤러 사용",v->{int selected=choice.getSelectedItemPosition()-1;if(selected<0)return;try{if(service!=null&&requestToken==state.token)service.selectGameSource(state.token,entries.getJSONObject(selected).getString("token"));}catch(Exception ignored){status.setText("원본 목록을 새로 확인해 주세요.");}},true);
            if(entries.length()==0)label(sourcePanel,"컨트롤러 연결을 확인한 뒤 다시 조회하세요.",13,MUTED);
            JSONArray errors=catalog.optJSONArray("errors");if(errors!=null&&errors.length()>0)label(sourcePanel,"장치 정보를 확인하지 못했습니다. 다시 연결하거나 다른 입력 모드를 사용해 주세요.",13,MUTED);
            button(sourcePanel,"다시 조회",v->{if(service!=null&&requestToken==state.token){shownSources="";service.refreshGameSources(state.token);}},false);
        }catch(Exception error){label(sourcePanel,"입력 원본 정보를 읽지 못했습니다.",13,MUTED);}
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(!foreground||!hasWindowFocus()||event.getDeviceId()<0||event.getKeyCode()==KeyEvent.KEYCODE_BACK||event.getKeyCode()==KeyEvent.KEYCODE_HOME)return super.dispatchKeyEvent(event);
        InputDevice device=event.getDevice();if(!relevant(device))return super.dispatchKeyEvent(event);
        ControllerTestState.Control control=keyControl(event.getKeyCode());
        // The source and virtual output can both deliver a button. Only the
        // selected device updates the diagram; the other pad must not click
        // focused Apply/Reset buttons while its output is being tested.
        if(selectedDevice<0||event.getDeviceId()!=selectedDevice){
            if((control!=null||KeyEvent.isGamepadButton(event.getKeyCode()))&&(device.supportsSource(InputDevice.SOURCE_GAMEPAD)||device.supportsSource(InputDevice.SOURCE_JOYSTICK)))return true;
            return super.dispatchKeyEvent(event);
        }
        boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
        if(event.getAction()!=KeyEvent.ACTION_DOWN&&event.getAction()!=KeyEvent.ACTION_UP)return super.dispatchKeyEvent(event);
        if(control!=null){controls.key(control,down);renderControls();return true;}
        if(KeyEvent.isGamepadButton(event.getKeyCode())){controls.keyboardKey(KeyEvent.keyCodeToString(event.getKeyCode()).replace("KEYCODE_BUTTON_",""),down);renderControls();return true;}
        if(device.getKeyboardType()==InputDevice.KEYBOARD_TYPE_ALPHABETIC){controls.keyboardKey(KeyEvent.keyCodeToString(event.getKeyCode()).replace("KEYCODE_",""),down);renderControls();return true;}
        return super.dispatchKeyEvent(event);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event){
        if(event.getDeviceId()<0)return super.dispatchGenericMotionEvent(event);
        if(foreground&&hasWindowFocus()&&(event.getSource()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK){
            if(selectedDevice<0||event.getDeviceId()!=selectedDevice)return true;
            InputDevice device=event.getDevice();if(device==null)return super.dispatchGenericMotionEvent(event);
            boolean zr=stickRange(device,MotionEvent.AXIS_Z)&&stickRange(device,MotionEvent.AXIS_RZ);
            int rightX=zr?MotionEvent.AXIS_Z:MotionEvent.AXIS_RX,rightY=zr?MotionEvent.AXIS_RZ:MotionEvent.AXIS_RY;
            controls.motion(axis(event,device,MotionEvent.AXIS_X),axis(event,device,MotionEvent.AXIS_Y),axis(event,device,rightX),axis(event,device,rightY),
                    trigger(event,device,MotionEvent.AXIS_LTRIGGER,MotionEvent.AXIS_BRAKE),trigger(event,device,MotionEvent.AXIS_RTRIGGER,MotionEvent.AXIS_GAS),
                    event.getAxisValue(MotionEvent.AXIS_HAT_X),event.getAxisValue(MotionEvent.AXIS_HAT_Y));renderControls();return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }
    private static boolean stickRange(InputDevice d,int axis){InputDevice.MotionRange r=d.getMotionRange(axis,InputDevice.SOURCE_JOYSTICK);return r!=null&&r.getMin()<0&&r.getMax()>0;}
    private static float axis(MotionEvent event,InputDevice device,int id){InputDevice.MotionRange range=device.getMotionRange(id,event.getSource());if(range==null||range.getMax()<=range.getMin())return 0;float center=(range.getMax()+range.getMin())/2;float value=event.getAxisValue(id)-center;if(Math.abs(value)<=range.getFlat())return 0;return value/(value>=0?range.getMax()-center:center-range.getMin());}
    private static float trigger(MotionEvent e,InputDevice d,int preferred,int fallback){InputDevice.MotionRange range=d.getMotionRange(preferred,e.getSource());int axis=preferred;if(range==null){range=d.getMotionRange(fallback,e.getSource());axis=fallback;}if(range==null||range.getMax()<=range.getMin())return 0;return (e.getAxisValue(axis)-range.getMin())/(range.getMax()-range.getMin());}
    private static ControllerTestState.Control keyControl(int key){switch(key){
        case KeyEvent.KEYCODE_BUTTON_A:return ControllerTestState.Control.A;case KeyEvent.KEYCODE_BUTTON_B:return ControllerTestState.Control.B;
        case KeyEvent.KEYCODE_BUTTON_X:return ControllerTestState.Control.X;case KeyEvent.KEYCODE_BUTTON_Y:return ControllerTestState.Control.Y;
        case KeyEvent.KEYCODE_BUTTON_L1:return ControllerTestState.Control.LB;case KeyEvent.KEYCODE_BUTTON_R1:return ControllerTestState.Control.RB;
        case KeyEvent.KEYCODE_BUTTON_L2:return ControllerTestState.Control.LT;case KeyEvent.KEYCODE_BUTTON_R2:return ControllerTestState.Control.RT;
        case KeyEvent.KEYCODE_BUTTON_THUMBL:return ControllerTestState.Control.L3;case KeyEvent.KEYCODE_BUTTON_THUMBR:return ControllerTestState.Control.R3;
        case KeyEvent.KEYCODE_DPAD_UP:return ControllerTestState.Control.UP;case KeyEvent.KEYCODE_DPAD_DOWN:return ControllerTestState.Control.DOWN;
        case KeyEvent.KEYCODE_DPAD_LEFT:return ControllerTestState.Control.LEFT;case KeyEvent.KEYCODE_DPAD_RIGHT:return ControllerTestState.Control.RIGHT;
        case KeyEvent.KEYCODE_BUTTON_SELECT:return ControllerTestState.Control.SELECT;case KeyEvent.KEYCODE_BUTTON_START:return ControllerTestState.Control.START;
        case KeyEvent.KEYCODE_BUTTON_MODE:return ControllerTestState.Control.GUIDE;default:return null;
    }}
    private void clearControls(){controls.clear();renderControls();}
    private void renderControls(){
        if(diagram==null)return;diagram.changed();List<String> active=controls.activeLabels();
        pressed.setText(active.isEmpty()?"입력 없음":String.join("  +  ",active));
        history.setText("현재 "+active.size()+"개 · 최대 동시 입력 "+controls.peak()+"개"
                +(controls.peak()==0?"":"\n기록: "+String.join(" + ",controls.peakLabels())));
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView label(LinearLayout parent,String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(7));if(size>=18)t.setTypeface(Typeface.DEFAULT_BOLD);parent.addView(t,new LinearLayout.LayoutParams(-1,-2));return t;}
    private LinearLayout card(LinearLayout parent){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(16),dp(14),dp(16),dp(14));GradientDrawable bg=new GradientDrawable();bg.setColor(CARD);bg.setCornerRadius(dp(20));c.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(16);parent.addView(c,lp);return c;}
    private Button button(LinearLayout parent,String title,View.OnClickListener click,boolean primary){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?BG:TEXT);b.setMinHeight(dp(48));GradientDrawable bg=new GradientDrawable();bg.setCornerRadius(dp(12));bg.setColor(primary?ACCENT:0xff25364a);b.setBackground(bg);b.setPadding(dp(14),dp(8),dp(14),dp(8));b.setOnClickListener(click);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);lp.bottomMargin=dp(4);parent.addView(b,lp);return b;}
    private Spinner spinner(LinearLayout parent,String[] items){Spinner spinner=new Spinner(this);setItems(spinner,items);spinner.setMinimumHeight(dp(48));parent.addView(spinner,new LinearLayout.LayoutParams(-1,-2));return spinner;}
    private void setItems(Spinner spinner,String[] items){ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,items){@Override public View getView(int p,View recycled,android.view.ViewGroup parent){TextView text=(TextView)super.getView(p,recycled,parent);text.setTextColor(TEXT);text.setTextSize(14);text.setSingleLine(false);return text;}@Override public View getDropDownView(int p,View recycled,android.view.ViewGroup parent){TextView text=(TextView)super.getDropDownView(p,recycled,parent);text.setTextColor(TEXT);text.setBackgroundColor(CARD);text.setPadding(dp(14),dp(14),dp(14),dp(14));return text;}};adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(adapter);}
}
