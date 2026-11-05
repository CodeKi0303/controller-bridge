package dev.codek.xboxprobe;

import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import java.io.IOException;

/** Shell-only provider acquisition: app_process has no AMS application record. */
final class ShellProviderHandoff {
    private ShellProviderHandoff(){}
    static Bundle send(int userId,Bundle request)throws Exception {
        BootstrapAuthorization.requireUid(android.os.Process.myUid(),2000);
        String authority=BuildConfig.APPLICATION_ID+".standalone";
        Class<?> managerInterface=Class.forName("android.app.IActivityManager");
        Object manager=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Object holder=managerInterface.getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class)
                .invoke(manager,authority,userId,null,"controller-bridge-bootstrap");
        if(holder==null)throw new IOException("Standalone bootstrap provider is unavailable");
        Exception failure=null;
        try {
            Object provider=holder.getClass().getField("provider").get(holder);
            if(provider==null)throw new IOException("Standalone provider endpoint is missing");
            Class<?> providerInterface=Class.forName("android.content.IContentProvider");
            if(Build.VERSION.SDK_INT>=31){
                Class<?> attribution=Class.forName("android.content.AttributionSource");
                Class<?> builderType=Class.forName("android.content.AttributionSource$Builder");
                Object builder=builderType.getConstructor(int.class).newInstance(2000);
                builderType.getMethod("setPackageName",String.class).invoke(builder,"com.android.shell");
                Object source=builderType.getMethod("build").invoke(builder);
                return (Bundle)providerInterface.getMethod("call",attribution,String.class,String.class,String.class,Bundle.class)
                        .invoke(provider,source,authority,"handoff",null,request);
            }
            return (Bundle)providerInterface.getMethod("call",String.class,String.class,String.class,String.class,String.class,Bundle.class)
                    .invoke(provider,"com.android.shell",null,authority,"handoff",null,request);
        }catch(Exception error){failure=error;throw error;}
        finally{
            try{
                // Null external tokens use a reference count; always release the
                // exact authority and user acquired above, including call failures.
                managerInterface.getMethod("removeContentProviderExternalAsUser",String.class,IBinder.class,int.class)
                        .invoke(manager,authority,null,userId);
            }catch(Exception cleanup){if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;}
        }
    }
}
