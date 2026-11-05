package dev.codek.xboxprobe;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Foreground-only raw button learning. No injection, shell permission or pairing is used. */
public final class GamepadButtonCaptureDialog extends Dialog implements InputManager.InputDeviceListener {
    public interface Callback { void onCaptured(int scanCode,int androidKeyCode,String deviceName); }
    private static final int BG=0xff101820,CARD=0xff1b2935,TEXT=0xffedf4f7,MUTED=0xff9eb2c2,ACCENT=0xff91e3b7;
    private static final long WAIT_MS=20_000;
    private final Activity owner;
    private final Callback callback;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final InputManager input;
    private final ButtonCaptureGesture gesture;
    private final Map<Integer,Long> revisions=new HashMap<>();
    private final List<DeviceRef> choices=new ArrayList<>();
    private final TextView status;
    private final Spinner devices;
    private final Button retry,stop;
    private final LinearLayout content;
    private ProbeSessionService service;
    private DeviceRef selected;
    private boolean bound,observing,registered,closed,focused,rebuilding,manualRetry,stopRequested;
    private boolean lastReady;
    private long expires;
    private String notice="컨트롤러와 입력 상태를 확인하고 있습니다.";

    private static final class DeviceRef {
        final int id,vendor,product;
        final long revision;
        final String descriptor,name;
        DeviceRef(InputDevice device,long revision) {
            id=device.getId();vendor=device.getVendorId();product=device.getProductId();
            descriptor=device.getDescriptor();name=device.getName();this.revision=revision;
        }
        boolean matches(InputDevice device,long currentRevision) {
            return relevant(device)&&id==device.getId()&&vendor==device.getVendorId()&&product==device.getProductId()
                    &&revision==currentRevision&&Objects.equals(descriptor,device.getDescriptor());
        }
        boolean same(DeviceRef other) {
            return other!=null&&id==other.id&&vendor==other.vendor&&product==other.product
                    &&revision==other.revision&&Objects.equals(descriptor,other.descriptor);
        }
    }

    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            if(closed)return;
            if(binder instanceof ProbeSessionService.LocalBinder)service=((ProbeSessionService.LocalBinder)binder).service();
            else notice="입력 상태를 확인하지 못했습니다. 닫은 뒤 다시 시도해 주세요.";
            refresh();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service=null;invalidate("입력 서비스 연결이 바뀌었습니다. 다시 확인해 주세요.");refresh();
        }
        @Override public void onNullBinding(ComponentName name) {
            service=null;invalidate("입력 상태를 확인하지 못했습니다. 닫은 뒤 다시 시도해 주세요.");refresh();
        }
        @Override public void onBindingDied(ComponentName name) { onNullBinding(name); }
    };
    private final Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
        @Override public void onActivityPaused(Activity activity){if(activity==owner)dismiss();}
        @Override public void onActivityDestroyed(Activity activity){if(activity==owner)dismiss();}
        @Override public void onActivityCreated(Activity activity,Bundle state){}
        @Override public void onActivityStarted(Activity activity){}
        @Override public void onActivityResumed(Activity activity){}
        @Override public void onActivityStopped(Activity activity){}
        @Override public void onActivitySaveInstanceState(Activity activity,Bundle state){}
    };
    private final Runnable tick=new Runnable(){@Override public void run(){
        if(closed)return;refresh();main.postDelayed(this,250);
    }};

    public static GamepadButtonCaptureDialog show(Activity owner,String targetLabel,Set<Integer> excludedRawCodes,Callback callback) {
        Objects.requireNonNull(owner,"owner");Objects.requireNonNull(targetLabel,"targetLabel");Objects.requireNonNull(callback,"callback");
        if(owner.isFinishing()||owner.isDestroyed())throw new IllegalStateException("Profile editor is closing");
        GamepadButtonCaptureDialog dialog=new GamepadButtonCaptureDialog(owner,targetLabel,excludedRawCodes,callback);
        try{
            dialog.show();Window window=dialog.getWindow();
            if(window!=null)window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            AdaptiveDialogWindow.attach(owner,dialog,560);dialog.content.requestFocus();
            return dialog;
        }catch(RuntimeException failure){dialog.dismiss();throw failure;}
    }

    private GamepadButtonCaptureDialog(Activity owner,String targetLabel,Set<Integer> excludedRawCodes,Callback callback) {
        super(owner,android.R.style.Theme_Material_Dialog_NoActionBar);
        this.owner=owner;this.callback=callback;input=owner.getSystemService(InputManager.class);
        gesture=new ButtonCaptureGesture(excludedRawCodes);
        setOwnerActivity(owner);requestWindowFeature(Window.FEATURE_NO_TITLE);setCanceledOnTouchOutside(false);
        ScrollView scroll=new ScrollView(owner);scroll.setFillViewport(false);
        content=new LinearLayout(owner);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(16),dp(20),dp(16));
        content.setBackground(shape(BG));content.setFocusableInTouchMode(true);
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));setContentView(scroll);
        TextView title=text(targetLabel+" 입력 지정",21,TEXT);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        text("연결할 패드 버튼 하나를 눌렀다 떼세요. 스틱·방향키·트리거는 원본 컨트롤러 배치 설정을 사용합니다.",14,MUTED);
        text("입력을 받을 컨트롤러",13,TEXT);
        devices=new Spinner(owner,Spinner.MODE_DROPDOWN);devices.setMinimumHeight(dp(48));
        devices.setPopupBackgroundDrawable(new ColorDrawable(CARD));content.addView(devices,new LinearLayout.LayoutParams(-1,-2));
        devices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                if(rebuilding||closed)return;
                DeviceRef next=position>0&&position<=choices.size()?choices.get(position-1):null;
                if(selected==null?next==null:selected.same(next))return;
                selected=next;gesture.cancel();manualRetry=false;notice="선택한 패드의 버튼 하나를 눌렀다 떼세요.";refresh();
            }
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
        status=text(notice,15,TEXT);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        stop=button("변환 중지",false);stop.setOnClickListener(view->{
            if(service==null||!service.isSessionActive())return;
            gesture.cancel();stopRequested=true;manualRetry=false;
            try{owner.startService(new Intent(owner,ProbeSessionService.class).setAction(ProbeSessionService.STOP));}
            catch(RuntimeException failure){stopRequested=false;notice="변환을 중지하지 못했습니다. 게임 화면에서 중지한 뒤 다시 시도해 주세요.";}
            refresh();
        });
        retry=button("다시 입력받기",true);retry.setOnClickListener(view->{
            gesture.cancel();manualRetry=false;notice="컨트롤러와 입력 상태를 확인하고 있습니다.";
            if(!bound)bindSession();refresh();
        });
        Button cancel=button("취소",false);cancel.setOnClickListener(view->cancel());
    }

    @Override protected void onStart() {
        super.onStart();
        owner.getApplication().registerActivityLifecycleCallbacks(lifecycle);observing=true;
        input.registerInputDeviceListener(this,main);registered=true;refreshDevices();bindSession();main.post(tick);
    }
    private void bindSession() {
        if(closed||bound)return;
        try{bound=owner.bindService(new Intent(owner,ProbeSessionService.class),connection,Context.BIND_AUTO_CREATE);}
        catch(RuntimeException failure){notice="입력 상태를 확인하지 못했습니다. 닫은 뒤 다시 시도해 주세요.";}
        if(!bound)manualRetry=true;
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);focused=hasFocus;
        if(!hasFocus)gesture.cancel();
        if(!closed)refresh();
    }
    @Override public void dismiss() {
        if(!closed){
            closed=true;gesture.cancel();main.removeCallbacksAndMessages(null);
            if(registered){input.unregisterInputDeviceListener(this);registered=false;}
            if(bound){owner.unbindService(connection);bound=false;}
            service=null;
            if(observing){owner.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);observing=false;}
        }
        super.dismiss();
    }

    @Override public void onInputDeviceAdded(int id){revisions.put(id,revision(id)+1);refreshDevices();}
    @Override public void onInputDeviceRemoved(int id){revisions.put(id,revision(id)+1);refreshDevices();}
    @Override public void onInputDeviceChanged(int id){revisions.put(id,revision(id)+1);refreshDevices();}
    private long revision(int id){return revisions.getOrDefault(id,0L);}
    private static boolean relevant(InputDevice device) {
        return device!=null&&device.getId()>=0&&!device.isVirtual()
                &&(device.supportsSource(InputDevice.SOURCE_GAMEPAD)||device.supportsSource(InputDevice.SOURCE_JOYSTICK));
    }
    private void refreshDevices() {
        if(closed)return;
        List<DeviceRef> next=new ArrayList<>();
        for(int id:InputDevice.getDeviceIds()){InputDevice device=InputDevice.getDevice(id);if(relevant(device))next.add(new DeviceRef(device,revision(id)));}
        next.sort(Comparator.comparingInt(device->device.id));
        if(selected!=null&&!selected.matches(InputDevice.getDevice(selected.id),revision(selected.id))) {
            selected=null;invalidate("컨트롤러 연결이 바뀌었습니다. 사용할 패드를 확인하고 다시 입력받기를 누르세요.");
        }
        if(selected==null&&next.size()==1)selected=next.get(0);
        choices.clear();choices.addAll(next);
        List<String> labels=new ArrayList<>();labels.add(next.isEmpty()?"컨트롤러를 연결해 주세요":"컨트롤러 선택");
        int selection=0;
        for(int index=0;index<next.size();index++){
            DeviceRef choice=next.get(index);labels.add(choice.name+" · #"+choice.id);
            if(choice.same(selected))selection=index+1;
        }
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(owner,android.R.layout.simple_spinner_item,labels){
            @Override public View getView(int position,View recycled,ViewGroup parent){return style(super.getView(position,recycled,parent),false);}
            @Override public View getDropDownView(int position,View recycled,ViewGroup parent){return style(super.getDropDownView(position,recycled,parent),true);}
            private View style(View view,boolean popup){TextView label=(TextView)view;label.setTextColor(TEXT);label.setTextSize(14);label.setSingleLine(false);label.setPadding(dp(12),dp(12),dp(12),dp(12));if(popup)label.setBackgroundColor(CARD);return label;}
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        rebuilding=true;devices.setAdapter(adapter);devices.setSelection(selection);rebuilding=false;refresh();
    }
    private void invalidate(String message) { gesture.cancel();manualRetry=true;notice=message; }
    private boolean ready() { return service!=null&&!service.isSessionActive(); }
    private void refresh() {
        if(closed||status==null)return;
        boolean available=ready();
        if(!available&&lastReady)invalidate("입력 상태가 바뀌었습니다. 변환을 중지한 뒤 다시 입력받으세요.");
        lastReady=available;
        devices.setEnabled(available);stop.setVisibility(service!=null&&!available?View.VISIBLE:View.GONE);
        stop.setEnabled(!stopRequested);retry.setVisibility(manualRetry||!bound?View.VISIBLE:View.GONE);
        retry.setEnabled((available&&selected!=null)||!bound);
        if(!available){
            gesture.cancel();
            ProbeSessionService.LaunchState state=service==null?null:service.launchState();
            if(state!=null&&"BLOCKED".equals(state.phase)){
                stopRequested=false;stop.setEnabled(true);
                String reason=state.error==null?"":state.error.trim();
                if(reason.length()>120)reason=reason.substring(0,120)+"…";
                status.setText("변환 정리를 확인하지 못했습니다. 다시 중지하거나 취소 후 게임 화면에서 확인하세요."
                        +(reason.isEmpty()?"":"\n"+reason));
            }else status.setText(service==null?notice:stopRequested?"변환을 정리하고 있습니다. 완료되면 입력을 받을 수 있습니다.":"원본 버튼을 확인하려면 실행 중인 변환을 먼저 중지하세요. 중지 후 자동으로 다시 켜지는 것은 아닙니다.");
            return;
        }
        stopRequested=false;
        if(selected==null){gesture.cancel();status.setText(choices.isEmpty()?"USB 또는 Bluetooth 컨트롤러를 연결하세요.":"위 목록에서 입력을 받을 컨트롤러를 선택하세요.");return;}
        if(!selected.matches(InputDevice.getDevice(selected.id),revision(selected.id))){refreshDevices();return;}
        if(manualRetry){status.setText(notice);return;}
        if(focused&&!gesture.isArmed()){
            long now=SystemClock.uptimeMillis();gesture.arm(now);expires=now+WAIT_MS;
            notice="선택한 패드의 버튼 하나를 눌렀다 떼세요.";
        }
        if(gesture.isArmed()&&SystemClock.uptimeMillis()>=expires){
            invalidate("입력 대기 시간이 끝났습니다. 연결을 확인한 뒤 다시 입력받기를 누르세요.");
            retry.setVisibility(View.VISIBLE);retry.setEnabled(true);
        }
        status.setText(notice);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        InputDevice device=event.getDevice();
        if(!relevant(device))return super.dispatchKeyEvent(event);
        // Controller keys must not activate dialog buttons or navigate Back,
        // including a hardware key Android translated to KEYCODE_BACK.
        if(closed||!focused||!ready()||!gesture.isArmed())return true;
        if(selected==null||!selected.matches(device,revision(device.getId()))){
            notice="선택한 컨트롤러의 버튼을 눌러 주세요.";status.setText(notice);return true;
        }
        if(event.getAction()!=KeyEvent.ACTION_DOWN&&event.getAction()!=KeyEvent.ACTION_UP)return true;
        if(isAxisControlKey(event.getKeyCode())){rejectAxisControl();return true;}
        ButtonCaptureGesture.Result result=gesture.event(event.getAction()==KeyEvent.ACTION_DOWN,event.getScanCode(),
                event.getKeyCode(),event.getRepeatCount(),event.getDownTime(),event.isCanceled());
        switch(result.kind){
            case PRESSED:notice="버튼을 감지했습니다. 손을 떼면 지정됩니다.";break;
            case MULTIPLE:notice="여러 버튼이 눌렸습니다. 모두 뗀 뒤 버튼 하나만 다시 눌러 주세요.";break;
            case RETRY:notice="버튼 하나를 다시 눌렀다 떼세요.";break;
            case UNAVAILABLE:invalidate("이 입력의 원본 버튼 번호를 받지 못했습니다. 다시 입력받기를 누르고 다른 버튼을 사용해 주세요.");refresh();break;
            case EXCLUDED:rejectAxisControl();break;
            case CAPTURED:
                if(SystemClock.uptimeMillis()>=expires||!ready()||!selected.matches(InputDevice.getDevice(selected.id),revision(selected.id))){
                    invalidate("입력 상태가 바뀌었습니다. 다시 입력받기를 눌러 주세요.");refresh();return true;
                }
                String name=selected.name;dismiss();callback.onCaptured(result.scanCode,result.keyCode,name);return true;
            default:break;
        }
        status.setText(notice);return true;
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return relevant(event.getDevice())||super.dispatchGenericMotionEvent(event);
    }
    private void rejectAxisControl(){invalidate("방향키·트리거는 원본 배치 설정을 사용하세요. 다시 입력받기를 누르고 일반 버튼을 지정해 주세요.");refresh();}
    private static boolean isAxisControlKey(int keyCode){
        switch(keyCode){
            case KeyEvent.KEYCODE_DPAD_UP:case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP_LEFT:case KeyEvent.KEYCODE_DPAD_UP_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN_LEFT:case KeyEvent.KEYCODE_DPAD_DOWN_RIGHT:
            case KeyEvent.KEYCODE_BUTTON_L2:case KeyEvent.KEYCODE_BUTTON_R2:return true;
            default:return false;
        }
    }
    private int dp(int value){return Math.round(value*owner.getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(16));return shape;}
    private TextView text(String value,int size,int color){TextView view=new TextView(owner);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setPadding(0,dp(6),0,dp(8));content.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;}
    private Button button(String label,boolean primary){Button view=new Button(owner);view.setText(label);view.setAllCaps(false);view.setTextColor(primary?BG:TEXT);view.setTextSize(14);view.setMinHeight(dp(48));view.setPadding(dp(12),dp(10),dp(12),dp(10));view.setBackground(shape(primary?ACCENT:CARD));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(8);content.addView(view,params);return view;}
}
