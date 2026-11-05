package dev.codek.xboxprobe;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Fixed same-APK shell bootstrap; never a general command or file service. */
public final class StandaloneHelperMain {
    private static final String TAG="BridgeBootstrap";
    private enum StartupStage {
        ARGUMENTS, DETACH, MAIN_LOOPER, ACTIVITY_THREAD, SYSTEM_CONTEXT,
        PACKAGE_CONTEXT, PACKAGE_IDENTITY, APK_PATH, ENDPOINT, HANDOFF,
        OWNER_LINK, READY
    }
    private StandaloneHelperMain(){}
    public static void main(String[] args) {
        ProbeUserService probe=null;Host launcher=null;
        StartupStage stage=StartupStage.ARGUMENTS;
        try{
            logStage(stage);
            BootstrapAuthorization.requireUid(android.os.Process.myUid(),2000);
            if(args.length!=4)throw new IllegalArgumentException("Invalid helper startup arguments");
            String role=args[0],signer=args[2],nonce=args[3];int uid=Integer.parseInt(args[1]);
            BootstrapProtocol.arguments(role,uid,signer,nonce);
            // A background shell command must survive the short ADB bootstrap
            // channel. It owns no inherited terminal/stream descriptors.
            logStage(stage=StartupStage.DETACH);
            try{Os.setsid();}catch(ErrnoException error){if(error.errno!=OsConstants.EPERM)throw error;}
            logStage(stage=StartupStage.MAIN_LOOPER);
            if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
            logStage(stage=StartupStage.ACTIVITY_THREAD);
            Class<?> activityThread=Class.forName("android.app.ActivityThread");
            Object thread=activityThread.getMethod("systemMain").invoke(null);
            logStage(stage=StartupStage.SYSTEM_CONTEXT);
            Context system=(Context)activityThread.getMethod("getSystemContext").invoke(thread);
            logStage(stage=StartupStage.PACKAGE_CONTEXT);
            Method packageAsUser=Context.class.getMethod("createPackageContextAsUser",String.class,int.class,UserHandle.class);
            Context application=(Context)packageAsUser.invoke(system,BuildConfig.APPLICATION_ID,Context.CONTEXT_IGNORE_SECURITY,UserHandle.getUserHandleForUid(uid));
            logStage(stage=StartupStage.PACKAGE_IDENTITY);
            InstalledBridgeIdentity identity=InstalledBridgeIdentity.read(application);identity.require(uid,signer);
            logStage(stage=StartupStage.APK_PATH);
            String classPath=System.getenv("CLASSPATH");
            if(classPath==null||!new File(classPath).getCanonicalPath().equals(identity.apk))
                throw new SecurityException("Helper must load the currently installed APK");
            logStage(stage=StartupStage.ENDPOINT);
            IBinder endpoint;
            if(role.equals("host")){launcher=new Host(application,identity);endpoint=launcher;}
            else{probe=new ProbeUserService(application,uid);endpoint=probe;}
            Bundle request=new Bundle();request.putString("nonce",nonce);request.putString("role",role);request.putString("signer",signer);
            request.putInt("uid",uid);request.putBinder("binder",endpoint);
            logStage(stage=StartupStage.HANDOFF);
            Bundle reply=ShellProviderHandoff.send(uid/100000,request);
            if(reply==null||!reply.getBoolean("accepted"))throw new SecurityException("Bootstrap handoff was not accepted");
            logStage(stage=StartupStage.OWNER_LINK);
            IBinder owner=reply.getBinder("owner");if(owner==null)throw new SecurityException("App lifetime owner is missing");
            if(launcher!=null){
                Host owned=launcher;owner.linkToDeath(owned::ownerDied,0);
            }else{ProbeUserService owned=probe;owner.linkToDeath(owned::destroy,0);}
            logStage(stage=StartupStage.READY);
            Looper.loop();
        }catch(Throwable error){
            // Logcat remains available when the bootstrap shell redirects streams.
            // Only fixed stages and a class name are allowed: never Throwable
            // messages/stack traces, arguments, capabilities, paths, or keys.
            Log.e(TAG,"helper failed stage="+stage.name()+" type="+FailureTypes.className(FailureTypes.reflectionCause(error)));
            if(probe!=null)probe.destroy();
            if(launcher!=null)launcher.ownerDied();
            System.exit(1);
        }
    }

    private static void logStage(StartupStage stage){Log.i(TAG,"helper stage="+stage.name());}

    private static final class Host extends IStandaloneHost.Stub {
        private final Context context;
        private final InstalledBridgeIdentity identity;
        private final Map<String,Process> children=new LinkedHashMap<>();
        private boolean closing;
        Host(Context context,InstalledBridgeIdentity identity){this.context=context;this.identity=identity;}
        @Override public boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
            BootstrapAuthorization.requireUid(Binder.getCallingUid(),identity.uid);
            return super.onTransact(code,data,reply,flags);
        }
        @Override public synchronized void startSession(String nonce)throws RemoteException {
            BootstrapProtocol.validateNonce(nonce);
            if(closing)throw new IllegalStateException("Standalone launcher is closing");
            try{
                InstalledBridgeIdentity now=InstalledBridgeIdentity.read(context);now.require(identity.uid,identity.signer);
                if(!now.apk.equals(identity.apk))throw new SecurityException("APK changed; restart the standalone launcher");
                children.entrySet().removeIf(entry->!entry.getValue().isAlive());
                if(!children.isEmpty())throw new IllegalStateException("Previous owned session has not exited");
                ProcessBuilder child=new ProcessBuilder(BootstrapProtocol.arguments("session",identity.uid,identity.signer,nonce));
                child.environment().put("CLASSPATH",identity.apk);
                child.redirectInput(new File("/dev/null"));child.redirectOutput(new File("/dev/null"));child.redirectError(new File("/dev/null"));
                children.put(nonce,child.start());
            }catch(Exception error){throw new RemoteException("Cannot start the fixed input helper: "+error.getClass().getSimpleName());}
        }
        @Override public synchronized boolean finishSession(String nonce) {
            BootstrapProtocol.validateNonce(nonce);
            Process process=children.get(nonce);if(process==null)return true;
            if(!terminate(process))return false;
            children.remove(nonce);return true;
        }
        @Override public synchronized boolean shutdown() {
            closing=true;
            for(Process process:children.values())if(!terminate(process))return false;
            children.clear();
            // Return acknowledgement before the exact launcher's Binder dies.
            new android.os.Handler(Looper.getMainLooper()).postDelayed(()->System.exit(0),100);
            return true;
        }
        void ownerDied(){
            synchronized(this){closing=true;for(Process process:children.values())terminate(process);children.clear();}
            System.exit(0);
        }
        private static boolean terminate(Process process) {
            if(!process.isAlive())return true;
            process.destroy();
            try{if(!process.waitFor(500,TimeUnit.MILLISECONDS)){process.destroyForcibly();return process.waitFor(2,TimeUnit.SECONDS);}return true;}
            catch(InterruptedException error){Thread.currentThread().interrupt();return !process.isAlive();}
        }
    }
}
