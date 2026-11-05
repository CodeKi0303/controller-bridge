package dev.codek.xboxprobe;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.core.util.Consumer;
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter;
import androidx.window.layout.DisplayFeature;
import androidx.window.layout.FoldingFeature;
import androidx.window.layout.WindowInfoTracker;
import androidx.window.layout.WindowLayoutInfo;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/** Responsive screen root. Keeps the two content trees alive while their viewports change. */
public final class AdaptiveScreenLayout extends ViewGroup {
    private static final WeakHashMap<Activity,WeakReference<AdaptiveScreenLayout>> ATTACHED=new WeakHashMap<>();
    private final Activity owner;
    private final View primary,secondary;
    private final PaneScrollView compactScroll,primaryScroll,secondaryScroll;
    private final LinearLayout compactStack;
    private final List<FoldingFeature> folds=new ArrayList<>();
    private final Set<Runnable> dialogBoundsListeners=new LinkedHashSet<>();
    private final Rect safeInsets=new Rect(),lastDialogBounds=new Rect();
    private final Consumer<WindowLayoutInfo> foldListener=this::onWindowLayoutInfo;
    private WindowInfoTrackerCallbackAdapter tracker;
    private AdaptiveScreenGeometry.Plan plan;
    private boolean split,restorePending,externalInsetsOwner,singlePane;
    private int primaryOffset,secondaryOffset,lastActivePane,primaryPreferredWidthDp;
    private int externalWindowLeft,externalWindowTop;
    private boolean externalWindowOriginKnown;
    private Rect externalDialogBounds;
    private View restoreFocus;

