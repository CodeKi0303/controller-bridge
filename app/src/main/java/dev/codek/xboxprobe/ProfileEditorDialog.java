package dev.codek.xboxprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

import dev.codek.xboxprobe.core.ControllerProfile;
import dev.codek.xboxprobe.core.GamepadSourceLayout;
import dev.codek.xboxprobe.core.SourceBindings;

/** Native editor only; ProfileStore and ControllerProfile remain the persistence/validation owners. */
public final class ProfileEditorDialog {
    private ProfileEditorDialog() {}

    public static void show(Activity activity, ProfileStore store, ControllerProfile profile, List<String> affectedGames, Runnable onSaved) {
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(profile, "profile");
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new Editor(activity, store, profile, affectedGames, onSaved).show();
    }

    private static final class Option {
        final int value;
        final String label;
        Option(int value, String label) { this.value = value; this.label = label; }
    }

    private static final class Editor {
        private static final int BG = 0xff101820, CARD = 0xff1b2935, FIELD = 0xff111d27;
        private static final int TEXT = 0xffedf4f7, MUTED = 0xff9eb2c2, BORDER = 0xff334655;
        private static final int ACCENT = 0xff91e3b7, ERROR = 0xffffb4ad;
        private final Activity activity;
        private final ProfileStore store;
        private final ControllerProfile original;
        private final Runnable onSaved;
        private final List<String> affectedGameNames;
        private final Dialog dialog;
        private final LinearLayout root, content;
        private final ScrollView scroll;
        private final ButtonMappingDraft buttonDraft;
        private final Map<Integer,TextView> bindingDescriptions=new LinkedHashMap<>();
        private final Map<Integer,Button> clearBindings=new LinkedHashMap<>();
        private final Map<Integer,String> learnedDevices=new LinkedHashMap<>();
        private final Map<GamepadSourceLayout.Control, EditText> sourceAxes = new EnumMap<>(GamepadSourceLayout.Control.class);
        private final Map<SourceBindings.DigitalControl, EditText> sourceKeys = new EnumMap<>(SourceBindings.DigitalControl.class);
        private final Application.ActivityLifecycleCallbacks lifecycle;
        private EditText label, deviceName, vendor, product;
        private Spinner bus;
        private Switch swapSticks, invertLeftX, invertLeftY, invertRightX, invertRightY, swapTriggers;
        private Switch automaticSource, sourceExclusive;
        private SeekBar deadzone;
        private TextView error;
        private Button save;
        private AlertDialog resetConfirmation,discardConfirmation;
        private GamepadButtonCaptureDialog captureDialog;
        private TextView mappingFeedback;
        private final List<Integer> unsupportedTargets=new ArrayList<>();
        private LinearLayout unsupportedBindings;
        private List<String> initialFormState;
        private boolean observingLifecycle;

