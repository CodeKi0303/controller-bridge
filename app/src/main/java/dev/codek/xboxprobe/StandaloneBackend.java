package dev.codek.xboxprobe;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/** App-owned bootstrap admission and exact shell-launcher/session Binder handles. */
public final class StandaloneBackend {
    private static final String TAG="BridgeBootstrap";
    private enum AdmissionStage { REQUEST, ENDPOINT, CAPABILITY, IDENTITY, ROLE, DESCRIPTOR, HOST, SESSION, ACKNOWLEDGED }
    private static final Object LOCK=new Object();
    private static final long BOOTSTRAP_TTL_MS=180_000;
    private static final BootstrapAuthorization<Admission> ADMISSIONS=new BootstrapAuthorization<>();
    private static final Map<String,SessionTicket> SESSIONS=new LinkedHashMap<>();
    private static final IBinder APP_OWNER=new Binder();
    private static IStandaloneHost host;
    private static String pendingHost;
    private static boolean stoppingHost;
    private StandaloneBackend(){}

    public static final class BootstrapRequest {
        public final String command,nonce;
        public final long expiresAtElapsedMs;
        private BootstrapRequest(String command,String nonce,long expires){this.command=command;this.nonce=nonce;expiresAtElapsedMs=expires;}
    }
    public interface SessionCallback {
        void onBound(IBinder binder);
        void onFailure(Exception error);
    }
    private static final class Admission {
        final InstalledBridgeIdentity identity;final SessionTicket session;
        Admission(InstalledBridgeIdentity identity,SessionTicket session){this.identity=identity;this.session=session;}
    }
    public static boolean isAvailable(){synchronized(LOCK){return !stoppingHost&&host!=null&&host.asBinder().isBinderAlive();}}
    public static boolean hasSessions(){synchronized(LOCK){return !SESSIONS.isEmpty();}}
    public static BootstrapRequest prepareBootstrap(Context context)throws Exception {
        InstalledBridgeIdentity identity=InstalledBridgeIdentity.read(context);
        String nonce=nonce();long now=SystemClock.elapsedRealtime();
        synchronized(LOCK){
            if(stoppingHost||host!=null&&host.asBinder().isBinderAlive())throw new IOException("The previous standalone helper must finish stopping first");
            if(pendingHost!=null)ADMISSIONS.remove(pendingHost);
            ADMISSIONS.add(nonce,new Admission(identity,null),now,BOOTSTRAP_TTL_MS);pendingHost=nonce;
        }
        return new BootstrapRequest(BootstrapProtocol.command(identity.apk,identity.uid,identity.signer,nonce),nonce,now+BOOTSTRAP_TTL_MS);
    }
    public static void cancelBootstrap(String nonce){synchronized(LOCK){ADMISSIONS.remove(nonce);if(nonce.equals(pendingHost))pendingHost=null;}}

    /** Reserve before starting, so cancellation always owns the exact request. */
    static SessionTicket prepareSession(Context context,SessionCallback callback)throws Exception {
        InstalledBridgeIdentity identity=InstalledBridgeIdentity.read(context);
        synchronized(LOCK){
            if(!isAvailable())throw new IOException("먼저 앱 자체 디버깅 연결을 시작해 주세요");
            SessionTicket ticket=new SessionTicket(nonce(),host,callback);
            ADMISSIONS.add(ticket.nonce,new Admission(identity,ticket),SystemClock.elapsedRealtime(),15_000);
            SESSIONS.put(ticket.nonce,ticket);return ticket;
        }
    }
    static final class SessionTicket {
        final String nonce;final IStandaloneHost owner;final SessionCallback callback;
        volatile IBinder binder;
        private boolean started,finished;
        private SessionTicket(String nonce,IStandaloneHost owner,SessionCallback callback){this.nonce=nonce;this.owner=owner;this.callback=callback;}
        synchronized void start()throws Exception {
            if(started||finished)throw new IOException("Session startup was already used or cancelled");
            started=true;owner.startSession(nonce);
        }
        synchronized void finish()throws Exception {
            if(finished)return;
            // Remove admission first: a child not yet handed over must exit without
            // accepting input. The host then confirms termination of its Process.
            ADMISSIONS.remove(nonce);
            if(started){
                if(owner.asBinder().isBinderAlive()){
                    try{if(!owner.finishSession(nonce))throw new IOException("Owned helper process has not exited");}
                    catch(android.os.DeadObjectException error){if(binder==null||binder.isBinderAlive())throw error;}
                }else if(binder==null||binder.isBinderAlive()){
                    // A dead launcher alone says nothing about its child. Once
                    // the exact admitted child Binder is dead, there is no child
                    // input endpoint left to retain solely for a missing launcher.
                    throw new IOException("Launcher died before its owned child exit could be confirmed");
                }
            }
            finished=true;synchronized(LOCK){SESSIONS.remove(nonce);}
        }
    }

