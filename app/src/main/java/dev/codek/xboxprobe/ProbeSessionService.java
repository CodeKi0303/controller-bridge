package dev.codek.xboxprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.RejectedExecutionException;

import dev.codek.xboxprobe.core.ControllerProfile;
import rikka.shizuku.Shizuku;

/** Owns virtual input sessions; only a foreground Activity may consume READY and launch a game. */
public final class ProbeSessionService extends Service {
    public static final String CONNECT="connect",LAUNCH_SESSION="launch_session",STOP="stop";
    private static final String CHANNEL="virtual-gamepad-session";
    private static final long BIND_TIMEOUT_MS=8000, CLEANUP_TIMEOUT_MS=5000, READY_TIMEOUT_MS=6000;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService cleanupWorker=Executors.newSingleThreadExecutor();
    private final ExecutorService bootstrapWorker=Executors.newSingleThreadExecutor();
    private final AtomicLong generation=new AtomicLong();
    private final SessionStatusPoller statusPoller=new SessionStatusPoller();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Object ownerLock=new Object();
    private final Set<SessionHandle> ownedHandles=new HashSet<>();
    private final GameLaunchGate launchGate=new GameLaunchGate();
    private final StringBuilder log=new StringBuilder();
    private final IBinder localBinder=new LocalBinder();
    private volatile SessionHandle session;
    private volatile SessionHandle blockedHandle;
    private volatile LaunchRequest launchRequest;
    private volatile String status="입력 서비스 연결 전";
    private volatile boolean stopping,destroyed,foreground;