        Editor(Activity activity, ProfileStore store, ControllerProfile profile, List<String> affectedGames, Runnable onSaved) {
            this.activity = activity;
            this.store = store;
            this.original = profile;
            this.onSaved = onSaved == null ? () -> {} : onSaved;
            affectedGameNames = affectedGames == null ? null : new ArrayList<>(affectedGames);
            buttonDraft=new ButtonMappingDraft(profile.getButtonMappings(),ControllerProfile.supportedTargetButtons(profile.getKind()));
            dialog = new Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar){
                @Override public void cancel(){requestClose();}
            };
            dialog.setOwnerActivity(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCanceledOnTouchOutside(false);
            root = column();
            root.setBackground(shape(BG, 24, BORDER));
            root.setFocusableInTouchMode(true);
            scroll = new ScrollView(activity);
            scroll.setFillViewport(false);
            scroll.setClipToPadding(true);
            header(root);
            content = column();
            content.setPadding(dp(18),0,dp(18),dp(8));
            scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0,1));
            buildFields();
            content.addView(text("저장한 설정은 다음 연결·게임 실행부터 적용됩니다.",12,MUTED));
            initialFormState=formState();
            dialog.setContentView(root);
            lifecycle = new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityDestroyed(Activity owner) {
                    if (owner == activity) dialog.dismiss();
                }
                @Override public void onActivityCreated(Activity owner, Bundle state) {}
                @Override public void onActivityStarted(Activity owner) {}
                @Override public void onActivityResumed(Activity owner) {}
                @Override public void onActivityPaused(Activity owner) {if(owner==activity)cancelCapture();}
                @Override public void onActivityStopped(Activity owner) {}
                @Override public void onActivitySaveInstanceState(Activity owner, Bundle state) {}
            };
            dialog.setOnDismissListener(ignored -> {
                cancelCapture();
                if (resetConfirmation != null && resetConfirmation.isShowing()) resetConfirmation.dismiss();
                if (discardConfirmation != null && discardConfirmation.isShowing()) discardConfirmation.dismiss();
                if (observingLifecycle) {
                    activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
                    observingLifecycle = false;
                }
            });
        }

        void show() {
            activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
            observingLifecycle = true;
            try {dialog.show();}
            catch (RuntimeException failure) {
                activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
                observingLifecycle = false;
                throw failure;
            }
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams attributes = window.getAttributes();
                attributes.dimAmount = 0.65f;
                attributes.gravity = Gravity.CENTER;
                window.setAttributes(attributes);
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                window.getDecorView().requestApplyInsets();
            }
            AdaptiveDialogWindow.attachEditor(activity,dialog,720);
            root.requestFocus();
        }

        private void header(LinearLayout parent) {
            LinearLayout box = column();
            box.setPadding(dp(18), dp(12), dp(18), dp(10));
            TextView title = text("프로필 편집", 20, TEXT);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            box.addView(title);
            LinearLayout actions=new AdaptiveActionRow(activity,130);
            Button cancel=button("취소",false);cancel.setOnClickListener(ignored->requestClose());
            save=button("저장",true);save.setOnClickListener(ignored->save());
            LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.rightMargin=dp(8);
            actions.addView(cancel,left);actions.addView(save,new LinearLayout.LayoutParams(0,-2,1));box.addView(actions);
            error=text("",13,ERROR);error.setVisibility(View.GONE);error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);box.addView(error);
            parent.addView(box, new LinearLayout.LayoutParams(-1, -2));
        }

        private void buildFields() {
            affectedGames();
            LinearLayout naming = card("프로필 이름", "게임 목록과 프로필 선택에 표시됩니다.");
            label = field(naming, "이름", original.getLabel(), false);
            if (original.getKind() == ControllerProfile.Kind.DIRECT) {
                LinearLayout direct = card("컨트롤러 직접 입력", null);
                direct.addView(text("연결한 컨트롤러의 입력을 그대로 사용합니다. 가상 장치를 만들거나 실제 컨트롤러의 이름·ID·버튼 배치를 바꾸지 않습니다.", 14, MUTED));
            } else {
                mappingFields();
                identityFields();
                sourceFields();
                if (original.getKind() == ControllerProfile.Kind.NATIVE || original.getKind() == ControllerProfile.Kind.XBOX) axisFields();
            }
            if (ProfileStore.isBuiltIn(original.getId())) {
                LinearLayout reset = card("기본 설정", "이 프로필만 기본값으로 되돌립니다.");
                Button resetButton = button("기본값으로 초기화", false);
                resetButton.setOnClickListener(ignored -> confirmReset());
                reset.addView(resetButton, new LinearLayout.LayoutParams(-1, -2));
            }
        }

        private void identityFields() {
            LinearLayout identity = card("가상 장치 정보", "이름과 ID는 가상 장치에만 적용됩니다.");
            deviceName = field(identity, "Android에 표시할 장치 이름", original.getDeviceName(), false);
            deviceName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            LinearLayout row = new AdaptiveActionRow(activity,150);
            LinearLayout left = column(), right = column();
            LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1);
            half.rightMargin = dp(10);
            row.addView(left, half);
            row.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
            identity.addView(row);
            vendor = field(left, "VID · 16진수", String.format(Locale.ROOT, "%04X", original.getVendorId()), true);
            product = field(right, "PID · 16진수", String.format(Locale.ROOT, "%04X", original.getProductId()), true);
            product.setImeOptions(EditorInfo.IME_ACTION_DONE);
            product.setOnEditorActionListener((view, action, event) -> {
                if (action != EditorInfo.IME_ACTION_DONE) return false;
                hideKeyboard();root.requestFocus();return true;
            });
            identity.addView(text("버스 유형", 12, MUTED));
            List<Option> buses = new ArrayList<>();
            buses.add(new Option(3, "USB"));buses.add(new Option(5, "Bluetooth 형식"));buses.add(new Option(6, "가상 버스"));
            bus = spinner(buses, original.getBus());identity.addView(bus, new LinearLayout.LayoutParams(-1, -2));
            identity.addView(text("Bluetooth 형식은 장치 정보만 바꾸며 무선 페어링을 만들지는 않습니다.", 12, MUTED));
        }

        private void mappingFields() {
            LinearLayout mapping=card("버튼 매핑", "보낼 버튼을 선택한 뒤, 연결할 실제 패드 버튼을 하나 눌렀다 놓으세요.");
            mapping.addView(text("새로 지정하면 해당 출력의 기존 연결을 교체합니다. 변경은 위의 저장 버튼으로 확정합니다.",12,MUTED));
            mappingFeedback=text("",13,ACCENT);mappingFeedback.setVisibility(View.GONE);
            mappingFeedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);mapping.addView(mappingFeedback);
            for(int target:ControllerProfile.supportedTargetButtons(original.getKind())) {
                if(original.getKind()==ControllerProfile.Kind.NATIVE&&!original.nativeProfile().getLayout().supportsButton(target)){unsupportedTargets.add(target);continue;}
                LinearLayout row=new AdaptiveActionRow(activity,150);
                Button choose=button(outputLabel(target),false);
                choose.setContentDescription(outputLabel(target)+" 입력 지정");
                choose.setOnClickListener(view->captureButton(target));
                LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.rightMargin=dp(8);
                row.addView(choose,left);
                Button clear=button("연결 해제",false);
                clear.setContentDescription(outputLabel(target)+" 연결 해제");
                clear.setOnClickListener(view->{buttonDraft.clearTarget(target);refreshBindings();mappingNotice(outputLabel(target)+"의 버튼 연결을 해제했습니다. 저장하면 적용됩니다.");});
                row.addView(clear,new LinearLayout.LayoutParams(0,-2,1));
                mapping.addView(row,new LinearLayout.LayoutParams(-1,-2));clearBindings.put(target,clear);
                TextView description=text("",12,MUTED);bindingDescriptions.put(target,description);mapping.addView(description);
                View divider=new View(activity);divider.setBackgroundColor(BORDER);
                LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,dp(1));line.topMargin=dp(8);line.bottomMargin=dp(10);mapping.addView(divider,line);
            }
            Button disableInput=button("사용 안 함 · 버튼을 눌러 지정",false);
            disableInput.setOnClickListener(view->captureButton(0));mapping.addView(disableInput,new LinearLayout.LayoutParams(-1,-2));
            if(!unsupportedTargets.isEmpty()){
                unsupportedBindings=column();mapping.addView(unsupportedBindings,new LinearLayout.LayoutParams(-1,-2));
                unsupportedBindings.addView(text("이 출력에서 지원하지 않는 C/Z의 기존 연결이 있습니다. 해당 버튼을 다른 출력에 지정하거나 연결을 해제할 수 있습니다.",12,MUTED));
                Button clearUnsupported=button("미지원 C/Z 연결 해제",false);
                clearUnsupported.setOnClickListener(view->{for(int target:unsupportedTargets)buttonDraft.clearTarget(target);refreshBindings();mappingNotice("지원하지 않는 출력의 연결을 해제했습니다. 저장하면 적용됩니다.");});
                unsupportedBindings.addView(clearUnsupported,new LinearLayout.LayoutParams(-1,-2));
            }
            mapping.addView(text("스틱과 축 방식의 방향키·트리거는 아래 자동 판별 설정을 사용합니다. Home 등 Android가 앱에 전달하지 않는 버튼은 입력 지정이 어려울 수 있습니다.",12,MUTED));
            refreshBindings();
        }

        private void refreshBindings(){
            for(Map.Entry<Integer,TextView> row:bindingDescriptions.entrySet()){
                List<Integer> sources=buttonDraft.sourcesFor(row.getKey());
                String description=sources.isEmpty()?"연결된 버튼 없음":"저장된 입력 "+sources.size()+"개 · 출력 버튼을 눌러 다시 지정";
                if(sources.size()==1&&learnedDevices.containsKey(sources.get(0)))description="방금 누른 버튼 · "+learnedDevices.get(sources.get(0));
                row.getValue().setText(description);clearBindings.get(row.getKey()).setEnabled(!sources.isEmpty());
            }
            if(unsupportedBindings!=null)unsupportedBindings.setVisibility(unsupportedTargets.stream().anyMatch(target->!buttonDraft.sourcesFor(target).isEmpty())?View.VISIBLE:View.GONE);
        }
        private void mappingNotice(String message){mappingFeedback.setText(message);mappingFeedback.setVisibility(View.VISIBLE);}
        private void cancelCapture(){GamepadButtonCaptureDialog capture=captureDialog;captureDialog=null;if(capture!=null)capture.dismiss();}
        private void captureButton(int target){
            if(activity.isFinishing()||activity.isDestroyed()||!dialog.isShowing())return;
            cancelCapture();hideKeyboard();root.requestFocus();
            Set<Integer> excluded;
            try{
                SourceBindings source=readSourceBindings();
                excluded=source.isAutomatic()?Set.of(312,313,544,545,546,547):new HashSet<>(source.getKeyBindings().values());
            }catch(RuntimeException failure){showError("원본 컨트롤러 배치를 확인해 주세요.",failure);return;}
            captureDialog=GamepadButtonCaptureDialog.show(activity,outputLabel(target),excluded,(source,keyCode,device)->{
                if(!dialog.isShowing()||activity.isFinishing()||activity.isDestroyed())return;
                int previous=buttonDraft.snapshot().getOrDefault(source,0);
                if(target==0)buttonDraft.disableSource(source);else buttonDraft.replaceTarget(target,source);
                learnedDevices.put(source,device);refreshBindings();
                String message=target==0?"누른 버튼을 사용하지 않도록 설정했습니다. 저장하면 적용됩니다.":outputLabel(target)+"에 누른 버튼을 연결했습니다. 저장하면 적용됩니다.";
                if(target!=0&&previous!=0&&previous!=target)message+=" 기존 "+outputLabel(previous)+" 연결은 새 출력으로 옮겼습니다.";
                mappingNotice(message);
            });
        }
        private String outputLabel(int code){
            if(code==0)return "사용 안 함";
            boolean ps4=ProfileStore.PS4_ID.equals(original.getId()),ps5=ProfileStore.PS5_ID.equals(original.getId());
            if(ps4||ps5)switch(code){
                case 304:return "× / A · 아래";case 305:return "○ / B · 오른쪽";
                case 307:return "□ / X · 왼쪽";case 308:return "△ / Y · 위";
                case 310:return "L1 / LB";case 311:return "R1 / RB";
                case 312:return "L2 · 버튼 입력";case 313:return "R2 · 버튼 입력";
                case 314:return ps5?"Create / Select":"Share / Select";
                case 315:return "Options / Start";case 316:return "PS / Home";
                default:break;
            }
            return targetLabel(code);
        }

        private void sourceFields() {
            SourceBindings source = original.getSourceBindings();
            LinearLayout box = card("원본 컨트롤러 배치", "자동 판별이 맞지 않고 원본 Linux ABS·KEY 코드를 알고 있을 때만 직접 지정하세요. 컨트롤러 화면에서는 원본 코드를 조회할 수 없습니다.");
            automaticSource = toggle(box, "스틱·방향키·트리거 자동 판별", source.isAutomatic());
            LinearLayout explicit = column();
            explicit.addView(text("ABS는 축, KEY는 버튼의 10진수 코드입니다. 빈칸은 사용하지 않습니다. 축과 버튼에 같은 동작을 중복 지정하지 마세요.", 12, MUTED));
            for (GamepadSourceLayout.Control control : GamepadSourceLayout.Control.values()) {
                Integer code = source.getAxisBindings().get(control);
                EditText field = field(explicit, controlLabel(control) + " · ABS 0~63", code == null ? "" : code.toString(), false);
                field.setInputType(InputType.TYPE_CLASS_NUMBER);
                sourceAxes.put(control, field);
            }
            explicit.addView(text("버튼 방식의 방향키·트리거", 15, TEXT));
            for (SourceBindings.DigitalControl control : SourceBindings.DigitalControl.values()) {
                Integer code = source.getKeyBindings().get(control);
                EditText field = field(explicit, digitalLabel(control) + " · KEY 1~767", code == null ? "" : code.toString(), false);
                field.setInputType(InputType.TYPE_CLASS_NUMBER);
                sourceKeys.put(control, field);
            }
            box.addView(explicit, new LinearLayout.LayoutParams(-1, -2));
            explicit.setVisibility(source.isAutomatic() ? View.GONE : View.VISIBLE);
            automaticSource.setOnCheckedChangeListener((button, automatic) -> explicit.setVisibility(automatic ? View.GONE : View.VISIBLE));
            LinearLayout exclusive = card("원본 입력 처리", null);
            sourceExclusive = toggle(exclusive, "원본 입력 중복 방지(실험)", original.isSourceExclusive());
            exclusive.addView(text("변환 중에는 선택한 원본 입력을 앱이 단독으로 받습니다. 실제 컨트롤러의 이름은 계속 표시됩니다. 단독 접근에 실패하면 연결을 시작하지 않습니다.", 12, MUTED));
        }

        private void axisFields() {
            ControllerProfile.AxisConfig axes = original.getAxes();
            LinearLayout box = card("스틱과 트리거", "스틱 교환 후 방향 반전과 데드존이 적용됩니다.");
            swapSticks = toggle(box, "왼쪽 · 오른쪽 스틱 교환", axes.isSwapSticks());
            invertLeftX = toggle(box, "왼쪽 스틱 좌우 반전", axes.isInvertLeftX());
            invertLeftY = toggle(box, "왼쪽 스틱 상하 반전", axes.isInvertLeftY());
            invertRightX = toggle(box, "오른쪽 스틱 좌우 반전", axes.isInvertRightX());
            invertRightY = toggle(box, "오른쪽 스틱 상하 반전", axes.isInvertRightY());
            swapTriggers = toggle(box, "LT · RT 교환", axes.isSwapTriggers());
            TextView amount = text("스틱 데드존  " + axes.getDeadzonePercent() + "%", 14, TEXT);
            amount.setPadding(0, dp(16), 0, dp(4));box.addView(amount);
            deadzone = new SeekBar(activity);deadzone.setMax(40);deadzone.setProgress(axes.getDeadzonePercent());
            deadzone.setContentDescription("스틱 데드존");deadzone.setStateDescription(axes.getDeadzonePercent() + "퍼센트");
            deadzone.setProgressTintList(ColorStateList.valueOf(ACCENT));deadzone.setThumbTintList(ColorStateList.valueOf(ACCENT));
            deadzone.setProgressBackgroundTintList(ColorStateList.valueOf(BORDER));
            deadzone.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seek, int value, boolean fromUser) {
                    amount.setText("스틱 데드존  " + value + "%");seek.setStateDescription(value + "퍼센트");
                }
                @Override public void onStartTrackingTouch(SeekBar seek) {}
                @Override public void onStopTrackingTouch(SeekBar seek) {}
            });
            box.addView(deadzone, new LinearLayout.LayoutParams(-1, dp(44)));
            box.addView(text("0%는 추가 데드존 없음 · 최대 40%", 12, MUTED));
        }

        private void affectedGames(){
            LinearLayout box=card("공유하는 게임", null);
            box.addView(text(affectedGameNames==null?"게임 목록을 아직 확인하지 못했습니다."
                    :affectedGameNames.isEmpty()?"현재 목록에서 사용하는 게임이 없습니다.":String.join(" · ",affectedGameNames),14,TEXT));
            box.addView(text("이 프로필을 사용하는 모든 게임에 공통 적용됩니다. 변경은 다음 실행부터 반영됩니다.",12,MUTED));
        }

        private List<String> formState(){
            List<String> state=new ArrayList<>();
            for(EditText field:new EditText[]{label,deviceName,vendor,product})if(field!=null)state.add(field.getText().toString());
            if(bus!=null)state.add("bus:"+selected(bus));
            for(Map.Entry<Integer,Integer> binding:buttonDraft.snapshot().entrySet())state.add("button:"+binding.getKey()+":"+binding.getValue());
            for(Switch toggle:new Switch[]{swapSticks,invertLeftX,invertLeftY,invertRightX,invertRightY,swapTriggers,automaticSource,sourceExclusive})if(toggle!=null)state.add(Boolean.toString(toggle.isChecked()));
            if(deadzone!=null)state.add("deadzone:"+deadzone.getProgress());
            for(Map.Entry<GamepadSourceLayout.Control,EditText> field:sourceAxes.entrySet())state.add(field.getKey()+":"+field.getValue().getText());
            for(Map.Entry<SourceBindings.DigitalControl,EditText> field:sourceKeys.entrySet())state.add(field.getKey()+":"+field.getValue().getText());
            return state;
        }
        private void requestClose(){
            if(initialFormState==null||initialFormState.equals(formState())){hideKeyboard();dialog.dismiss();return;}
            if(discardConfirmation!=null&&discardConfirmation.isShowing())return;
            discardConfirmation=new AlertDialog.Builder(activity).setTitle("변경 내용을 버릴까요?")
                    .setMessage("아직 저장하지 않은 프로필 변경이 있습니다.")
                    .setNegativeButton("계속 편집",null).setPositiveButton("변경 버리기",(which,button)->{hideKeyboard();dialog.dismiss();}).create();
            discardConfirmation.show();AdaptiveDialogWindow.attach(activity,discardConfirmation,560);
            discardConfirmation.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ACCENT);
        }

        private void save() {
            error.setVisibility(View.GONE);label.setError(null);
            if (vendor != null) {vendor.setError(null);product.setError(null);deviceName.setError(null);}
            for (EditText field : sourceAxes.values()) field.setError(null);
            for (EditText field : sourceKeys.values()) field.setError(null);
            save.setEnabled(false);
            try {
                if (label.getText().toString().trim().isEmpty()) throw invalid(label, "프로필 이름을 입력해 주세요.");
                String name = original.getDeviceName();int vid = original.getVendorId(),pid = original.getProductId(),busValue = original.getBus();
                if (original.getKind() != ControllerProfile.Kind.DIRECT) {
                    if (deviceName.getText().toString().trim().isEmpty()) throw invalid(deviceName, "장치 이름을 입력해 주세요.");
                    name = deviceName.getText().toString();vid = readHex(vendor, "VID");pid = readHex(product, "PID");busValue = selected(bus);
                }
                Map<Integer,Integer> buttons=buttonDraft.snapshot();
                Map<Integer,Integer> keyboard=original.getKeyboardBindings();
                ControllerProfile.AxisConfig axes = original.getAxes();
                if (deadzone != null) axes = new ControllerProfile.AxisConfig(swapSticks.isChecked(),invertLeftX.isChecked(),
                        invertLeftY.isChecked(),invertRightX.isChecked(),invertRightY.isChecked(),swapTriggers.isChecked(),deadzone.getProgress());
                SourceBindings source=readSourceBindings();
                ControllerProfile updated = new ControllerProfile(original.getId(),label.getText().toString(),original.getKind(),
                        name,vid,pid,busValue,buttons,keyboard,axes,source,sourceExclusive != null && sourceExclusive.isChecked());
                store.saveProfile(updated);
            } catch (RuntimeException failure) {
                showError("저장할 수 없습니다. 입력한 설정을 확인해 주세요.", failure);
                return;
            } finally {save.setEnabled(true);}
            hideKeyboard();dialog.dismiss();onSaved.run();
        }

        private SourceBindings readSourceBindings(){
            SourceBindings source = SourceBindings.AUTO;
            if (automaticSource != null && !automaticSource.isChecked()) {
                Map<GamepadSourceLayout.Control,Integer> axisBindings = new EnumMap<>(GamepadSourceLayout.Control.class);
                Map<SourceBindings.DigitalControl,Integer> keyBindings = new EnumMap<>(SourceBindings.DigitalControl.class);
                for (Map.Entry<GamepadSourceLayout.Control,EditText> field : sourceAxes.entrySet()) {
                    Integer code = readSourceCode(field.getValue(), controlLabel(field.getKey()), 0, 63);
                    if (code != null) axisBindings.put(field.getKey(), code);
                }
                for (Map.Entry<SourceBindings.DigitalControl,EditText> field : sourceKeys.entrySet()) {
                    Integer code = readSourceCode(field.getValue(), digitalLabel(field.getKey()), 1, 767);
                    if (code != null) keyBindings.put(field.getKey(), code);
                }
                source = new SourceBindings(false, axisBindings, keyBindings);
            }
            return source;
        }

        private void confirmReset() {
            String message = "이 프로필의 이름, 장치 정보, 버튼 매핑, 원본 배치와 입력 설정을 기본값으로 되돌립니다.";
            if (original.getKind() == ControllerProfile.Kind.KEYBOARD) message += "\n\n이전에 저장했거나 이전 앱에서 가져온 키보드 매핑도 기본값으로 바뀝니다.";
            resetConfirmation = new AlertDialog.Builder(activity).setTitle("기본값으로 되돌릴까요?")
                    .setMessage(message).setNegativeButton("취소", null).setPositiveButton("초기화", (which, button) -> {
                        try {store.resetBuiltin(original.getId());}
                        catch (RuntimeException failure) {showError("초기화 결과를 확인해 주세요.", failure);return;}
                        hideKeyboard();dialog.dismiss();onSaved.run();
                    }).create();
            resetConfirmation.show();
            AdaptiveDialogWindow.attach(activity,resetConfirmation,560);
            resetConfirmation.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ACCENT);
            resetConfirmation.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(MUTED);
        }

        private int readHex(EditText field, String label) {
            String value = field.getText().toString().trim();
            if (value.startsWith("0x") || value.startsWith("0X")) value = value.substring(2);
            if (!value.matches("[0-9a-fA-F]{4}")) throw invalid(field, label + "는 0000~FFFF 범위의 16진수 네 자리로 입력해 주세요.");
            return Integer.parseInt(value,16);
        }
        private Integer readSourceCode(EditText field, String label, int min, int max) {
            String value = field.getText().toString().trim();
            if (value.isEmpty()) return null;
            if (!value.matches("[0-9]{1,3}")) throw invalid(field, label + "는 " + min + "~" + max + " 범위의 10진수로 입력해 주세요.");
            int code = Integer.parseInt(value);
            if (code < min || code > max) throw invalid(field, label + "는 " + min + "~" + max + " 범위입니다.");
            return code;
        }
        private IllegalArgumentException invalid(EditText field,String message) {field.setError(message);field.requestFocus();return new IllegalArgumentException(message);}
        private void showError(String summary,RuntimeException failure) {error.setText(summary+"\n"+(failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage()));error.setVisibility(View.VISIBLE);error.post(()->error.requestRectangleOnScreen(new Rect(0,0,error.getWidth(),error.getHeight()),false));}
        private void hideKeyboard() {InputMethodManager manager=activity.getSystemService(InputMethodManager.class);if(manager!=null)manager.hideSoftInputFromWindow(root.getWindowToken(),0);}

        private Spinner spinner(List<Option> options,int current) {
            Spinner spinner=new Spinner(activity,Spinner.MODE_DROPDOWN);spinner.setBackground(shape(FIELD,10,BORDER));
            spinner.setMinimumHeight(dp(48));
            spinner.setPopupBackgroundDrawable(new ColorDrawable(CARD));spinner.setAdapter(new OptionsAdapter(options));
            for(int i=0;i<options.size();i++)if(options.get(i).value==current){spinner.setSelection(i);break;}
            return spinner;
        }
        private int selected(Spinner spinner) {Object selected=spinner.getSelectedItem();if(!(selected instanceof Option))throw new IllegalStateException("선택한 항목을 다시 확인해 주세요.");return ((Option)selected).value;}
        private final class OptionsAdapter extends BaseAdapter {
            private final List<Option> options;
            OptionsAdapter(List<Option> options){this.options=new ArrayList<>(options);}
            @Override public int getCount(){return options.size();}
            @Override public Option getItem(int position){return options.get(position);}
            @Override public long getItemId(int position){return getItem(position).value;}
            @Override public View getView(int position,View convertView,ViewGroup parent){return row(position,convertView,false);}
            @Override public View getDropDownView(int position,View convertView,ViewGroup parent){return row(position,convertView,true);}
            private TextView row(int position,View recycled,boolean popup){TextView item=recycled instanceof TextView?(TextView)recycled:text("",14,TEXT);item.setText(getItem(position).label);item.setMinHeight(dp(48));item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(12),dp(8),dp(12),dp(8));item.setSingleLine(false);item.setEllipsize(null);item.setBackgroundColor(popup?CARD:Color.TRANSPARENT);return item;}
        }
        private EditText field(LinearLayout parent,String title,String value,boolean hex) {
            parent.addView(text(title,12,MUTED));EditText edit=new EditText(activity);edit.setText(value);edit.setSingleLine(true);edit.setTextSize(15);
            edit.setTextColor(TEXT);edit.setHintTextColor(MUTED);edit.setPadding(dp(12),dp(10),dp(12),dp(10));edit.setBackground(shape(FIELD,10,BORDER));
            edit.setInputType(InputType.TYPE_CLASS_TEXT|(hex?InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS:0));
            edit.setImeOptions(EditorInfo.IME_ACTION_NEXT);edit.setSelectAllOnFocus(true);
            edit.setMinHeight(dp(48));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(12);parent.addView(edit,params);return edit;
        }
        private Switch toggle(LinearLayout parent,String title,boolean checked) {
            Switch toggle=new Switch(activity);toggle.setText(title);toggle.setTextColor(TEXT);toggle.setTextSize(14);toggle.setShowText(false);toggle.setChecked(checked);toggle.setPadding(0,dp(6),0,dp(6));
            toggle.setSingleLine(false);toggle.setMinHeight(dp(52));
            toggle.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{ACCENT,0xff8295a5}));
            toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{0xff477761,0xff364856}));
            parent.addView(toggle,new LinearLayout.LayoutParams(-1,-2));return toggle;
        }
        private LinearLayout card(String title,String hint) {LinearLayout card=column();card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackground(shape(CARD,16,BORDER));TextView heading=text(title,17,TEXT);heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);card.addView(heading);if(hint!=null)card.addView(text(hint,12,MUTED));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(14);content.addView(card,params);return card;}
        private Button button(String label,boolean primary) {Button button=new Button(activity);button.setAllCaps(false);button.setText(label);button.setTextSize(14);button.setMinHeight(dp(48));button.setPadding(dp(12),dp(10),dp(12),dp(10));button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);button.setTextColor(primary?0xff10291c:TEXT);button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),shape(primary?ACCENT:CARD,13,primary?ACCENT:BORDER),null));return button;}
        private TextView text(String value,int size,int color) {TextView text=new TextView(activity);text.setText(value);text.setTextSize(size);text.setTextColor(color);text.setPadding(0,dp(4),0,dp(6));return text;}
        private LinearLayout column() {LinearLayout layout=new LinearLayout(activity);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
        private GradientDrawable shape(int fill,int radius,int stroke) {GradientDrawable shape=new GradientDrawable();shape.setColor(fill);shape.setCornerRadius(dp(radius));shape.setStroke(dp(1),stroke);return shape;}
        private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    }

    private static String controlLabel(GamepadSourceLayout.Control control) {
        switch (control) {
            case LEFT_X: return "왼쪽 스틱 좌우";
            case LEFT_Y: return "왼쪽 스틱 상하";
            case RIGHT_X: return "오른쪽 스틱 좌우";
            case RIGHT_Y: return "오른쪽 스틱 상하";
            case LEFT_TRIGGER: return "왼쪽 트리거";
            case RIGHT_TRIGGER: return "오른쪽 트리거";
            case HAT_X: return "방향키 좌우";
            case HAT_Y: return "방향키 상하";
            default: throw new IllegalArgumentException("Unknown source control");
        }
    }
    private static String digitalLabel(SourceBindings.DigitalControl control) {
        switch (control) {
            case LEFT_TRIGGER: return "왼쪽 트리거";
            case RIGHT_TRIGGER: return "오른쪽 트리거";
            case DPAD_UP: return "방향키 위";
            case DPAD_DOWN: return "방향키 아래";
            case DPAD_LEFT: return "방향키 왼쪽";
            case DPAD_RIGHT: return "방향키 오른쪽";
            default: throw new IllegalArgumentException("Unknown digital source control");
        }
    }
    private static String targetLabel(int code) {
        switch(code){case 304:return "A";case 305:return "B";case 306:return "C · 추가 버튼";case 307:return "X";case 308:return "Y";case 309:return "Z · 추가 버튼";case 310:return "LB";case 311:return "RB";case 312:return "LT · 디지털";case 313:return "RT · 디지털";case 314:return "View / Select";case 315:return "Menu / Start";case 316:return "Home / Guide";case 317:return "L3 · 왼쪽 스틱";case 318:return "R3 · 오른쪽 스틱";default:return "버튼 "+code;}
    }
}
