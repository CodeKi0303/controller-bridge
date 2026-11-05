package dev.codek.xboxprobe;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Insets;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

/** Keeps an existing dialog inside one unobstructed app-window pane. The same
 * widgets remain attached across resizing; this owner never rebuilds a form. */
final class AdaptiveDialogWindow implements ViewTreeObserver.OnGlobalLayoutListener,View.OnAttachStateChangeListener {
    private final Activity owner;
    private final Window window;
    private final View decor,host;
    private final int maxWidthDp;
    private final boolean fillHeight;
    private final boolean compactEditor;
    private final Runnable boundsChanged=this::update;
    private boolean closed,updating,constrained;
    private int lastCap,lastWidth;

    static void attach(Activity owner,Dialog dialog,int maxWidthDp){
        attach(owner,dialog,maxWidthDp,false);
    }
    static void attachEditor(Activity owner,Dialog dialog,int maxWidthDp){
        attach(owner,dialog,maxWidthDp,true);
    }
    private static void attach(Activity owner,Dialog dialog,int maxWidthDp,boolean compactEditor){
        Window window=dialog.getWindow();if(window==null)return;
        // Pane bounds already exclude bars/cutouts. All callers use the same
        // window coordinate convention rather than adding those insets twice.
        window.setDecorFitsSystemWindows(false);
        AdaptiveDialogWindow layout=new AdaptiveDialogWindow(owner,window,maxWidthDp,compactEditor);
        layout.host.getViewTreeObserver().addOnGlobalLayoutListener(layout);
        layout.decor.getViewTreeObserver().addOnGlobalLayoutListener(layout);
        layout.decor.addOnAttachStateChangeListener(layout);
        AdaptiveScreenLayout.addDialogBoundsListener(owner,layout.boundsChanged);
        layout.update();
    }
    private AdaptiveDialogWindow(Activity owner,Window window,int maxWidthDp,boolean compactEditor){
        this.owner=owner;this.window=window;this.maxWidthDp=maxWidthDp;
        this.compactEditor=compactEditor;
        decor=window.getDecorView();host=owner.getWindow().getDecorView();
        fillHeight=window.getAttributes().height==WindowManager.LayoutParams.MATCH_PARENT;
    }
    @Override public void onGlobalLayout(){update();}
    @Override public void onViewAttachedToWindow(View view){}
    @Override public void onViewDetachedFromWindow(View view){
        if(closed)return;closed=true;
        if(host.getViewTreeObserver().isAlive())host.getViewTreeObserver().removeOnGlobalLayoutListener(this);
        if(decor.getViewTreeObserver().isAlive())decor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
        decor.removeOnAttachStateChangeListener(this);
        AdaptiveScreenLayout.removeDialogBoundsListener(owner,boundsChanged);
    }
    private void update(){
        if(closed||updating||owner.isDestroyed()||owner.isFinishing())return;
        updating=true;
        try{
            WindowMetrics metrics=owner.getWindowManager().getCurrentWindowMetrics();
            Rect hostBounds=metrics.getBounds();
            Rect safe=new Rect(AdaptiveScreenLayout.availableDialogBounds(owner));
            // Metrics insets are calculated against the unchanged activity
            // bounds. Deriving IME top from this dialog's changing height could
            // feed a previous inset back into the next resize and shrink again.
            WindowInsets insets=metrics.getWindowInsets();
            Insets ime=insets.getInsets(WindowInsets.Type.ime());
            if(insets.isVisible(WindowInsets.Type.ime())&&ime.bottom>0)
                safe.bottom=Math.max(safe.top,Math.min(safe.bottom,hostBounds.bottom-ime.bottom));
            boolean fullPane=compactEditor&&safe.width()<dp(600);
            int marginX=fullPane?0:Math.min(dp(16),Math.max(0,safe.width()/20));
            int marginY=fullPane?0:Math.min(dp(24),Math.max(0,safe.height()/20));
            safe.inset(marginX,marginY);
            int width=Math.max(1,fullPane?safe.width():Math.min(dp(maxWidthDp),safe.width()));
            int cap=Math.max(1,safe.height());
            if(width!=lastWidth||cap>lastCap)constrained=false;
            if(decor.getHeight()>cap)constrained=true;
            int height=fillHeight||constrained?cap:WindowManager.LayoutParams.WRAP_CONTENT;
            int visibleHeight=height>0?height:Math.min(cap,decor.getHeight()>0?decor.getHeight():cap);
            int x=safe.left-hostBounds.left+(safe.width()-width)/2;
            int y=safe.top-hostBounds.top+(safe.height()-visibleHeight)/2;
            WindowManager.LayoutParams attributes=window.getAttributes();
            boolean frameInsets=attributes.getFitInsetsTypes()!=0||attributes.getFitInsetsSides()!=0
                    ||attributes.layoutInDisplayCutoutMode!=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            if(frameInsets||attributes.width!=width||attributes.height!=height||attributes.x!=x||attributes.y!=y||attributes.gravity!=(Gravity.TOP|Gravity.LEFT)){
                // safe already accounts for bars, cutouts, IME and folding features.
                // Floating windows otherwise add their own fitted parent-frame
                // origin to x/y; setDecorFitsSystemWindows(false) alone does not
                // disable that WindowManager frame fitting on rotation.
                attributes.setFitInsetsTypes(0);attributes.setFitInsetsSides(0);
                attributes.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                attributes.width=width;attributes.height=height;attributes.x=x;attributes.y=y;attributes.gravity=Gravity.TOP|Gravity.LEFT;window.setAttributes(attributes);
            }
            lastWidth=width;lastCap=cap;
        }finally{updating=false;}
    }
    private int dp(int value){return Math.round(value*owner.getResources().getDisplayMetrics().density);}
}