    /** Both panels must be unparented. Their padding, controls and listeners are preserved. */
    public AdaptiveScreenLayout(Activity owner,View primary,View secondary) {
        super(owner);
        this.owner=Objects.requireNonNull(owner,"owner");
        this.primary=Objects.requireNonNull(primary,"primary");
        this.secondary=Objects.requireNonNull(secondary,"secondary");
        if(primary==secondary||primary.getParent()!=null||secondary.getParent()!=null)
            throw new IllegalArgumentException("Adaptive panels must be distinct and unparented");
        owner.getWindow().setDecorFitsSystemWindows(false);
        setClipChildren(true);setClipToPadding(true);setFocusable(false);
        compactScroll=new PaneScrollView(-1);primaryScroll=new PaneScrollView(0);secondaryScroll=new PaneScrollView(1);
        compactStack=new LinearLayout(owner);compactStack.setOrientation(LinearLayout.VERTICAL);
        compactScroll.addView(compactStack,new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        compactStack.addView(primary,new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        compactStack.addView(secondary,new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        addView(compactScroll);addView(primaryScroll);addView(secondaryScroll);
        primaryScroll.setVisibility(GONE);secondaryScroll.setVisibility(GONE);
        setOnApplyWindowInsetsListener((view,insets)->{
            if(externalInsetsOwner)return insets;
            Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
            if(safeInsets.left!=safe.left||safeInsets.top!=safe.top||safeInsets.right!=safe.right||safeInsets.bottom!=safe.bottom) {
                safeInsets.set(safe.left,safe.top,safe.right,safe.bottom);requestLayout();
            }
            return WindowInsets.CONSUMED;
        });
    }

    /** Reveal a control in whichever scroll viewport currently owns it. */
    public void reveal(View descendant) {
        if(!isInside(descendant,primary)&&!isInside(descendant,secondary))return;
        lastActivePane=isInside(descendant,secondary)?1:0;
        post(()->{
            if(!isAttachedToWindow()||!descendant.isAttachedToWindow())return;
            ScrollView scroll=split?(isInside(descendant,secondary)?secondaryScroll:primaryScroll):compactScroll;
            if(!isInside(descendant,scroll))return;
            int visibleHeight=Math.max(0,scroll.getHeight()-scroll.getPaddingTop()-scroll.getPaddingBottom());
            if(visibleHeight==0)return;
            // ScrollView leaves a rectangle that spans both viewport edges where
            // it is. Request the top segment of a large page so page changes reveal
            // its start, while a smaller control still requests its complete bounds.
            Rect bounds=new Rect(0,0,descendant.getWidth(),Math.min(descendant.getHeight(),visibleHeight));
            descendant.requestRectangleOnScreen(bounds,false);
        });
    }

    public int getContentWidthDp() {
        if(plan==null)return Math.round(Math.max(0,getWidth()-safeInsets.left-safeInsets.right)/density());
        int width=plan.primary.width();
        if(plan.mode==AdaptiveScreenGeometry.Mode.COLUMNS)
            width=plan.secondary.right-plan.primary.left;
        return Math.round(width/density());
    }

    /** Zero keeps balanced columns. Physical folding boundaries always take precedence. */
    public void setPrimaryPreferredWidthDp(int widthDp) {
        if(widthDp<0)throw new IllegalArgumentException("Preferred primary width cannot be negative");
        if(primaryPreferredWidthDp!=widthDp){primaryPreferredWidthDp=widthDp;requestLayout();}
    }

    /** Used when a fixed navigation shell already measures this view inside its safe area. */
    public void setExternalInsetsOwner(boolean external) {
        if(externalInsetsOwner==external)return;
        externalInsetsOwner=external;externalWindowOriginKnown=false;externalDialogBounds=null;safeInsets.setEmpty();requestApplyInsets();requestLayout();
    }

    /** The external owner supplies the pending layout origin before measuring this child. */
    void setExternalWindowOrigin(int left,int top){externalWindowLeft=left;externalWindowTop=top;externalWindowOriginKnown=true;}
    /** Modal windows may cover app chrome, but still must avoid system insets and real hinges. */
    void setExternalDialogBounds(Rect boundsInWindow){externalDialogBounds=new Rect(boundsInWindow);}

    /** Use one content pane even on wide screens; a real hinge still limits its safe region. */
    public void setSinglePane(boolean single) {
        if(singlePane!=single){singlePane=single;requestLayout();}
    }

    /** Largest unobstructed content region in screen coordinates, independent of UI-only columns. */
    public static Rect availableDialogBounds(Activity owner) {
        AdaptiveScreenLayout layout=attached(owner);
        if(layout!=null&&layout.plan!=null&&layout.getWidth()>0)return layout.dialogBoundsOnScreen();
        WindowMetrics metrics=owner.getWindowManager().getCurrentWindowMetrics();
        Rect bounds=new Rect(metrics.getBounds());
        WindowInsets insets=owner.getWindow().getDecorView().getRootWindowInsets();
        if(insets==null)insets=metrics.getWindowInsets();
        Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
        bounds.left=Math.min(bounds.right,bounds.left+safe.left);bounds.top=Math.min(bounds.bottom,bounds.top+safe.top);
        bounds.right=Math.max(bounds.left,bounds.right-safe.right);bounds.bottom=Math.max(bounds.top,bounds.bottom-safe.bottom);
        return bounds;
    }

    /** Call from the UI thread; remove when the dialog is dismissed. */
    public static void addDialogBoundsListener(Activity owner,Runnable listener) {
        AdaptiveScreenLayout layout=attached(owner);if(layout!=null)layout.dialogBoundsListeners.add(Objects.requireNonNull(listener,"listener"));
    }
    public static void removeDialogBoundsListener(Activity owner,Runnable listener) {
        AdaptiveScreenLayout layout=attached(owner);if(layout!=null)layout.dialogBoundsListeners.remove(listener);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();ATTACHED.put(owner,new WeakReference<>(this));requestApplyInsets();
        try {
            tracker=new WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(owner));
            tracker.addWindowLayoutInfoListener(owner,owner.getMainExecutor(),foldListener);
        } catch(RuntimeException|LinkageError unavailable) {
            // OEM window extensions can be absent or incompatible. Measured-width layout remains usable.
            removeTracker();folds.clear();requestLayout();
        }
    }

    @Override protected void onDetachedFromWindow() {
        if(attached(owner)==this)ATTACHED.remove(owner);
        removeTracker();
        dialogBoundsListeners.clear();folds.clear();super.onDetachedFromWindow();
    }

    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        compactScroll.updatePadding();primaryScroll.updatePadding();secondaryScroll.updatePadding();
        requestApplyInsets();requestLayout();
    }

    @Override protected void onMeasure(int widthMeasureSpec,int heightMeasureSpec) {
        int width=MeasureSpec.getSize(widthMeasureSpec),height=MeasureSpec.getSize(heightMeasureSpec);
        if(MeasureSpec.getMode(widthMeasureSpec)==MeasureSpec.UNSPECIFIED)
            width=owner.getWindowManager().getCurrentWindowMetrics().getBounds().width();
        if(MeasureSpec.getMode(heightMeasureSpec)==MeasureSpec.UNSPECIFIED)
            height=owner.getWindowManager().getCurrentWindowMetrics().getBounds().height();
        setMeasuredDimension(width,height);
        int left=Math.min(width,safeInsets.left),top=Math.min(height,safeInsets.top);
        AdaptiveScreenGeometry.Bounds available=new AdaptiveScreenGeometry.Bounds(left,top,
                Math.max(left,width-safeInsets.right),Math.max(top,height-safeInsets.bottom));
        int[] location=new int[2];getLocationInWindow(location);
        if(externalInsetsOwner&&externalWindowOriginKnown){location[0]=externalWindowLeft;location[1]=externalWindowTop;}
        List<AdaptiveScreenGeometry.Feature> features=new ArrayList<>();
        for(FoldingFeature fold:folds) {
            Rect b=fold.getBounds();
            features.add(new AdaptiveScreenGeometry.Feature(new AdaptiveScreenGeometry.Bounds(
                    b.left-location[0],b.top-location[1],b.right-location[0],b.bottom-location[1]),
                    fold.getOrientation()==FoldingFeature.Orientation.VERTICAL,
                    fold.isSeparating(),fold.getOcclusionType()==FoldingFeature.OcclusionType.FULL));
        }
        plan=AdaptiveScreenGeometry.arrange(available,density(),getResources().getConfiguration().fontScale,features,primaryPreferredWidthDp);
        if(singlePane)plan=new AdaptiveScreenGeometry.Plan(AdaptiveScreenGeometry.Mode.SINGLE,plan.dialogBounds,null);
        changeStructure(plan.split());
        if(split){measureViewport(primaryScroll,plan.primary);measureViewport(secondaryScroll,plan.secondary);}
        else measureViewport(compactScroll,plan.primary);
    }

    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
        if(plan==null)return;
        if(split){layoutViewport(primaryScroll,plan.primary);layoutViewport(secondaryScroll,plan.secondary);}
        else layoutViewport(compactScroll,plan.primary);
        if(restorePending) {
            restorePending=false;
            if(split){primaryScroll.scrollTo(0,primaryOffset);secondaryScroll.scrollTo(0,secondaryOffset);}
            else compactScroll.scrollTo(0,lastActivePane==1?secondary.getTop()+secondaryOffset:primary.getTop()+primaryOffset);
            View focused=restoreFocus;restoreFocus=null;
            if(focused!=null){focused.requestFocus();reveal(focused);}
        }
        Rect dialogBounds=dialogBoundsOnScreen();
        if(!lastDialogBounds.equals(dialogBounds)) {
            lastDialogBounds.set(dialogBounds);
            for(Runnable listener:new ArrayList<>(dialogBoundsListeners))post(()->{
                if(isAttachedToWindow()&&dialogBoundsListeners.contains(listener))listener.run();
            });
        }
    }

