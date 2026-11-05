package dev.codek.xboxprobe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Window-local geometry, independent of Android views and physical screen size. */
final class AdaptiveScreenGeometry {
    enum Mode { SINGLE,COLUMNS,ROWS }
    static final class Bounds {
        final int left,top,right,bottom;
        Bounds(int left,int top,int right,int bottom) {
            this.left=left;this.top=top;this.right=Math.max(left,right);this.bottom=Math.max(top,bottom);
        }
        int width(){return right-left;}
        int height(){return bottom-top;}
        long area(){return (long)width()*height();}
    }
    static final class Feature {
        final Bounds bounds;
        final boolean vertical,separating,occluding;
        Feature(Bounds bounds,boolean vertical,boolean separating,boolean occluding) {
            this.bounds=bounds;this.vertical=vertical;this.separating=separating;this.occluding=occluding;
        }
    }
    static final class Plan {
        final Mode mode;
        final Bounds primary,secondary,dialogBounds;
        Plan(Mode mode,Bounds primary,Bounds secondary) {
            this(mode,primary,secondary,secondary!=null&&secondary.area()>primary.area()?secondary:primary);
        }
        Plan(Mode mode,Bounds primary,Bounds secondary,Bounds dialogBounds) {
            this.mode=mode;this.primary=primary;this.secondary=secondary;this.dialogBounds=dialogBounds;
        }
        boolean split(){return mode!=Mode.SINGLE;}
    }

    private AdaptiveScreenGeometry(){}

    static Plan arrange(Bounds available,float density,float fontScale,List<Feature> features) {
        return arrange(available,density,fontScale,features,0);
    }

    static Plan arrange(Bounds available,float density,float fontScale,List<Feature> features,int primaryPreferredWidthDp) {
        if(!(density>0)||!Float.isFinite(density)||!(fontScale>0)||!Float.isFinite(fontScale))
            throw new IllegalArgumentException("Density and font scale must be finite and positive");
        if(primaryPreferredWidthDp<0)throw new IllegalArgumentException("Preferred primary width cannot be negative");
        int maxWidth=px(1280,density),gap=px(24,density);
        int width=Math.min(available.width(),maxWidth);
        int left=available.left+(available.width()-width)/2;
        List<Bounds> regions=new ArrayList<>();
        regions.add(new Bounds(left,available.top,left+width,available.bottom));
        boolean blocked=false;
        for(Feature feature:features) {
            if(!feature.separating&&!feature.occluding)continue;
            List<Bounds> next=new ArrayList<>();
            for(Bounds region:regions) {
                Bounds fold=feature.bounds;
                boolean overlap=feature.vertical
                        ?fold.bottom>region.top&&fold.top<region.bottom&&fold.left<region.right&&fold.right>region.left
                        :fold.right>region.left&&fold.left<region.right&&fold.top<region.bottom&&fold.bottom>region.top;
                // A zero-width/height separating crease still divides the window.
                if(feature.vertical&&fold.width()==0)
                    overlap=fold.height()>0&&fold.bottom>region.top&&fold.top<region.bottom&&fold.left>region.left&&fold.left<region.right;
                if(!feature.vertical&&fold.height()==0)
                    overlap=fold.width()>0&&fold.right>region.left&&fold.left<region.right&&fold.top>region.top&&fold.top<region.bottom;
                if(!overlap){next.add(region);continue;}
                blocked=true;
                if(feature.vertical) {
                    int before=Math.max(region.left,Math.min(region.right,fold.left-gap/2));
                    int after=Math.max(region.left,Math.min(region.right,fold.right+(gap-gap/2)));
                    if(before>region.left)next.add(new Bounds(region.left,region.top,before,region.bottom));
                    if(after<region.right)next.add(new Bounds(after,region.top,region.right,region.bottom));
                } else {
                    int before=Math.max(region.top,Math.min(region.bottom,fold.top-gap/2));
                    int after=Math.max(region.top,Math.min(region.bottom,fold.bottom+(gap-gap/2)));
                    if(before>region.top)next.add(new Bounds(region.left,region.top,region.right,before));
                    if(after<region.bottom)next.add(new Bounds(region.left,after,region.right,region.bottom));
                }
            }
            regions=next;
        }
        regions.sort(Comparator.<Bounds>comparingLong(Bounds::area).reversed()
                .thenComparingInt(b->b.top).thenComparingInt(b->b.left));
        if(regions.isEmpty())return new Plan(Mode.SINGLE,new Bounds(left,available.top,left,available.top),null);
        float scale=Math.max(1,fontScale);
        int minimumFoldWidth=px(240*scale,density),minimumFoldHeight=px(96*scale,density);
        if(blocked&&regions.size()>=2) {
            Bounds first=regions.get(0),second=regions.get(1);
            if(first.width()>=minimumFoldWidth&&second.width()>=minimumFoldWidth
                    &&first.height()>=minimumFoldHeight&&second.height()>=minimumFoldHeight) {
                if(first.top==second.top&&first.bottom==second.bottom)
                    return first.left<second.left?new Plan(Mode.COLUMNS,first,second):new Plan(Mode.COLUMNS,second,first);
                if(first.left==second.left&&first.right==second.right)
                    return first.top<second.top?new Plan(Mode.ROWS,first,second):new Plan(Mode.ROWS,second,first);
            }
        }
        Bounds region=regions.get(0);
        long splitMinimum=Math.max(px(720,density),2L*px(320*scale,density)+gap);
        if(region.width()>=splitMinimum) {
            int paneWidth=(region.width()-gap)/2;
            if(!blocked&&primaryPreferredWidthDp>0) {
                int minimum=px(320*scale,density);
                paneWidth=Math.max(minimum,Math.min(px(primaryPreferredWidthDp,density),region.width()-gap-minimum));
            }
            return new Plan(Mode.COLUMNS,new Bounds(region.left,region.top,region.left+paneWidth,region.bottom),
                    new Bounds(region.left+paneWidth+gap,region.top,region.right,region.bottom),region);
        }
        return new Plan(Mode.SINGLE,region,null);
    }

    private static int px(float dp,float density){return Math.max(0,Math.round(dp*density));}
}
