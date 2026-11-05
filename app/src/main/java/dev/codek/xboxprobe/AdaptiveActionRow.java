package dev.codek.xboxprobe;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

/** Keeps equal-weight actions readable when their actual container becomes narrow. */
final class AdaptiveActionRow extends LinearLayout {
    private final int minimumChildWidthDp;

    AdaptiveActionRow(Context context,int minimumChildWidthDp) {
        super(context);
        this.minimumChildWidthDp=minimumChildWidthDp;
        setOrientation(HORIZONTAL);
    }

    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int count=0;
        for(int i=0;i<getChildCount();i++)if(getChildAt(i).getVisibility()!=GONE)count++;
        float density=getResources().getDisplayMetrics().density;
        float font=Math.max(1f,getResources().getConfiguration().fontScale);
        int available=Math.max(0,MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight());
        boolean stack=MeasureSpec.getMode(widthSpec)!=MeasureSpec.UNSPECIFIED
                &&count>1&&available<count*minimumChildWidthDp*density*font;
        int direction=stack?VERTICAL:HORIZONTAL;
        if(getOrientation()!=direction)setOrientation(direction);
        for(int i=0;i<getChildCount();i++) {
            View child=getChildAt(i);
            LayoutParams params=(LayoutParams)child.getLayoutParams();
            int desiredWidth=stack?LayoutParams.MATCH_PARENT:0;
            float weight=stack?0:1;
            if(params.width!=desiredWidth||params.weight!=weight) {
                params.width=desiredWidth;params.weight=weight;
                child.setLayoutParams(params);
            }
        }
        super.onMeasure(widthSpec,heightSpec);
    }
}
