package dev.codek.xboxprobe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Original schematic illustrations. Labels wrap at the actual pane width and
 * follow system font sizing; neither screenshots nor live device values. */
final class WirelessGuideView extends View {
    private static final int SURFACE=0xff0c121b,CARD=0xff17222f,LINE=0xff314456,
            TEXT=0xffedf4fc,MUTED=0xff9aafc4,PAIR=0xff9ebfff,CONNECT=0xff78dfbd;
    private interface Drawing { void draw(Canvas canvas); }
    private final List<Drawing> drawing=new ArrayList<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int step;

    WirelessGuideView(Context context,int step) {
        super(context);
        if(step<1||step>3)throw new IllegalArgumentException("Unknown setup illustration");
        this.step=step;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(step==1
                ?"화면 예시. 개발자 옵션에서 무선 디버깅 스위치를 켭니다."
                :step==2
                ?"화면 예시. 페어링 코드 창의 포트 37123과 코드 001234를, 알림의 포트·코드 입력에 공백으로 나눠 입력합니다. 이 숫자는 예시입니다."
                :"화면 예시. 무선 디버깅 기본 화면의 IP 주소 및 포트에서 마지막 숫자 42855만 연결 포트에 입력합니다. 이 숫자는 예시입니다.");
    }

    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int width=resolveSize(dp(400),widthSpec);
        float contentWidth=Math.min(width,dp(560));
        float left=(width-contentWidth)/2f;
        drawing.clear();
        RectF surface=new RectF(left,0,left+contentWidth,0);round(surface,dp(16),SURFACE);
        float inset=dp(12),x=left+inset,w=Math.max(1,contentWidth-2*inset);
        float y=label(step==1?"화면 예시":"화면 예시 · 모든 숫자는 예시입니다",x,dp(10),w,12,MUTED,false);
        if(step==1)y=settings(x,y,w);
        else if(step==2){
            float readableWidth=contentWidth/getResources().getDisplayMetrics().density
                    /Math.max(1f,getResources().getConfiguration().fontScale);
            if(readableWidth>=380){
                float gap=dp(12),column=(w-gap)/2;
                y=Math.max(pairing(x,y,column),notification(x+column+gap,y,column));
            }else{
                y=pairing(x,y,w)+dp(10);
                y=notification(x,y,w);
            }
        }else y=connection(x,y,w);
        surface.bottom=y+dp(12);
        setMeasuredDimension(width,resolveSize((int)Math.ceil(surface.bottom),heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for(Drawing item:drawing)item.draw(canvas);
    }

    private float settings(float x,float y,float width) {
        RectF card=card(x,y,width);float px=x+dp(12),w=Math.max(1,width-dp(24));
        y=label("개발자 옵션",px,y+dp(12),w,14,TEXT,true);
        y=rule(px,y,w);
        float rowTop=y;
        y=label("무선 디버깅",px,y,Math.max(1,w-dp(62)),16,TEXT,true);
        float switchTop=rowTop+Math.max(0,(y-rowTop-dp(6)-dp(24))/2);
        float switchX=x+width-dp(58);
        round(new RectF(switchX,switchTop,switchX+dp(46),switchTop+dp(24)),dp(12),CONNECT);
        drawing.add(canvas->{paint.setColor(SURFACE);canvas.drawCircle(switchX+dp(33),switchTop+dp(12),dp(8),paint);});
        y=label("같은 Wi-Fi에서 켜기",px,y,w,12,MUTED,false);
        y=highlight("코드로 기기 페어링",px,y,w,14,PAIR,0xff203342);
        card.bottom=y+dp(10);return card.bottom;
    }

    private float pairing(float x,float y,float width) {
        RectF card=card(x,y,width);float px=x+dp(12),w=Math.max(1,width-dp(24));
        y=label("페어링 코드 창",px,y+dp(12),w,14,TEXT,true);
        y=label("001234",px,y,w,25,PAIR,true);
        y=label("IP 주소 및 포트",px,y,w,12,MUTED,false);
        y=label("192.168.0.2 : 37123",px,y,w,13,TEXT,false);
        y=highlight("페어링 포트 37123",px,y,w,16,PAIR,0xff293e5c);
        y=label("이 창을 열어 둡니다",px,y,w,12,MUTED,false);
        card.bottom=y+dp(6);return card.bottom;
    }

    private float notification(float x,float y,float width) {
        RectF card=card(x,y,width);float px=x+dp(12),w=Math.max(1,width-dp(24));
        y=label("Controller Bridge 알림",px,y+dp(12),w,14,TEXT,true);
        y=label("포트·코드 입력",px,y,w,14,PAIR,true);
        y=highlight("37123 001234",px,y,w,20,TEXT,0xff23334a);
        y=label("포트 + 공백 + 코드",px,y,w,12,MUTED,false);
        y=label("알림 입력란에 보내세요",px,y,w,12,MUTED,false);
        card.bottom=y+dp(6);return card.bottom;
    }

    private float connection(float x,float y,float width) {
        RectF card=card(x,y,width);float px=x+dp(12),w=Math.max(1,width-dp(24));
        y=label("무선 디버깅 기본 화면",px,y+dp(12),w,15,TEXT,true);
        y=label("IP 주소 및 포트",px,y,w,12,MUTED,false);
        y=label("192.168.0.2 : 42855",px,y,w,15,TEXT,false);
        y=highlight("연결 포트 42855",px,y,w,20,CONNECT,0xff24463e);
        y=rule(px,y,w);
        y=label("페어링 창의 포트와 다릅니다",px,y,w,13,TEXT,false);
        y=label("무선 디버깅을 다시 켜면 바뀔 수 있어요",px,y,w,12,MUTED,false);
        card.bottom=y+dp(6);return card.bottom;
    }

    private RectF card(float x,float y,float width) {
        RectF card=new RectF(x,y,x+width,y);round(card,dp(12),CARD);return card;
    }
    private void round(RectF rect,float radius,int color) {
        drawing.add(canvas->{paint.setStyle(Paint.Style.FILL);paint.setColor(color);canvas.drawRoundRect(rect,radius,radius,paint);});
    }
    private float rule(float x,float y,float width) {
        drawing.add(canvas->{paint.setColor(LINE);paint.setStrokeWidth(dp(1));canvas.drawLine(x,y,x+width,y,paint);});
        return y+dp(10);
    }
    private float highlight(String value,float x,float y,float width,int size,int color,int background) {
        RectF rect=new RectF(x,y,x+width,y);round(rect,dp(8),background);
        float bottom=label(value,x+dp(8),y+dp(6),Math.max(1,width-dp(16)),size,color,true);
        rect.bottom=bottom;return bottom+dp(8);
    }
    private float label(String value,float x,float y,float width,int size,int color,boolean bold) {
        TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);text.setColor(color);
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,size,getResources().getDisplayMetrics()));
        text.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
        StaticLayout layout=StaticLayout.Builder.obtain(value,0,value.length(),text,Math.max(1,(int)width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(dp(2),1f).build();
        drawing.add(canvas->{canvas.save();canvas.translate(x,y);layout.draw(canvas);canvas.restore();});
        return y+layout.getHeight()+dp(6);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
