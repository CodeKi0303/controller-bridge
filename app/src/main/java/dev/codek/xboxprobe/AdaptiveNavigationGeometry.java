package dev.codek.xboxprobe;

import java.util.List;

/** Fixed application chrome around a scrollable screen, in window-local coordinates. */
final class AdaptiveNavigationGeometry {
    static final class Plan {
        final AdaptiveScreenGeometry.Bounds appBar,content,navigation;
        final boolean rail;
        final int itemHeight;
        Plan(AdaptiveScreenGeometry.Bounds appBar,AdaptiveScreenGeometry.Bounds content,
             AdaptiveScreenGeometry.Bounds navigation,boolean rail,int itemHeight) {
            this.appBar=appBar;this.content=content;this.navigation=navigation;this.rail=rail;this.itemHeight=itemHeight;
        }
    }
    private AdaptiveNavigationGeometry(){}

    static Plan arrange(AdaptiveScreenGeometry.Bounds available,float density,float fontScale,
                        List<AdaptiveScreenGeometry.Feature> features,boolean showNavigation) {
        float scale=Math.max(1,fontScale);
        int barHeight=Math.round(Math.max(64,40*scale+16)*density);
        int itemHeight=Math.round(Math.max(80,44+36*scale)*density);
        int railWidth=Math.round(Math.min(160,96*scale)*density);
        AdaptiveScreenGeometry.Bounds usable=available;
        AdaptiveScreenGeometry.Bounds top=topBand(usable,barHeight);
        AdaptiveScreenGeometry.Bounds safeTop=safe(top,density,fontScale,features);
        // A feature crossing the chrome itself can leave too little space for a
        // touch target. In that case place the screen on one complete safe side.
        if(safeTop.height()<Math.min(barHeight,available.height())||safeTop.width()<Math.round(144*density)) {
            usable=safe(available,density,fontScale,features);
            top=topBand(usable,barHeight);safeTop=safe(top,density,fontScale,features);
        }
        int bodyTop=Math.min(usable.bottom,usable.top+barHeight);
        if(!showNavigation)return new Plan(safeTop,
                new AdaptiveScreenGeometry.Bounds(usable.left,bodyTop,usable.right,usable.bottom),null,false,itemHeight);
        boolean wide=usable.width()>=Math.round(600*scale*density);
        AdaptiveScreenGeometry.Bounds railBand=new AdaptiveScreenGeometry.Bounds(usable.left,bodyTop,
                Math.min(usable.right,usable.left+railWidth),usable.bottom);
        AdaptiveScreenGeometry.Bounds safeRail=safe(railBand,density,fontScale,features);
        int railMargin=Math.round(12*density);
        if(wide&&safeRail.width()>=Math.round(80*density)&&safeRail.height()>=3L*itemHeight+railMargin*2) {
            AdaptiveScreenGeometry.Bounds navigation=new AdaptiveScreenGeometry.Bounds(safeRail.left,safeRail.top+railMargin,
                    safeRail.right,safeRail.top+railMargin+3*itemHeight);
            return new Plan(safeTop,new AdaptiveScreenGeometry.Bounds(railBand.right,bodyTop,usable.right,usable.bottom),
                    navigation,true,itemHeight);
        }
        AdaptiveScreenGeometry.Bounds bottom=new AdaptiveScreenGeometry.Bounds(usable.left,
                Math.max(bodyTop,usable.bottom-itemHeight),usable.right,usable.bottom);
        AdaptiveScreenGeometry.Bounds safeBottom=safe(bottom,density,fontScale,features);
        if(safeBottom.height()<Math.min(itemHeight,Math.max(0,usable.bottom-bodyTop))) {
            usable=safe(available,density,fontScale,features);
            safeTop=safe(topBand(usable,barHeight),density,fontScale,features);
            bodyTop=Math.min(usable.bottom,usable.top+barHeight);
            bottom=new AdaptiveScreenGeometry.Bounds(usable.left,Math.max(bodyTop,usable.bottom-itemHeight),usable.right,usable.bottom);
            safeBottom=safe(bottom,density,fontScale,features);
        }
        int navigationWidth=Math.min(safeBottom.width(),Math.round(720*density));
        int navigationLeft=safeBottom.left+(safeBottom.width()-navigationWidth)/2;
        return new Plan(safeTop,new AdaptiveScreenGeometry.Bounds(usable.left,bodyTop,usable.right,bottom.top),
                new AdaptiveScreenGeometry.Bounds(navigationLeft,safeBottom.top,navigationLeft+navigationWidth,safeBottom.bottom),false,itemHeight);
    }
    private static AdaptiveScreenGeometry.Bounds topBand(AdaptiveScreenGeometry.Bounds available,int height) {
        return new AdaptiveScreenGeometry.Bounds(available.left,available.top,available.right,Math.min(available.bottom,available.top+height));
    }
    private static AdaptiveScreenGeometry.Bounds safe(AdaptiveScreenGeometry.Bounds area,float density,float fontScale,
                                                     List<AdaptiveScreenGeometry.Feature> features) {
        return AdaptiveScreenGeometry.arrange(area,density,fontScale,features).dialogBounds;
    }
}
