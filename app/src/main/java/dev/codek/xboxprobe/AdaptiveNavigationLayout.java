package dev.codek.xboxprobe;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.util.Consumer;
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter;
import androidx.window.layout.DisplayFeature;
import androidx.window.layout.FoldingFeature;
import androidx.window.layout.WindowInfoTracker;
import androidx.window.layout.WindowLayoutInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fixed top app bar and three destinations, independent from the screen's scrolling content. */
public final class AdaptiveNavigationLayout extends ViewGroup {
    public static final int GAMES=0,CONTROLLER=1,PROFILES=2;
    public interface DestinationListener {void onDestinationSelected(int destination);}
    private static final String[] LABELS={"게임","컨트롤러","프로필"};
    private static final int BG=0xff0c121b,SURFACE=0xff14202d,TEXT=0xffedf4fc,MUTED=0xff9aafc4,ACCENT=0xff91e3b7;
    private final Activity owner;
    private final View content;
    private final TextView title;
    private final ChromeIcon overflow,back;
    private final DestinationListener navigation;
    private final DestinationView[] destinations=new DestinationView[3];
    private final Rect safeInsets=new Rect();
    private final Paint backgroundPaint=new Paint();
    private final List<FoldingFeature> folds=new ArrayList<>();
    private final Consumer<WindowLayoutInfo> foldListener=this::onWindowLayoutInfo;
    private WindowInfoTrackerCallbackAdapter tracker;
    private AdaptiveNavigationGeometry.Plan plan;
    private Runnable backAction;
    private int selectedDestination;

