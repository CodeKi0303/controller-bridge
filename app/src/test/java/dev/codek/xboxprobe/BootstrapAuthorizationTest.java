package dev.codek.xboxprobe;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class BootstrapAuthorizationTest {
    private static final String NONCE="ab".repeat(32),OTHER="cd".repeat(32),SIGNER="ef".repeat(32);

    @Test public void wrongCallerCannotConsumeCapabilityAndAcceptedHandoffIsOneUse(){
        BootstrapAuthorization<Object> gate=new BootstrapAuthorization<>();Object owned=new Object();
        gate.add(NONCE,owned,100,500);
        assertThrows(SecurityException.class,()->gate.consume(10123,NONCE,200));
        assertSame(owned,gate.consume(2000,NONCE,200));
        assertNull(gate.consume(2000,NONCE,201));
    }
    @Test public void expiryAndCancellationRejectLateHelperWithoutReplacingNewOwner(){
        BootstrapAuthorization<Object> gate=new BootstrapAuthorization<>();Object newer=new Object();
        gate.add(NONCE,new Object(),100,500);
        assertNull(gate.consume(2000,NONCE,600));
        gate.add(NONCE,new Object(),700,500);gate.remove(NONCE);
        gate.add(OTHER,newer,700,500);
        assertNull(gate.consume(2000,NONCE,701));
        assertSame(newer,gate.consume(2000,OTHER,701));
    }
    @Test public void concurrentHandoffsCannotBothClaimTheSameSession()throws Exception{
        BootstrapAuthorization<Object> gate=new BootstrapAuthorization<>();Object owned=new Object();
        gate.add(NONCE,owned,0,500);
        ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch start=new CountDownLatch(1);
        try{
            List<Future<Object>> attempts=new ArrayList<>();
            for(int i=0;i<8;i++)attempts.add(pool.submit(()->{assertTrue(start.await(2,TimeUnit.SECONDS));return gate.consume(2000,NONCE,100);}));
            start.countDown();int accepted=0;
            for(Future<Object> result:attempts){Object value=result.get(3,TimeUnit.SECONDS);if(value!=null){assertSame(owned,value);accepted++;}}
            assertEquals(1,accepted);
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test public void directBinderAuthorityIsExactUidNotRootShellOrAnotherApp(){
        BootstrapAuthorization.requireUid(10123,10123);
        for(int uid:new int[]{0,2000,10124,-1})assertThrows(SecurityException.class,()->BootstrapAuthorization.requireUid(uid,10123));
        assertThrows(SecurityException.class,()->BootstrapAuthorization.requireUid(-1,-1));
    }
    @Test public void malformedIdentityCannotBecomeAChildCommand(){
        assertThrows(IllegalArgumentException.class,()->BootstrapProtocol.arguments("exec",10123,SIGNER,NONCE));
        assertThrows(IllegalArgumentException.class,()->BootstrapProtocol.arguments("host",2000,SIGNER,NONCE));
        assertThrows(IllegalArgumentException.class,()->BootstrapProtocol.arguments("host",10123,"$(id)",NONCE));
        assertThrows(IllegalArgumentException.class,()->BootstrapProtocol.arguments("session",10123,SIGNER,"x;reboot"));
        assertThrows(IllegalArgumentException.class,()->BootstrapProtocol.command("/data/app/a\nb.apk",10123,SIGNER,NONCE));
        assertThrows(IllegalArgumentException.class,()->BootstrapProtocol.command("relative.apk",10123,SIGNER,NONCE));
    }
    @Test public void startupCommandKeepsInstalledPathQuotedAndUsesOnlyFixedEntrypoint(){
        String command=BootstrapProtocol.command("/data/app/one ' quoted/base.apk",10123,SIGNER,NONCE);
        assertTrue(command.startsWith("CLASSPATH='/data/app/one '\\'' quoted/base.apk' "));
        assertTrue(command.contains("'/system/bin/app_process' '/' 'dev.codek.xboxprobe.StandaloneHelperMain' 'host' '10123'"));
        assertTrue(command.endsWith(" </dev/null >/dev/null 2>&1 &"));
    }
}