    private void changeStructure(boolean nextSplit) {
        if(split==nextSplit)return;
        restoreFocus=findFocus();
        if(isInside(restoreFocus,secondary))lastActivePane=1;else if(isInside(restoreFocus,primary))lastActivePane=0;
        if(split){primaryOffset=primaryScroll.getScrollY();secondaryOffset=secondaryScroll.getScrollY();}
        else if(compactStack.isLaidOut()) {
            int offset=compactScroll.getScrollY();
            if(offset<secondary.getTop())primaryOffset=Math.max(0,offset-primary.getTop());
            else {secondaryOffset=Math.max(0,offset-secondary.getTop());lastActivePane=1;}
        }
        detach(primary);detach(secondary);split=nextSplit;
        if(split) {
            primaryScroll.addView(primary,new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
            secondaryScroll.addView(secondary,new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        } else {
            compactStack.addView(primary,new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
            compactStack.addView(secondary,new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        }
        compactScroll.setVisibility(split?GONE:VISIBLE);primaryScroll.setVisibility(split?VISIBLE:GONE);secondaryScroll.setVisibility(split?VISIBLE:GONE);
        restorePending=true;
    }

    private void onWindowLayoutInfo(WindowLayoutInfo info) {
        if(!isAttachedToWindow())return;
        folds.clear();for(DisplayFeature feature:info.getDisplayFeatures())if(feature instanceof FoldingFeature)folds.add((FoldingFeature)feature);
        requestLayout();
    }
    private void removeTracker() {
        WindowInfoTrackerCallbackAdapter registered=tracker;tracker=null;
        if(registered!=null)try{registered.removeWindowLayoutInfoListener(foldListener);}catch(RuntimeException|LinkageError ignored){}
    }
    private Rect dialogBoundsOnScreen() {
        if(externalInsetsOwner&&externalDialogBounds!=null) {
            int[] screen=new int[2],window=new int[2];getLocationOnScreen(screen);getLocationInWindow(window);
            Rect bounds=new Rect(externalDialogBounds);bounds.offset(screen[0]-window[0],screen[1]-window[1]);return bounds;
        }
        AdaptiveScreenGeometry.Bounds b=plan.dialogBounds;int[] location=new int[2];getLocationOnScreen(location);
        return new Rect(b.left+location[0],b.top+location[1],b.right+location[0],b.bottom+location[1]);
    }
    private float density(){return getResources().getDisplayMetrics().density;}
    private static AdaptiveScreenLayout attached(Activity owner) {
        WeakReference<AdaptiveScreenLayout> reference=ATTACHED.get(owner);return reference==null?null:reference.get();
    }
    private static void detach(View view){if(view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);}
    private static boolean isInside(View view,View ancestor) {
        if(view==null)return false;
        for(View current=view;current!=null;) {
            if(current==ancestor)return true;ViewParent parent=current.getParent();current=parent instanceof View?(View)parent:null;
        }
        return false;
    }
    private static void measureViewport(View view,AdaptiveScreenGeometry.Bounds bounds) {
        view.measure(MeasureSpec.makeMeasureSpec(bounds.width(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(bounds.height(),MeasureSpec.EXACTLY));
    }
    private static void layoutViewport(View view,AdaptiveScreenGeometry.Bounds bounds){view.layout(bounds.left,bounds.top,bounds.right,bounds.bottom);}

    private final class PaneScrollView extends ScrollView {
        final int pane;
        PaneScrollView(int pane){super(owner);this.pane=pane;setFillViewport(true);setClipToPadding(true);setSaveEnabled(false);updatePadding();}
        void updatePadding(){setPadding(Math.round(20*density()),Math.round(16*density()),Math.round(20*density()),Math.round(24*density()));}
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN)
                lastActivePane=pane<0?(event.getY()+getScrollY()>=secondary.getTop()?1:0):pane;
            return super.onInterceptTouchEvent(event);
        }
    }
}