    public AdaptiveNavigationLayout(Activity owner,View content,CharSequence title,int selectedDestination,
                                    DestinationListener navigation,Runnable overflowAction) {
        super(owner);this.owner=Objects.requireNonNull(owner,"owner");this.content=Objects.requireNonNull(content,"content");
        this.navigation=Objects.requireNonNull(navigation,"navigation");Objects.requireNonNull(overflowAction,"overflowAction");
        if(content.getParent()!=null)throw new IllegalArgumentException("Navigation content must be unparented");
        owner.getWindow().setDecorFitsSystemWindows(false);
        if(content instanceof AdaptiveScreenLayout)((AdaptiveScreenLayout)content).setExternalInsetsOwner(true);
        setBackgroundColor(BG);setWillNotDraw(false);setClipChildren(true);addView(content);
        this.title=new TextView(owner);this.title.setTextSize(22);this.title.setTextColor(TEXT);
        this.title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);this.title.setGravity(Gravity.CENTER_VERTICAL);
        this.title.setSingleLine(true);this.title.setEllipsize(TextUtils.TruncateAt.END);addView(this.title);setTitle(title);
        back=new ChromeIcon(true);back.setContentDescription("뒤로");back.setVisibility(GONE);
        back.setOnClickListener(view->{if(backAction!=null)backAction.run();});addView(back);
        overflow=new ChromeIcon(false);overflow.setContentDescription("더보기");overflow.setOnClickListener(view->overflowAction.run());addView(overflow);
        for(int i=0;i<destinations.length;i++) {
            final int destination=i;destinations[i]=new DestinationView(i);
            destinations[i].setOnClickListener(view->{if(destination!=this.selectedDestination)navigation.onDestinationSelected(destination);});
            addView(destinations[i]);
        }
        setSelectedDestination(selectedDestination);
        setOnApplyWindowInsetsListener((view,insets)->{
            Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
            if(safeInsets.left!=safe.left||safeInsets.top!=safe.top||safeInsets.right!=safe.right||safeInsets.bottom!=safe.bottom) {
                safeInsets.set(safe.left,safe.top,safe.right,safe.bottom);requestLayout();
            }
            return WindowInsets.CONSUMED;
        });
    }

    public void setTitle(CharSequence value){title.setText(value==null||value.length()==0?"Controller Bridge":value);}
    public void setSelectedDestination(int destination) {
        if(destination<GAMES||destination>PROFILES)throw new IllegalArgumentException("Unknown navigation destination");
        selectedDestination=destination;
        for(int i=0;i<destinations.length;i++)destinations[i].select(i==destination);
    }
    public void setBackAction(Runnable action) {
        backAction=action;back.setVisibility(action==null?GONE:VISIBLE);
        for(DestinationView destination:destinations)destination.setVisibility(action==null?VISIBLE:GONE);
        requestLayout();
    }
    public View getOverflowAnchor(){return overflow;}

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();requestApplyInsets();
        try {
            tracker=new WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(owner));
            tracker.addWindowLayoutInfoListener(owner,owner.getMainExecutor(),foldListener);
        } catch(RuntimeException|LinkageError unavailable){removeTracker();folds.clear();requestLayout();}
    }
    @Override protected void onDetachedFromWindow(){removeTracker();folds.clear();super.onDetachedFromWindow();}
    @Override protected void onConfigurationChanged(Configuration configuration){super.onConfigurationChanged(configuration);requestApplyInsets();requestLayout();}

    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int width=MeasureSpec.getSize(widthSpec),height=MeasureSpec.getSize(heightSpec);setMeasuredDimension(width,height);
        int left=Math.min(width,safeInsets.left),top=Math.min(height,safeInsets.top);
        AdaptiveScreenGeometry.Bounds available=new AdaptiveScreenGeometry.Bounds(left,top,Math.max(left,width-safeInsets.right),Math.max(top,height-safeInsets.bottom));
        int[] origin=new int[2];getLocationInWindow(origin);
        List<AdaptiveScreenGeometry.Feature> features=new ArrayList<>();
        for(FoldingFeature fold:folds) {
            Rect b=fold.getBounds();features.add(new AdaptiveScreenGeometry.Feature(new AdaptiveScreenGeometry.Bounds(
                    b.left-origin[0],b.top-origin[1],b.right-origin[0],b.bottom-origin[1]),
                    fold.getOrientation()==FoldingFeature.Orientation.VERTICAL,fold.isSeparating(),fold.getOcclusionType()==FoldingFeature.OcclusionType.FULL));
        }
        plan=AdaptiveNavigationGeometry.arrange(available,density(),getResources().getConfiguration().fontScale,features,backAction==null);
        if(content instanceof AdaptiveScreenLayout) {
            AdaptiveScreenLayout screen=(AdaptiveScreenLayout)content;
            screen.setExternalWindowOrigin(origin[0]+plan.content.left,origin[1]+plan.content.top);
            AdaptiveScreenGeometry.Bounds modal=AdaptiveScreenGeometry.arrange(available,density(),getResources().getConfiguration().fontScale,features).dialogBounds;
            screen.setExternalDialogBounds(new Rect(origin[0]+modal.left,origin[1]+modal.top,origin[0]+modal.right,origin[1]+modal.bottom));
        }
        measure(content,plan.content.width(),plan.content.height());
        int touch=Math.min(dp(48),plan.appBar.height()),margin=dp(12);
        measure(overflow,touch,touch);if(backAction!=null)measure(back,touch,touch);
        measure(title,Math.max(0,plan.appBar.width()-2*margin-touch-(backAction==null?0:touch+dp(8))),plan.appBar.height());
        if(plan.navigation!=null)for(int i=0;i<destinations.length;i++) {
            int start=plan.rail?plan.navigation.height()*i/3:plan.navigation.width()*i/3;
            int end=plan.rail?plan.navigation.height()*(i+1)/3:plan.navigation.width()*(i+1)/3;
            measure(destinations[i],plan.rail?plan.navigation.width():end-start,plan.rail?end-start:plan.navigation.height());
        }
    }

    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
        if(plan==null)return;
        layout(content,plan.content);int margin=dp(12),touch=overflow.getMeasuredWidth();
        int buttonTop=plan.appBar.top+(plan.appBar.height()-overflow.getMeasuredHeight())/2;
        int trailing=plan.appBar.right-margin;
        overflow.layout(trailing-touch,buttonTop,trailing,buttonTop+overflow.getMeasuredHeight());
        int titleLeft=plan.appBar.left+margin;
        if(backAction!=null){back.layout(titleLeft,buttonTop,titleLeft+touch,buttonTop+back.getMeasuredHeight());titleLeft+=touch+dp(8);}
        title.layout(titleLeft,plan.appBar.top,titleLeft+title.getMeasuredWidth(),plan.appBar.bottom);
        if(plan.navigation!=null)for(int i=0;i<destinations.length;i++) {
            int start=plan.rail?plan.navigation.height()*i/3:plan.navigation.width()*i/3;
            int end=plan.rail?plan.navigation.height()*(i+1)/3:plan.navigation.width()*(i+1)/3;
            if(plan.rail)destinations[i].layout(plan.navigation.left,plan.navigation.top+start,plan.navigation.right,plan.navigation.top+end);
            else destinations[i].layout(plan.navigation.left+start,plan.navigation.top,plan.navigation.left+end,plan.navigation.bottom);
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);if(plan==null)return;backgroundPaint.setColor(SURFACE);
        canvas.drawRect(plan.appBar.left,plan.appBar.top,plan.appBar.right,plan.appBar.bottom,backgroundPaint);
        if(plan.navigation!=null)canvas.drawRect(plan.navigation.left,plan.navigation.top,plan.navigation.right,plan.navigation.bottom,backgroundPaint);
    }

    private void onWindowLayoutInfo(WindowLayoutInfo info) {
        if(!isAttachedToWindow())return;
        folds.clear();for(DisplayFeature feature:info.getDisplayFeatures())if(feature instanceof FoldingFeature)folds.add((FoldingFeature)feature);
        requestLayout();
    }
    private void removeTracker() {
        WindowInfoTrackerCallbackAdapter current=tracker;tracker=null;
        if(current!=null)try{current.removeWindowLayoutInfoListener(foldListener);}catch(RuntimeException|LinkageError ignored){}
    }
    private float density(){return getResources().getDisplayMetrics().density;}
    private int dp(float value){return Math.round(value*density());}
    private static void measure(View view,int width,int height){view.measure(MeasureSpec.makeMeasureSpec(Math.max(0,width),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(0,height),MeasureSpec.EXACTLY));}
    private static void layout(View view,AdaptiveScreenGeometry.Bounds b){view.layout(b.left,b.top,b.right,b.bottom);}
    private RippleDrawable ripple() {
        GradientDrawable mask=new GradientDrawable();mask.setColor(0xffffffff);mask.setCornerRadius(dp(24));
        return new RippleDrawable(ColorStateList.valueOf(0x2478dfbd),null,mask);
    }

    private final class DestinationView extends LinearLayout {
        final NavigationIcon icon;
        final TextView label;
        DestinationView(int destination) {
            super(owner);setOrientation(VERTICAL);setGravity(Gravity.CENTER);setPadding(dp(4),dp(6),dp(4),dp(6));
            setMinimumWidth(dp(48));setMinimumHeight(dp(48));setClickable(true);setFocusable(true);setBackground(ripple());
            setContentDescription(LABELS[destination]);
            icon=new NavigationIcon(destination);icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(icon,new LinearLayout.LayoutParams(dp(64),dp(32)));
            label=new TextView(owner);label.setText(LABELS[destination]);label.setTextSize(12);label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams textParams=new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);textParams.topMargin=dp(4);addView(label,textParams);
        }
        void select(boolean selected){setSelected(selected);label.setTextColor(selected?ACCENT:MUTED);label.setTypeface(Typeface.DEFAULT,selected?Typeface.BOLD:Typeface.NORMAL);icon.selected=selected;icon.invalidate();}
        @Override public CharSequence getAccessibilityClassName(){return "android.widget.Button";}
    }
    private final class NavigationIcon extends View {
        final int destination;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);boolean selected;
        final RectF tileBounds=new RectF();
        final Path pad=new Path();
        NavigationIcon(int destination){
            super(owner);this.destination=destination;
            pad.moveTo(7,6);pad.lineTo(17,6);pad.cubicTo(20,6,21,11,22,16);pad.cubicTo(22,20,19,20,16,16);
            pad.lineTo(8,16);pad.cubicTo(5,20,2,20,2,16);pad.cubicTo(3,11,4,6,7,6);pad.close();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);paint.setStyle(Paint.Style.FILL);
            if(selected){paint.setColor(0xff2d5146);canvas.drawRoundRect(0,0,getWidth(),getHeight(),dp(16),dp(16),paint);}
            canvas.save();canvas.translate((getWidth()-dp(24))/2f,(getHeight()-dp(24))/2f);canvas.scale(density(),density());
            paint.setColor(selected?ACCENT:MUTED);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            if(destination==GAMES) {
                for(int x=3;x<=13;x+=10)for(int y=3;y<=13;y+=10){tileBounds.set(x,y,x+7,y+7);canvas.drawRoundRect(tileBounds,1.5f,1.5f,paint);}
            } else if(destination==CONTROLLER) {
                canvas.drawPath(pad,paint);
                canvas.drawLine(6,11,10,11,paint);canvas.drawLine(8,9,8,13,paint);canvas.drawCircle(16,10,.6f,paint);canvas.drawCircle(18,13,.6f,paint);
            } else {
                for(int y=6;y<=18;y+=6)canvas.drawLine(3,y,21,y,paint);
                paint.setStyle(Paint.Style.FILL);paint.setColor(selected?0xff2d5146:SURFACE);
                for(int i=0;i<3;i++)canvas.drawCircle(i==1?15:8,6+6*i,2.5f,paint);
                paint.setStyle(Paint.Style.STROKE);paint.setColor(selected?ACCENT:MUTED);
                for(int i=0;i<3;i++)canvas.drawCircle(i==1?15:8,6+6*i,2.5f,paint);
            }
            canvas.restore();
        }
    }
    private final class ChromeIcon extends View {
        final boolean backIcon;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        ChromeIcon(boolean backIcon){super(owner);this.backIcon=backIcon;setClickable(true);setFocusable(true);setMinimumWidth(dp(48));setMinimumHeight(dp(48));setBackground(ripple());}
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);canvas.save();canvas.translate(getWidth()/2f,getHeight()/2f);canvas.scale(density(),density());paint.setColor(TEXT);
            if(backIcon){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);canvas.drawLine(8,0,-8,0,paint);canvas.drawLine(-8,0,-1,-7,paint);canvas.drawLine(-8,0,-1,7,paint);}
            else {paint.setStyle(Paint.Style.FILL);for(int y=-6;y<=6;y+=6)canvas.drawCircle(0,y,1.8f,paint);}
            canvas.restore();
        }
        @Override public CharSequence getAccessibilityClassName(){return "android.widget.Button";}
    }
}
