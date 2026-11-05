package dev.codek.xboxprobe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.WindowManager;
import java.util.Locale;

/** Resolution-independent controller illustration; red always means currently
 * held or moved, and each control is drawn from the complete selected state. */
public final class ControllerDiagramView extends View {
    private static final int BODY=0xff25364a,EDGE=0xff435b73,IDLE=0xff0c1622,
            TEXT=0xffedf4fc,MUTED=0xff9aafc4,RED=0xffff5d6c;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shell=new Path();
    private final RectF rect=new RectF();
    private final ControllerTestState state;
    private boolean compact;
    private float drawingScale=1;

    ControllerDiagramView(Context context,ControllerTestState state){
        super(context);this.state=state;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("컨트롤러 그림. 누르거나 움직인 부분이 붉게 표시됩니다.");
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        float density=getResources().getDisplayMetrics().density;
        int width=resolveSize((int)(560*density),widthSpec);
        compact=width/density<460*Math.min(1.4f,getResources().getConfiguration().fontScale);
        int desired=compact?Math.min(Math.round(width*420f/540f),(int)(300*density)):
                Math.min(Math.round(width*.5f),(int)(280*density));
        WindowManager manager=getContext().getSystemService(WindowManager.class);
        if(manager!=null)desired=Math.min(desired,Math.max((int)(120*density),
                Math.round(manager.getCurrentWindowMetrics().getBounds().height()*.45f)));
        setMeasuredDimension(width,resolveSize(desired,heightSpec));
    }
    void changed(){
        setContentDescription(state.activeLabels().isEmpty()?"컨트롤러 입력 없음":"입력 중: "+String.join(", ",state.activeLabels()));
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(compact){drawCompact(canvas);return;}
        canvas.save();float scale=Math.min(Math.min(getWidth(),560*getResources().getDisplayMetrics().density)/800f,getHeight()/400f);drawingScale=scale;
        canvas.translate((getWidth()-800*scale)/2,(getHeight()-400*scale)/2);canvas.scale(scale,scale);
        shell.reset();shell.moveTo(165,103);shell.cubicTo(110,110,84,188,61,303);
        shell.cubicTo(50,357,97,382,134,340);shell.lineTo(225,275);
        shell.cubicTo(300,304,500,304,575,275);shell.lineTo(666,340);
        shell.cubicTo(703,382,750,357,739,303);shell.cubicTo(716,188,690,110,635,103);
        shell.cubicTo(515,89,285,89,165,103);shell.close();
        paint.setStyle(Paint.Style.FILL);paint.setColor(BODY);canvas.drawPath(shell,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(EDGE);canvas.drawPath(shell,paint);paint.setStyle(Paint.Style.FILL);
        trigger(canvas,142,15,"LT",state.leftTrigger(),state.active(ControllerTestState.Control.LT));
        trigger(canvas,562,15,"RT",state.rightTrigger(),state.active(ControllerTestState.Control.RT));
        pill(canvas,138,72,110,32,"LB",state.active(ControllerTestState.Control.LB));
        pill(canvas,552,72,110,32,"RB",state.active(ControllerTestState.Control.RB));
        stick(canvas,202,185,"L3",state.leftX(),state.leftY(),state.active(ControllerTestState.Control.L3),state.leftMoved());
        stick(canvas,494,269,"R3",state.rightX(),state.rightY(),state.active(ControllerTestState.Control.R3),state.rightMoved());
        circle(canvas,612,153,25,"Y",state.active(ControllerTestState.Control.Y));
        circle(canvas,654,195,25,"B",state.active(ControllerTestState.Control.B));
        circle(canvas,570,195,25,"X",state.active(ControllerTestState.Control.X));
        circle(canvas,612,237,25,"A",state.active(ControllerTestState.Control.A));
        dpad(canvas,322,263);
        pill(canvas,335,163,42,29,"▱",state.active(ControllerTestState.Control.SELECT));
        pill(canvas,423,163,42,29,"≡",state.active(ControllerTestState.Control.START));
        circle(canvas,400,128,18,"●",state.active(ControllerTestState.Control.GUIDE));
        text(canvas,"View",356,214,18,MUTED);text(canvas,"Menu",444,214,18,MUTED);
        text(canvas,String.format(Locale.ROOT,"L %.2f  %.2f",state.leftX(),state.leftY()),204,373,20,MUTED);
        text(canvas,String.format(Locale.ROOT,"R %.2f  %.2f",state.rightX(),state.rightY()),598,373,20,MUTED);
        canvas.restore();
    }
    /** A narrower composition keeps identifiers readable instead of shrinking
     * the same wide drawing until its labels become a few pixels high. */
    private void drawCompact(Canvas canvas){
        canvas.save();float scale=Math.min(getWidth()/540f,getHeight()/420f);drawingScale=scale;
        canvas.translate((getWidth()-540*scale)/2,(getHeight()-420*scale)/2);canvas.scale(scale,scale);
        shell.reset();shell.moveTo(100,104);shell.cubicTo(60,112,38,215,24,355);
        shell.cubicTo(18,403,62,427,105,390);shell.lineTo(184,369);
        shell.cubicTo(230,388,310,388,356,369);shell.lineTo(435,390);
        shell.cubicTo(478,427,522,403,516,355);shell.cubicTo(502,215,480,112,440,104);
        shell.cubicTo(360,92,180,92,100,104);shell.close();
        paint.setStyle(Paint.Style.FILL);paint.setColor(BODY);canvas.drawPath(shell,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(EDGE);canvas.drawPath(shell,paint);paint.setStyle(Paint.Style.FILL);
        trigger(canvas,70,15,"LT",state.leftTrigger(),state.active(ControllerTestState.Control.LT));
        trigger(canvas,374,15,"RT",state.rightTrigger(),state.active(ControllerTestState.Control.RT));
        pill(canvas,65,72,110,32,"LB",state.active(ControllerTestState.Control.LB));
        pill(canvas,365,72,110,32,"RB",state.active(ControllerTestState.Control.RB));
        stick(canvas,140,185,"L3",state.leftX(),state.leftY(),state.active(ControllerTestState.Control.L3),state.leftMoved());
        stick(canvas,385,325,"R3",state.rightX(),state.rightY(),state.active(ControllerTestState.Control.R3),state.rightMoved());
        circle(canvas,400,148,25,"Y",state.active(ControllerTestState.Control.Y));
        circle(canvas,442,190,25,"B",state.active(ControllerTestState.Control.B));
        circle(canvas,358,190,25,"X",state.active(ControllerTestState.Control.X));
        circle(canvas,400,232,25,"A",state.active(ControllerTestState.Control.A));
        dpad(canvas,155,310);
        pill(canvas,215,250,42,29,"▱",state.active(ControllerTestState.Control.SELECT));
        pill(canvas,283,250,42,29,"≡",state.active(ControllerTestState.Control.START));
        circle(canvas,270,150,18,"●",state.active(ControllerTestState.Control.GUIDE));
        canvas.restore();
    }
    private void dpad(Canvas c,float x,float y){
        pill(c,x-16,y-48,32,32,"↑",state.active(ControllerTestState.Control.UP));
        pill(c,x-16,y+16,32,32,"↓",state.active(ControllerTestState.Control.DOWN));
        pill(c,x-48,y-16,32,32,"←",state.active(ControllerTestState.Control.LEFT));
        pill(c,x+16,y-16,32,32,"→",state.active(ControllerTestState.Control.RIGHT));
        paint.setColor(IDLE);c.drawCircle(x,y,16,paint);
    }
    private void stick(Canvas c,float x,float y,String label,float dx,float dy,boolean down,boolean moved){
        paint.setColor(IDLE);c.drawCircle(x,y,51,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);paint.setColor(down||moved?RED:EDGE);c.drawCircle(x,y,51,paint);paint.setStyle(Paint.Style.FILL);
        paint.setColor(down||moved?RED:EDGE);c.drawCircle(x+dx*19,y+dy*19,33,paint);
        text(c,label,x+dx*19,y+dy*19+8,22,down||moved?IDLE:TEXT);
    }
    private void trigger(Canvas c,float x,float y,String label,float value,boolean active){
        rounded(c,x,y,96,47,13,IDLE);if(active)rounded(c,x,y+47*(1-value),96,47*Math.max(value,.12f),10,RED);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(active?RED:EDGE);rect.set(x,y,x+96,y+47);c.drawRoundRect(rect,13,13,paint);paint.setStyle(Paint.Style.FILL);
        text(c,label,x+48,y+32,24,TEXT);
    }
    private void circle(Canvas c,float x,float y,float radius,String label,boolean active){
        paint.setColor(active?RED:IDLE);c.drawCircle(x,y,radius,paint);text(c,label,x,y+8,24,active?IDLE:TEXT);
    }
    private void pill(Canvas c,float x,float y,float width,float height,String label,boolean active){
        rounded(c,x,y,width,height,9,active?RED:IDLE);text(c,label,x+width/2,y+height/2+8,23,active?IDLE:TEXT);
    }
    private void rounded(Canvas c,float x,float y,float width,float height,float radius,int color){
        paint.setColor(color);rect.set(x,y,x+width,y+height);c.drawRoundRect(rect,radius,radius,paint);
    }
    private void text(Canvas c,String value,float x,float y,float size,int color){
        float density=getResources().getDisplayMetrics().density;
        float font=Math.min(1.35f,Math.max(1,getResources().getConfiguration().fontScale));
        float minimum=(compact?11:12)*density*font/Math.max(.01f,drawingScale);
        paint.setColor(color);paint.setTextSize(Math.max(size,minimum));paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        Paint.FontMetrics metrics=paint.getFontMetrics();c.drawText(value,x,y-8-(metrics.ascent+metrics.descent)/2,paint);
    }
}