    static Bundle acceptHandoff(Context context,int callingUid,Bundle extras) {
        BootstrapAuthorization.requireUid(callingUid,2000);
        Bundle reply=new Bundle();reply.putBoolean("accepted",false);
        AdmissionStage stage=AdmissionStage.REQUEST;
        try {
            String nonce=extras.getString("nonce"),role=extras.getString("role"),signer=extras.getString("signer");
            int uid=extras.getInt("uid",-1);IBinder binder=extras.getBinder("binder");
            stage=AdmissionStage.ENDPOINT;
            if(binder==null||!binder.isBinderAlive()){logAdmissionRejected(stage);return reply;}
            stage=AdmissionStage.CAPABILITY;
            Admission admission=ADMISSIONS.consume(callingUid,nonce,SystemClock.elapsedRealtime());
            if(admission==null){logAdmissionRejected(stage);return reply;}
            stage=AdmissionStage.IDENTITY;
            InstalledBridgeIdentity current=InstalledBridgeIdentity.read(context);
            current.require(admission.identity.uid,admission.identity.signer);current.require(uid,signer);
            stage=AdmissionStage.ROLE;
            String expected=admission.session==null?"host":"session";
            if(!expected.equals(role))throw new SecurityException("Bootstrap role mismatch");
            stage=AdmissionStage.DESCRIPTOR;
            String descriptor=binder.getInterfaceDescriptor();
            if(!(admission.session==null?"dev.codek.xboxprobe.IStandaloneHost":"dev.codek.xboxprobe.IProbeService").equals(descriptor))
                throw new SecurityException("Unexpected helper Binder interface");
            synchronized(LOCK){
                if(admission.session==null){
                    stage=AdmissionStage.HOST;
                    if(stoppingHost||!nonce.equals(pendingHost)||host!=null&&host.asBinder().isBinderAlive()){logAdmissionRejected(stage);return reply;}
                    IStandaloneHost selected=IStandaloneHost.Stub.asInterface(binder);
                    binder.linkToDeath(()->{synchronized(LOCK){if(host==selected){host=null;stoppingHost=false;}}},0);
                    host=selected;pendingHost=null;
                }else{
                    stage=AdmissionStage.SESSION;
                    SessionTicket ticket=admission.session;
                    if(SESSIONS.get(nonce)!=ticket||ticket.binder!=null){logAdmissionRejected(stage);return reply;}
                    ticket.binder=binder;
                    // Always deliver an admitted Binder, even if the UI moved on;
                    // the existing ownership ledger will tear it down explicitly.
                    ticket.callback.onBound(binder);
                }
            }
            reply.putBinder("owner",APP_OWNER);reply.putBoolean("accepted",true);
            Log.i(TAG,"admission stage="+AdmissionStage.ACKNOWLEDGED.name());return reply;
        }catch(Exception error){
            Log.w(TAG,"admission failed stage="+stage.name()+" type="+FailureTypes.className(FailureTypes.reflectionCause(error)));
            return reply;
        }
    }

    private static void logAdmissionRejected(AdmissionStage stage){Log.w(TAG,"admission rejected stage="+stage.name());}

    /** Blocking teardown; call on a worker, after the foreground session is stopped. */
    public static void stopLauncher()throws Exception {
        IStandaloneHost selected;
        synchronized(LOCK){
            // Admission and shutdown share this boundary. A settings action
            // cannot destroy a session that raced a preceding UI busy check.
            if(!SESSIONS.isEmpty())throw new IOException("먼저 메인 화면에서 입력 세션을 종료해 주세요");
            stoppingHost=true;selected=host;
            if(pendingHost!=null){ADMISSIONS.remove(pendingHost);pendingHost=null;}
        }
        if(selected!=null&&selected.asBinder().isBinderAlive()){
            if(!selected.shutdown())throw new IOException("Standalone child cleanup is unresolved");
            long deadline=SystemClock.elapsedRealtime()+3_000;
            while(selected.asBinder().isBinderAlive()){
                if(SystemClock.elapsedRealtime()>=deadline)throw new IOException("Standalone launcher has not exited");
                Thread.sleep(25);
            }
        }
        synchronized(LOCK){if(host==selected){host=null;stoppingHost=false;}}
    }
    private static String nonce(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
