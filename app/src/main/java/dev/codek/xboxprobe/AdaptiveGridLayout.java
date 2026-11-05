package dev.codek.xboxprobe;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** A small card grid measured in its current pane, without rebuilding form controls. */
final class AdaptiveGridLayout extends ViewGroup {
    private int columns=1,cellWidth,gap;

    AdaptiveGridLayout(Context context) {super(context);}

    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        float density=getResources().getDisplayMetrics().density;
        float font=Math.max(1f,getResources().getConfiguration().fontScale);
        gap=Math.round(16*density);
        int width=MeasureSpec.getSize(widthSpec);
        int available=Math.max(0,width-getPaddingLeft()-getPaddingRight());
        columns=Math.max(1,Math.min(3,(available+gap)/Math.max(1,Math.round(280*density*font)+gap)));
        cellWidth=Math.max(0,(available-(columns-1)*gap)/columns);
        int height=getPaddingTop(),rowHeight=0,index=0;
        for(int i=0;i<getChildCount();i++) {
            View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
            MarginLayoutParams p=(MarginLayoutParams)child.getLayoutParams();
            child.measure(MeasureSpec.makeMeasureSpec(Math.max(0,cellWidth-p.leftMargin-p.rightMargin),MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
            rowHeight=Math.max(rowHeight,child.getMeasuredHeight()+p.topMargin+p.bottomMargin);
            if(++index%columns==0){height+=rowHeight;rowHeight=0;}
        }
        height+=rowHeight+getPaddingBottom();
        setMeasuredDimension(resolveSize(width,widthSpec),resolveSize(height,heightSpec));
    }

    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
        int y=getPaddingTop(),rowHeight=0,index=0;
        for(int i=0;i<getChildCount();i++) {
            View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
            MarginLayoutParams p=(MarginLayoutParams)child.getLayoutParams();
            int column=index%columns;
            if(getLayoutDirection()==LAYOUT_DIRECTION_RTL)column=columns-1-column;
            int x=getPaddingLeft()+column*(cellWidth+gap)+p.leftMargin;
            child.layout(x,y+p.topMargin,x+child.getMeasuredWidth(),y+p.topMargin+child.getMeasuredHeight());
            rowHeight=Math.max(rowHeight,child.getMeasuredHeight()+p.topMargin+p.bottomMargin);
            if(++index%columns==0){y+=rowHeight;rowHeight=0;}
        }
    }

    @Override protected LayoutParams generateDefaultLayoutParams(){return new MarginLayoutParams(-1,-2);}
    @Override protected LayoutParams generateLayoutParams(LayoutParams source){return new MarginLayoutParams(source);}
    @Override protected boolean checkLayoutParams(LayoutParams params){return params instanceof MarginLayoutParams;}
}