    public final class LocalBinder extends Binder { public ProbeSessionService service(){return ProbeSessionService.this;} }
    public static final class LaunchState {
        public final long token;
        public final String packageName,profileLabel,phase,sourcesJson,error;
        public final boolean ready,needsSource,direct;
        private LaunchState(GameLaunchGate.Snapshot s){token=s.token;packageName=s.packageName;profileLabel=s.profileLabel;
            phase=s.phase.name();sourcesJson=s.sourcesJson;error=s.error;direct=s.direct;
            ready=s.phase==GameLaunchGate.Phase.READY;needsSource=s.phase==GameLaunchGate.Phase.NEEDS_SOURCE;}
    }
    private static final class LaunchRequest {
        final long token,epoch;
        final String packageName,json;
        final ControllerProfile profile;
        volatile SessionHandle handle;
        LaunchRequest(long token,long epoch,String packageName,String json,ControllerProfile profile){
            this.token=token;this.epoch=epoch;this.packageName=packageName;this.json=json;this.profile=profile;}
    }
    private static final class BoundEndpoint {
        final IBinder binder;
        final IProbeService remote;
        volatile boolean cleanObserved;
        BoundEndpoint(IBinder binder){this.binder=binder;this.remote=IProbeService.Stub.asInterface(binder);}
    }
    private final class SessionHandle {
        final long ownerEpoch;
        final ConnectionMode mode=ConnectionMode.get(ProbeSessionService.this);
        volatile StandaloneBackend.SessionTicket directTicket;
        final String id=UUID.randomUUID().toString();
        final CountDownLatch bound=new CountDownLatch(1);
        final ExactBindingLedger<IBinder,BoundEndpoint> bindings=new ExactBindingLedger<>();
        final SessionResourceAdmission resources=new SessionResourceAdmission();
        final Shizuku.UserServiceArgs args=new Shizuku.UserServiceArgs(new ComponentName(ProbeSessionService.this,ProbeUserService.class))
                .daemon(false).tag("input-session-"+id).processNameSuffix("uhid").debuggable(false).version(BuildConfig.VERSION_CODE);
        volatile IProbeService remote;
        volatile IBinder binder;
        volatile boolean bindInvoked,closing,cleaned,unbindRequested;
        volatile String bindError="",cleanupError="";
        SessionHandle(long ownerEpoch){this.ownerEpoch=ownerEpoch;}
        final ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder value){
                acceptBinding(SessionHandle.this,value);
            }
            @Override public void onServiceDisconnected(ComponentName name){
                for(BoundEndpoint endpoint:bindings.snapshot())if(!endpoint.binder.isBinderAlive())onBinderDeath(SessionHandle.this,endpoint.binder);
                if(remote==null){bindError="Shizuku disconnected before delivering its service binder";bound.countDown();}
            }
        };
    }

    public String status(){return status;}
    public boolean connected(){SessionHandle h=session;return h!=null&&h.remote!=null&&!h.closing&&h.binder!=null&&h.binder.isBinderAlive();}
    private boolean noOwnedResources(){synchronized(ownerLock){return ownedHandles.isEmpty();}}
    private SessionHandle firstOwned(){synchronized(ownerLock){return ownedHandles.isEmpty()?null:ownedHandles.iterator().next();}}
    public boolean isSessionActive(){return foreground||!noOwnedResources()||stopping;}
    public LaunchState launchState(){return new LaunchState(launchGate.snapshot());}
    public synchronized String log(){return log.toString();}
    private synchronized void append(String text){if(log.length()>12000)log.delete(0,log.length()-8000);
        log.append(android.text.format.DateFormat.format("HH:mm:ss",System.currentTimeMillis())).append("  ").append(text).append('\n');}
    private void result(String value){status=value;append(value);}
    private void fail(Exception e){result("연결/장치 오류: "+e.getClass().getSimpleName()+": "+e.getMessage());}
    private boolean current(long epoch){return generation.get()==epoch&&!destroyed&&!stopping;}
    private boolean launchCurrent(LaunchRequest r){return r==launchRequest&&generation.get()==r.epoch&&!destroyed&&launchGate.current(r.token);}
    private void onMain(Runnable action){if(Looper.myLooper()==Looper.getMainLooper())action.run();else main.post(action);}
    private void postWorker(Runnable action){try{worker.execute(action);}catch(RejectedExecutionException e){if(!destroyed)fail(e);}}
    private void postCleanup(Runnable action){try{cleanupWorker.execute(action);}catch(RejectedExecutionException e){
        SessionHandle unresolved=firstOwned();if(unresolved!=null)blockCleanup(unresolved,"정리 작업을 예약하지 못했습니다: "+e.getMessage());}}
    private interface RemoteCall{String run(IProbeService service)throws Exception;}
    /** All work that may open a device or inventory child crosses this owner.
     * Record admission before IPC, even if the call later fails or is cancelled. */
    private String resourceCall(SessionHandle handle,long epoch,RemoteCall call)throws Exception{
        final IProbeService remote;
        synchronized(ownerLock){
            if(handle==null||!current(epoch)||session!=handle||handle.closing||handle.remote==null||!handle.resources.admit())
                throw new IOException("Input resource operation was cancelled before admission");
            remote=handle.remote;
        }
        return call.run(remote);
    }

    /** A new user action revokes old queued work, then passes through the cleanup barrier. */
    public long prepareGameLaunch(String packageName,String profileJson){
        final ControllerProfile profile;
        try{
            ProfileCodec.validatePackageName(packageName);
            profile=ProfileCodec.decode(profileJson);
        }catch(Exception e){
            final long epoch,token;final SessionHandle old;
            synchronized(ownerLock){stopping=true;epoch=generation.incrementAndGet();old=session;launchRequest=null;
                token=launchGate.begin(packageName==null?"":packageName,"",false);launchGate.fail(token,"프로필을 읽을 수 없습니다: "+e.getMessage());}
            postCleanup(()->{if(closeHandle(old))finishStopped(epoch,true);else blockCleanup(old,old.cleanupError);});return token;
        }
        final long epoch,token;final LaunchRequest request;final SessionHandle superseded;
        synchronized(ownerLock){
            stopping=true;epoch=generation.incrementAndGet();
            token=launchGate.begin(packageName,profile.getLabel(),profile.getKind()==ControllerProfile.Kind.DIRECT);
            request=new LaunchRequest(token,epoch,packageName,profileJson,profile);launchRequest=request;superseded=session;
        }
        if(!launchGate.current(token)){
            // A blocked request must still be able to retry exact cleanup. It
            // cannot become a launch automatically when recovery clears BLOCKED.
            postCleanup(()->{
                if(!closeHandle(superseded)){blockCleanup(superseded,superseded.cleanupError);return;}
                synchronized(ownerLock){
                    if(generation.get()!=epoch||destroyed)return;
                    SessionHandle unresolved=firstOwned();
                    if(unresolved!=null){blockCleanup(unresolved,"이전 입력 세션의 소유 자원이 아직 남아 있습니다");return;}
                    result("이전 입력 세션을 정리했습니다. 게임 실행을 다시 눌러 주세요.");
                }
                finishStopped(epoch,true);
            });
            return token;
        }
        onMain(()->{synchronized(ownerLock){if(launchCurrent(request)&&launchGate.snapshot().phase!=GameLaunchGate.Phase.HANDED_OFF)enterForeground();}});
        postCleanup(()->{
            if(!closeHandle(superseded)){blockCleanup(superseded,superseded.cleanupError);return;}
            synchronized(ownerLock){
                if(!launchCurrent(request))return;
                SessionHandle unresolved=firstOwned();
                if(unresolved!=null){blockCleanup(unresolved,"이전 입력 세션의 소유 자원이 아직 남아 있습니다");return;}
                stopping=false;if(!launchGate.afterCleanup(token))return;
            }
            if(profile.getKind()==ControllerProfile.Kind.DIRECT){synchronized(ownerLock){if(launchCurrent(request))result("직접 입력 준비 완료 · 가상 장치 없음");}return;}
            postWorker(()->prepareInput(request));
        });
        return token;
    }

    private void prepareInput(LaunchRequest request){
        try{
            requireLaunch(request);SessionHandle handle=createSession(request.epoch);request.handle=handle;
            awaitBound(handle);requireLaunch(request);
            if(!launchGate.afterConnected(request.token))return;
            inspectGameSources(request,handle);
        }catch(Exception e){launchFailure(request,e);}
    }

    private void inspectGameSources(LaunchRequest request,SessionHandle handle)throws Exception{
        requireLaunch(request);if(session!=handle||handle.closing)throw new IOException("Input session changed");
        String catalog=resourceCall(handle,request.epoch,IProbeService::listSources);requireLaunch(request);
        JSONObject parsed=new JSONObject(catalog);JSONArray entries=parsed.getJSONArray("sources");
        // Missing/malformed error coverage must not be treated as a complete scan.
        JSONArray errors=parsed.getJSONArray("errors");ArrayList<String> tokens=new ArrayList<>();
        for(int i=0;i<entries.length();i++){
            JSONObject entry=entries.getJSONObject(i);
            // Catalog already enforced source selection policy and retains opaque identity tokens.
            tokens.add(entry.getString("token"));
        }
        String selected;
        synchronized(ownerLock){requireLaunch(request);
            selected=launchGate.sourcesListed(request.token,catalog,tokens,errors.length()==0);
            if(selected==null&&launchGate.snapshot().phase==GameLaunchGate.Phase.NEEDS_SOURCE)
                result(tokens.size()>1?"게임패드가 여러 개입니다. 사용할 장치를 선택해 주세요":
                        errors.length()>0?"장치 조회 중 오류가 있습니다. 목록을 확인하고 게임패드를 선택해 주세요":"사용할 게임패드를 선택해 주세요");}
        if(selected!=null)startGameBridge(request,handle,selected);
    }

    public void selectGameSource(long token,String sourceToken){
        LaunchRequest request=launchRequest;
        if(request==null||request.token!=token||launchGate.snapshot().phase!=GameLaunchGate.Phase.NEEDS_SOURCE||!launchCurrent(request))return;
        if(sourceToken==null||sourceToken.isEmpty()){launchFailure(request,new IOException("게임패드를 선택해 주세요"));return;}
        if(!launchGate.sourceSelected(token))return;
        SessionHandle handle=request.handle;postWorker(()->{try{startGameBridge(request,handle,sourceToken);}catch(Exception e){launchFailure(request,e);}});
    }

    public void refreshGameSources(long token){
        LaunchRequest request=launchRequest;
        if(request==null||request.token!=token||!launchCurrent(request)||!launchGate.relist(token))return;
        SessionHandle handle=request.handle;
        postWorker(()->{try{inspectGameSources(request,handle);}catch(Exception e){launchFailure(request,e);}});
    }

    private void startGameBridge(LaunchRequest request,SessionHandle handle,String sourceToken)throws Exception{
        requireLaunch(request);if(handle==null||session!=handle||handle.closing||handle.remote==null)throw new IOException("Input session changed");
        JSONObject created=new JSONObject(resourceCall(handle,request.epoch,remote->remote.startConfiguredBridge(sourceToken,request.json)));
        synchronized(ownerLock){
            requireLaunch(request);status=created.toString();
            if(!created.optString("lastError").isEmpty())throw new IOException(created.getString("lastError"));
            if(!launchGate.waitingForInput(request.token))return;
        }
        long deadline=SystemClock.elapsedRealtime()+READY_TIMEOUT_MS;
        while(SystemClock.elapsedRealtime()<deadline){
            requireLaunch(request);if(session!=handle||handle.closing)throw new IOException("Input session changed");
            JSONObject value=new JSONObject(handle.remote.status());
            if(value.optBoolean("sessionClosing"))throw new IOException("Input session is closing");
            if(!value.optString("lastError").isEmpty())throw new IOException(value.getString("lastError"));
            if(!request.profile.getId().equals(value.optString("configuredProfileId")))throw new IOException("Output profile identity does not match the requested profile");
            JSONObject bridge=value.optJSONObject("bridge");boolean running=bridge!=null&&"running".equals(bridge.optString("state"));
            if(value.optBoolean("deviceOpened")&&value.optBoolean("kernelStarted")&&value.optBoolean("consumerOpen")&&running&&!value.optBoolean("outputLayoutVerified")){
                value=new JSONObject(resourceCall(handle,request.epoch,IProbeService::verifyConfiguredOutputLayout));
                requireLaunch(request);
                if(!value.optString("lastError").isEmpty())throw new IOException(value.getString("lastError"));
                bridge=value.optJSONObject("bridge");running=bridge!=null&&"running".equals(bridge.optString("state"));
            }
            boolean ready;
            synchronized(ownerLock){requireLaunch(request);status=value.toString();
                ready=launchGate.inputReady(request.token,value.optBoolean("deviceOpened"),value.optBoolean("kernelStarted"),value.optBoolean("consumerOpen"),running&&value.optBoolean("outputLayoutVerified"));}
            if(ready){
                handle.remote.keepAlive();synchronized(ownerLock){if(launchCurrent(request))result("입력 프로필 준비 완료: "+request.profile.getLabel());}return;}
            Thread.sleep(100);
        }
        throw new IOException("가상 입력 장치 준비 시간이 초과되었습니다");
    }

    /** The Activity must check its own foreground/user-action state before calling this. */
    public boolean consumeGameLaunchReady(long token){
        LaunchRequest request=launchRequest;
        if(request==null||request.token!=token||!launchCurrent(request)||stopping||launchGate.snapshot().phase!=GameLaunchGate.Phase.READY)return false;
        try {
            if(request.profile.getKind()==ControllerProfile.Kind.DIRECT){if(!noOwnedResources())throw new IOException("이전 가상 입력 세션이 남아 있습니다");}
            else {
                SessionHandle handle=request.handle;
                if(handle==null||session!=handle||handle.closing||handle.remote==null)throw new IOException("입력 세션이 변경되었습니다");
                JSONObject now=new JSONObject(handle.remote.status());JSONObject bridge=now.optJSONObject("bridge");
                if(now.optBoolean("sessionClosing")||!now.optString("lastError").isEmpty()||!now.optBoolean("deviceOpened")
                        ||!now.optBoolean("kernelStarted")||!now.optBoolean("consumerOpen")||bridge==null||!"running".equals(bridge.optString("state"))
                        ||!now.optBoolean("outputLayoutVerified")||!request.profile.getId().equals(now.optString("configuredProfileId")))throw new IOException("실행 직전 입력 준비 상태가 변경되었습니다");
            }
        }catch(Exception e){launchFailure(request,e);return false;}
        final boolean consumed,direct;
        synchronized(ownerLock){if(!launchCurrent(request)||stopping)return false;consumed=launchGate.consumeReady(token);direct=launchGate.snapshot().direct;}
        if(consumed&&direct)onMain(()->{synchronized(ownerLock){if(generation.get()==request.epoch&&launchGate.snapshot().token==token&&launchGate.snapshot().phase==GameLaunchGate.Phase.HANDED_OFF){leaveForeground();stopSelf();}}});
        return consumed;
    }
    public void cancelGameLaunch(long token){requestShutdown(true,token);}
    private void requireLaunch(LaunchRequest request)throws IOException{if(!launchCurrent(request))throw new IOException("Game launch request was cancelled or replaced");}
    private void launchFailure(LaunchRequest request,Exception error){
        final long epoch;final SessionHandle expected;
        synchronized(ownerLock){
            if(!launchCurrent(request)||!launchGate.fail(request.token,"입력 준비 실패: "+error.getMessage()))return;
            stopping=true;epoch=generation.incrementAndGet();result("입력 준비 실패: "+error.getMessage());
            SessionHandle owned=request.handle;
            if(owned==null){SessionHandle current=session;if(current!=null&&current.ownerEpoch==request.epoch)owned=current;}
            expected=owned;
        }
        postCleanup(()->{if(closeHandle(expected))finishStopped(epoch,true);else blockCleanup(expected,"입력 준비 실패 후 정리를 확인하지 못했습니다: "+expected.cleanupError);});
    }

    private SessionHandle createSession(long epoch)throws Exception{
        if(!current(epoch))throw new IOException("Session request was cancelled");
        SessionHandle handle=new SessionHandle(epoch);
        synchronized(ownerLock){if(!current(epoch))throw new IOException("Session request was cancelled");
            if(!ownedHandles.isEmpty())throw new IOException("Previous session still owns resources");session=handle;ownedHandles.add(handle);}
        main.post(()->{
            if(destroyed||handle.closing||generation.get()!=epoch){handle.bindError="Binding was cancelled before starting";handle.bound.countDown();return;}
            try{
                if(handle.mode==ConnectionMode.STANDALONE){
                    handle.directTicket=StandaloneBackend.prepareSession(ProbeSessionService.this,new StandaloneBackend.SessionCallback(){
                        @Override public void onBound(IBinder value){acceptBinding(handle,value);}
                        @Override public void onFailure(Exception error){handle.bindError=error.getMessage();handle.bound.countDown();}
                    });
                    handle.bindInvoked=true;
                    bootstrapWorker.execute(()->{
                        try{handle.directTicket.start();}
                        catch(Exception error){handle.bindError=error.getClass().getSimpleName()+": "+error.getMessage();handle.bound.countDown();}
                    });
                    return;
                }
                if(!Shizuku.pingBinder())throw new IllegalStateException("Shizuku가 실행되지 않았습니다");
                if(Shizuku.getUid()!=2000)throw new IllegalStateException("ADB(shell UID 2000) 모드만 사용합니다");
                if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("Shizuku 권한이 필요합니다");
                handle.bindInvoked=true;Shizuku.bindUserService(handle.args,handle.connection);
            }catch(Exception e){handle.bindError=e.getClass().getSimpleName()+": "+e.getMessage();handle.bound.countDown();}
        });
        return handle;
    }
    private void awaitBound(SessionHandle handle)throws Exception{
        if(!handle.bound.await(BIND_TIMEOUT_MS,TimeUnit.MILLISECONDS))throw new IOException("입력 서비스 연결 시간이 초과되었습니다");
        if(!handle.bindError.isEmpty())throw new IOException(handle.bindError);
        if(handle.remote==null||handle.binder==null||!handle.binder.isBinderAlive())throw new IOException("입력 서비스에 연결할 수 없습니다");
    }
    private void acceptBinding(SessionHandle handle,IBinder binder){
        BoundEndpoint endpoint=new BoundEndpoint(binder);boolean unexpected;
        synchronized(ownerLock){
            ExactBindingLedger.Admission<BoundEndpoint> admission=handle.bindings.admit(binder,endpoint);
            if(admission.kind==ExactBindingLedger.Kind.DUPLICATE){handle.bound.countDown();return;}
            unexpected=admission.kind==ExactBindingLedger.Kind.ADDITIONAL||handle.cleaned;
            if(admission.kind==ExactBindingLedger.Kind.PRIMARY){handle.binder=binder;handle.remote=endpoint.remote;}
            // A redelivery is a newly observed resource, never a replacement for
            // the primary endpoint or evidence that an earlier endpoint died.
            handle.bindInvoked=true;
            if(unexpected){handle.cleaned=false;handle.closing=true;ownedHandles.add(handle);}
        }
        try{binder.linkToDeath(()->onBinderDeath(handle,binder),0);}catch(Exception e){
            if(!binder.isBinderAlive())onBinderDeath(handle,binder);
            else handle.bindError="Binder death observation could not be registered: "+e.getMessage();
        }
        handle.bound.countDown();
        if(unexpected){
            final long epoch;final ArrayList<SessionHandle> captured;
            synchronized(ownerLock){stopping=true;epoch=generation.incrementAndGet();captured=new ArrayList<>(ownedHandles);
                blockedHandle=handle;launchGate.blockCleanup("입력 서비스가 다른 Binder를 다시 전달했습니다. 기존 세션을 종료합니다");}
            postCleanup(()->{boolean all=true;for(SessionHandle owned:captured)if(!closeHandle(owned)){all=false;blockCleanup(owned,owned.cleanupError);}
                if(all)finishStopped(epoch,true);});
        }else if(handle.closing||destroyed||session!=handle)queueDetachedCleanup(handle);
    }
    private void onBinderDeath(SessionHandle handle,IBinder exactBinder){
        if(!handle.bindings.markDead(exactBinder))return;
        if(handle.binder==exactBinder&&session==handle&&!handle.closing&&!destroyed){
            result("입력 서비스가 종료되었습니다");LaunchRequest request=launchRequest;
            if(request!=null&&request.handle==handle)launchFailure(request,new IOException("입력 서비스 연결이 종료되었습니다"));
        }
    }
    private void queueDetachedCleanup(SessionHandle handle){
        if(cleanupWorker.isShutdown())return;
        postCleanup(()->{if(!closeHandle(handle))blockCleanup(handle,handle.cleanupError);});
    }

    /** Returns true only after explicit resource closure and the exact binder's death. */
    private boolean closeHandle(SessionHandle handle){
        if(handle==null)return true;
        synchronized(handle){
            if(handle.cleaned){clearOwner(handle);return true;}
            synchronized(ownerLock){handle.closing=true;handle.resources.close();}
            try{
                boolean delivered=handle.bound.await(BIND_TIMEOUT_MS,TimeUnit.MILLISECONDS);
                if(handle.mode==ConnectionMode.STANDALONE&&handle.directTicket!=null&&handle.bindings.snapshot().isEmpty()){
                    handle.directTicket.finish();
                    // finish() synchronizes with provider admission and confirms
                    // the exact unbound child exited. It never exposed input APIs.
                    if(handle.bindings.snapshot().isEmpty()){handle.cleaned=true;clearOwner(handle);return true;}
                }
                if(!delivered)throw new IOException("입력 서비스 생성/취소 상태를 확인하지 못했습니다");
                if(!handle.bindInvoked){handle.cleaned=true;clearOwner(handle);return true;}
                if(handle.bindings.snapshot().isEmpty())throw new IOException("생성 요청의 Binder를 받지 못해 소유 자원 정리를 확인할 수 없습니다");
                if(retireDeadIdleStandalone(handle))return true;
                long deadline=SystemClock.elapsedRealtime()+CLEANUP_TIMEOUT_MS;
                Set<BoundEndpoint> attempted=new HashSet<>();Exception firstFailure=null;
                while(true){
                    boolean found=false;
                    for(BoundEndpoint endpoint:handle.bindings.snapshot()){
                        if(endpoint.cleanObserved||attempted.contains(endpoint))continue;found=true;attempted.add(endpoint);
                        try{
                            if(handle.bindings.isDead(endpoint.binder)||!endpoint.binder.isBinderAlive())throw new IOException("입력 서비스가 종료되어 종료 전 자원 정리를 확인할 수 없습니다");
                            JSONObject value=new JSONObject(endpoint.remote.stop());
                            while(true){
                                status=value.toString();JSONObject cleanup=value.optJSONObject("cleanup");
                                if(cleanup!=null&&cleanup.optBoolean("complete")&&!value.optBoolean("deviceOpened")){endpoint.cleanObserved=true;break;}
                                if(SystemClock.elapsedRealtime()>=deadline)throw new IOException("가상 장치/소스/보조 프로세스 정리가 아직 확인되지 않았습니다");
                                Thread.sleep(50);value=new JSONObject(endpoint.remote.status());
                            }
                        }catch(Exception e){if(firstFailure==null)firstFailure=e;else firstFailure.addSuppressed(e);}
                    }
                    if(!found||SystemClock.elapsedRealtime()>=deadline)break;
                }
                if(firstFailure!=null)throw firstFailure;
                for(BoundEndpoint endpoint:handle.bindings.snapshot())if(!endpoint.cleanObserved)throw new IOException("새 Binder의 정리가 아직 확인되지 않았습니다");
                refreshBindingDeaths(handle);
                if(!handle.bindings.allDead()){
                    if(handle.mode==ConnectionMode.STANDALONE){
                        handle.unbindRequested=true;destroyObservedEndpoints(handle);
                    }else runMainAndWait(()->{
                            if(!Shizuku.pingBinder())throw new IllegalStateException("Shizuku 종료 요청을 전달할 수 없습니다");
                            handle.unbindRequested=true;Shizuku.unbindUserService(handle.args,handle.connection,true);
                        });
                    // A redelivery is not allowed to replace the primary. Teardown
                    // every exact endpoint observed for this request, including a
                    // prior instance no longer represented by the server's tag.
                    if(handle.bindings.snapshot().size()>1)destroyObservedEndpoints(handle);
                    deadline=SystemClock.elapsedRealtime()+3000;
                    while(!handle.bindings.allDead()){
                        refreshBindingDeaths(handle);if(handle.bindings.allDead())break;
                        if(SystemClock.elapsedRealtime()>=deadline)throw new IOException("소유한 입력 Binder 각각의 종료를 확인하지 못했습니다");
                        Thread.sleep(25);
                    }
                }
                if(handle.mode==ConnectionMode.STANDALONE&&handle.directTicket!=null)handle.directTicket.finish();
                synchronized(ownerLock){
                    for(BoundEndpoint endpoint:handle.bindings.snapshot())if(!endpoint.cleanObserved||!handle.bindings.isDead(endpoint.binder))throw new IOException("정리 중 새 Binder가 관찰되었습니다");
                    handle.cleaned=true;handle.cleanupError="";clearOwner(handle);return true;
                }
            }catch(Exception e){
                if(e instanceof InterruptedException)Thread.currentThread().interrupt();
                handle.cleanupError="정리 확인 필요: "+e.getMessage();append(handle.cleanupError);
                // Request teardown of this exact owned tag, but do not declare
                // cleanup successful merely because the request was submitted.
                if(handle.mode==ConnectionMode.SHIZUKU&&handle.bindInvoked&&!handle.unbindRequested)main.post(()->{try{if(Shizuku.pingBinder()){handle.unbindRequested=true;Shizuku.unbindUserService(handle.args,handle.connection,true);}}catch(Exception ignored){}});
                destroyObservedEndpoints(handle);
                if(handle.mode==ConnectionMode.STANDALONE&&handle.directTicket!=null){
                    try{handle.directTicket.finish();}catch(Exception cleanupError){append("앱 자체 helper 정리 확인 필요: "+cleanupError.getMessage());}
                }
                return false;
            }
        }
    }
    /** Process death is enough only for a helper that was never allowed to
     * allocate resources. Active helpers may leave an inventory subprocess, so
     * they still require the normal explicit cleanup acknowledgement. */
    private boolean retireDeadIdleStandalone(SessionHandle handle)throws Exception{
        final StandaloneBackend.SessionTicket ticket;
        final BoundEndpoint endpoint;
        synchronized(ownerLock){
            if(handle.mode!=ConnectionMode.STANDALONE||handle.directTicket==null||!handle.resources.closedUnused())return false;
            java.util.List<BoundEndpoint> endpoints=handle.bindings.snapshot();
            if(endpoints.size()!=1)return false;
            ticket=handle.directTicket;endpoint=endpoints.get(0);
            if(endpoint.binder!=ticket.binder||endpoint.binder!=handle.binder||endpoint.binder.isBinderAlive())return false;
            handle.bindings.markDead(endpoint.binder);
        }
        // Retain the exact ticket until its owner confirms termination. A dead
        // host alone, an unknown child, or failed cleanup cannot satisfy this.
        ticket.finish();
        synchronized(ownerLock){
            java.util.List<BoundEndpoint> endpoints=handle.bindings.snapshot();
            if(handle.directTicket!=ticket||!handle.resources.closedUnused()||endpoints.size()!=1
                    ||endpoints.get(0)!=endpoint||endpoint.binder!=ticket.binder||endpoint.binder.isBinderAlive())
                throw new IOException("Idle helper identity changed during cleanup");
            endpoint.cleanObserved=true;handle.bindings.markDead(endpoint.binder);
            handle.cleaned=true;handle.cleanupError="";clearOwner(handle);
            append("종료된 대기 입력 세션 정리 확인 완료 · 입력 자원 작업 없음");
            return true;
        }
    }
    private void refreshBindingDeaths(SessionHandle handle){
        for(BoundEndpoint endpoint:handle.bindings.snapshot())if(!endpoint.binder.isBinderAlive())handle.bindings.markDead(endpoint.binder);
    }
    private void destroyObservedEndpoints(SessionHandle handle){
        for(BoundEndpoint endpoint:handle.bindings.snapshot()){
            if(handle.bindings.isDead(endpoint.binder)||!endpoint.binder.isBinderAlive()){handle.bindings.markDead(endpoint.binder);continue;}
            try{endpoint.remote.destroy();}catch(Exception e){if(endpoint.binder.isBinderAlive())append("소유 Binder 종료 요청 실패: "+e.getMessage());}
            if(!endpoint.binder.isBinderAlive())handle.bindings.markDead(endpoint.binder);
        }
    }
    private interface MainAction{void run()throws Exception;}
    private void runMainAndWait(MainAction action)throws Exception{
        CountDownLatch done=new CountDownLatch(1);Exception[] error=new Exception[1];
        main.post(()->{try{action.run();}catch(Exception e){error[0]=e;}finally{done.countDown();}});
        if(!done.await(3000,TimeUnit.MILLISECONDS))throw new IOException("메인 스레드의 세션 종료 처리가 지연되었습니다");
        if(error[0]!=null)throw error[0];
    }
    private void clearOwner(SessionHandle handle){
        synchronized(ownerLock){if(session==handle)session=null;ownedHandles.remove(handle);
            if(blockedHandle==handle){blockedHandle=null;launchGate.cleanupRecovered();}
            if(destroyed&&ownedHandles.isEmpty())cleanupWorker.shutdown();}
    }
    private void blockCleanup(SessionHandle handle,String message){synchronized(ownerLock){
        if(handle!=null&&handle.cleaned&&!ownedHandles.contains(handle))return;
        blockedHandle=handle;stopping=true;launchGate.blockCleanup(message);result(message);}}
    private void finishStopped(long epoch,boolean stopService){
        synchronized(ownerLock){if(generation.get()!=epoch||destroyed)return;stopping=false;}
        onMain(()->{synchronized(ownerLock){if(generation.get()!=epoch||destroyed)return;leaveForeground();if(stopService)stopSelf();}});
    }

    public void refreshStatus(){readStatus();}
    private void readStatus(){
        if(destroyed||stopping||!connected())return;
        long epoch=generation.get();SessionHandle handle=session;
        if(handle==null||handle.remote==null)return;
        IProbeService remote=handle.remote;
        statusPoller.request(worker,()->current(epoch)&&session==handle&&!handle.closing&&handle.remote==remote,
                new SessionStatusPoller.Remote(){
                    @Override public void keepAlive()throws Exception{remote.keepAlive();}
                    @Override public String status()throws Exception{return remote.status();}
                },value->status=value,this::fail);
    }
    private final Runnable heartbeat=new Runnable(){public void run(){if(destroyed)return;readStatus();main.postDelayed(this,10000);}};
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL,"가상 입력 장치 세션",NotificationManager.IMPORTANCE_LOW));}
    @Override public IBinder onBind(Intent intent){return localBinder;}
    private void enterForeground(){if(foreground||destroyed)return;
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ProbeSessionService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("게임패드 입력 프로필 실행 중").setContentText("세션 종료 시 입력을 중립으로 돌리고 가상 장치를 제거합니다")
                .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"세션 종료",stop).build()).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(10,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(10,notification);
        foreground=true;main.removeCallbacks(heartbeat);main.post(heartbeat);
    }
    private void leaveForeground(){if(foreground){stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;}main.removeCallbacks(heartbeat);}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}String action=intent.getAction();
        if(STOP.equals(action)){requestShutdown(true);return START_NOT_STICKY;}
        if(LAUNCH_SESSION.equals(action)){enterForeground();return START_NOT_STICKY;}
        if(!CONNECT.equals(action)){if(!foreground)stopSelfResult(startId);return START_NOT_STICKY;}
        if(stopping){result("이전 세션 정리 중이거나 정리 확인이 필요합니다");return START_NOT_STICKY;}
        enterForeground();
        if(CONNECT.equals(action)){
            LaunchRequest preparing=launchRequest;
            if(session==null&&(preparing==null||!launchGate.current(preparing.token))){long epoch=generation.get();postWorker(()->{
                if(session!=null||!current(epoch))return;SessionHandle handle=null;
                try{handle=createSession(epoch);awaitBound(handle);if(current(epoch)&&session==handle){handle.remote.keepAlive();result(handle.remote.status());}}
                catch(Exception e){if(current(epoch))fail(e);SessionHandle failed=handle;if(failed!=null)postCleanup(()->{if(!closeHandle(failed))blockCleanup(failed,failed.cleanupError);});}
            });}
        }
        return START_NOT_STICKY;
    }
    private void requestShutdown(boolean cancelLaunch){requestShutdown(cancelLaunch,null);}
    private void requestShutdown(boolean cancelLaunch,Long expectedToken){
        final long epoch;final SessionHandle handle;
        synchronized(ownerLock){
            if(expectedToken!=null&&!launchGate.cancel(expectedToken))return;
            if(cancelLaunch){GameLaunchGate.Snapshot snapshot=launchGate.snapshot();launchGate.cancel(snapshot.token);launchRequest=null;}
            stopping=true;epoch=generation.incrementAndGet();handle=session;
        }
        onMain(()->{synchronized(ownerLock){if(generation.get()==epoch)main.removeCallbacks(heartbeat);}});
        postCleanup(()->{if(closeHandle(handle))finishStopped(epoch,true);else blockCleanup(handle,handle.cleanupError);});
    }
    @Override public void onDestroy(){
        ArrayList<SessionHandle> pending;
        synchronized(ownerLock){destroyed=true;generation.incrementAndGet();GameLaunchGate.Snapshot snapshot=launchGate.snapshot();launchGate.cancel(snapshot.token);pending=new ArrayList<>(ownedHandles);}
        main.removeCallbacks(heartbeat);
        for(SessionHandle handle:pending)if(!handle.cleaned)postCleanup(()->closeHandle(handle));
        worker.shutdown();bootstrapWorker.shutdown();synchronized(ownerLock){if(ownedHandles.isEmpty())cleanupWorker.shutdown();}super.onDestroy();
    }
}
