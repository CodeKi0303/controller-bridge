package dev.codek.xboxprobe;

import android.annotation.SuppressLint;
import org.conscrypt.Conscrypt;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/** One cancellable same-device ADB operation. Only TLS is accepted, and startup
 * accepts the backend's authenticated bootstrap request rather than arbitrary UI commands. */
final class WirelessAdbClient implements AutoCloseable {
    static final int IO_TIMEOUT_MS = 8000;
    private static final String HOST = "127.0.0.1";
    private static final byte[] EMPTY = new byte[0];
    private final WirelessAdbIdentity identity;
    private volatile Socket ownedSocket;
    private volatile boolean closed;
    private volatile WirelessAdbFailure.Stage stage=WirelessAdbFailure.Stage.PREPARATION;
    WirelessAdbClient(WirelessAdbIdentity identity) { this.identity = identity; }
    WirelessAdbFailure.Stage currentStage() { return stage; }

    static int port(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,4}")) throw new IllegalArgumentException("포트 번호를 확인해 주세요.");
        int port = Integer.parseInt(value);
        if (port > 65535) throw new IllegalArgumentException("포트 번호는 1–65535여야 합니다.");
        return port;
    }
    static byte[] pairingCode(String value) {
        if (value == null || !value.matches("[0-9]{6}")) throw new IllegalArgumentException("6자리 페어링 코드를 입력해 주세요.");
        return value.getBytes(StandardCharsets.US_ASCII);
    }
    void pair(int port, byte[] code) throws Exception {
        stage=WirelessAdbFailure.Stage.PREPARATION;
        try {
            if (code==null || code.length != 6) throw new IllegalArgumentException("Invalid pairing code length");
            stage=WirelessAdbFailure.Stage.PAIRING_CONNECT;
            Socket raw=connect(port);
            stage=WirelessAdbFailure.Stage.PAIRING_TLS;
            SSLSocket tls = tls(raw, port);
            stage=WirelessAdbFailure.Stage.PAIRING_EXCHANGE;
            byte[] exported = Conscrypt.exportKeyingMaterial(tls, "adb-label\0", null, 64);
            byte[] password = new byte[code.length + exported.length];
            System.arraycopy(code,0,password,0,code.length); System.arraycopy(exported,0,password,code.length,exported.length);
            try (AdbPairingCrypto crypto = new AdbPairingCrypto(password, true)) {
                Arrays.fill(password,(byte)0); Arrays.fill(exported,(byte)0);
                DataInputStream in = new DataInputStream(tls.getInputStream());
                DataOutputStream out = new DataOutputStream(tls.getOutputStream());
                writePair(out,0,crypto.message());
                crypto.finish(readPair(in,0,32));
                byte[] peerInfo = AdbPublicKey.peerInfo((RSAPublicKey) identity.certificate.getPublicKey());
                writePair(out,1,crypto.encrypt(peerInfo));
                byte[] peer = crypto.decrypt(readPair(in,1,8208));
                if (peer.length!=8192 || peer[0]!=1) throw new IOException("Invalid ADB pairing peer identity");
                // The peer GUID is protocol metadata, never persisted or logged.
                Arrays.fill(peer,(byte)0); Arrays.fill(peerInfo,(byte)0);
            } finally { Arrays.fill(password,(byte)0); Arrays.fill(exported,(byte)0); }
        } catch (Exception failure) {
            throw WirelessAdbFailure.at(stage,failure);
        } finally { if(code!=null)Arrays.fill(code,(byte)0); close(); }
    }
    void start(int port, StandaloneBackend.BootstrapRequest request) throws Exception {
        stage=WirelessAdbFailure.Stage.PREPARATION;
        try {
            if (request == null) throw new IllegalArgumentException("Missing authenticated bootstrap request");
            stage=WirelessAdbFailure.Stage.START_CONNECT;
            Socket raw = connect(port);
            stage=WirelessAdbFailure.Stage.START_TLS_NEGOTIATION;
            AdbWire.write(raw.getOutputStream(), AdbWire.CNXN, AdbWire.VERSION, AdbWire.MAX_PAYLOAD,
                    "host::features=shell_v2;\0".getBytes(StandardCharsets.UTF_8));
            AdbWire.Packet initial = AdbWire.read(raw.getInputStream());
            AdbWire.requireTlsUpgrade(initial);
            AdbWire.write(raw.getOutputStream(),AdbWire.STLS,AdbWire.TLS_VERSION,0,EMPTY);
            stage=WirelessAdbFailure.Stage.START_AUTHENTICATION;
            SSLSocket tls = tls(raw,port);
            AdbWire.Packet ready = AdbWire.read(tls.getInputStream());
            if (ready.command!=AdbWire.CNXN || ready.arg0<AdbWire.VERSION || ready.arg1<=0)
                throw new IOException("무선 디버깅 인증에 실패했습니다. 다시 페어링해 주세요.");
            stage=WirelessAdbFailure.Stage.START_SHELL_OPEN;
            byte[] command = AdbShellV2.command(ready.payload,request.command);
            if (command.length>Math.min(ready.arg1,AdbWire.MAX_PAYLOAD)) throw new IOException("Bootstrap command exceeds ADB capacity");
            runBootstrap(tls.getInputStream(),tls.getOutputStream(),command);
        } catch (Exception failure) {
            throw WirelessAdbFailure.at(stage,failure);
        } finally { close(); }
    }
    void runBootstrap(InputStream in, OutputStream out, byte[] command) throws IOException {
        stage=WirelessAdbFailure.Stage.START_SHELL_OPEN;
        try {
            AdbWire.write(out,AdbWire.OPEN,1,0,command);
            int remote=0;
            AdbShellV2 response=new AdbShellV2();
            for (int messages=0; messages<256; messages++) {
                checkOpen();
                AdbWire.Packet packet=AdbWire.read(in);
                if (packet.arg1!=1) throw new IOException("ADB bootstrap stream identity mismatch");
                if (packet.command==AdbWire.OKAY && remote==0 && packet.arg0!=0) {
                    remote=packet.arg0;stage=WirelessAdbFailure.Stage.START_BOOTSTRAP;continue;
                }
                if (packet.command==AdbWire.CLSE && remote==0)
                    throw new IOException("ADB bootstrap shell open rejected");
                if (packet.command==AdbWire.CLSE && packet.arg0==remote) {
                    AdbWire.write(out,AdbWire.CLSE,1,remote,EMPTY);
                    response.requireSuccess();
                    return; // Actual success is the separately authenticated Binder handoff.
                }
                if (packet.command!=AdbWire.WRTE || remote==0 || packet.arg0!=remote)
                    throw new IOException("Unexpected ADB bootstrap stream state");
                response.accept(packet.payload);
                AdbWire.write(out,AdbWire.OKAY,1,remote,EMPTY);
            }
            throw new IOException("ADB bootstrap exceeded its message limit");
        } catch (IOException failure) {
            throw WirelessAdbFailure.at(stage,failure);
        }
    }
    private Socket connect(int port) throws IOException {
        if (port<1 || port>65535) throw new IOException("Invalid local ADB port");
        Socket socket=new Socket();
        synchronized(this) { checkOpen(); ownedSocket=socket; }
        socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),port),IO_TIMEOUT_MS);
        socket.setSoTimeout(IO_TIMEOUT_MS);socket.setTcpNoDelay(true);checkOpen();return socket;
    }
    private SSLSocket tls(Socket raw,int port) throws Exception {
        SSLContext context=SSLContext.getInstance("TLSv1.3",Conscrypt.newProvider());
        context.init(new KeyManager[]{keyManager()},new X509TrustManager[]{localAdbTrust()},new SecureRandom());
        SSLSocket socket=(SSLSocket)context.getSocketFactory().createSocket(raw,HOST,port,true);
        socket.setEnabledProtocols(new String[]{"TLSv1.3"});socket.setSoTimeout(IO_TIMEOUT_MS);
        synchronized(this) { if(closed){socket.close();throw new IOException("Operation cancelled");}ownedSocket=socket; }
        socket.startHandshake();checkOpen();return socket;
    }
    private X509ExtendedKeyManager keyManager() {
        return new X509ExtendedKeyManager() {
            @Override public String[] getClientAliases(String type,Principal[] issuers){return "RSA".equals(type)?new String[]{"adb"}:null;}
            @Override public String chooseClientAlias(String[] types,Principal[] issuers,Socket socket){for(String type:types)if("RSA".equals(type))return "adb";return null;}
            @Override public String[] getServerAliases(String type,Principal[] issuers){return null;}
            @Override public String chooseServerAlias(String type,Principal[] issuers,Socket socket){return null;}
            @Override public X509Certificate[] getCertificateChain(String alias){return "adb".equals(alias)?new X509Certificate[]{identity.certificate}:null;}
            @Override public PrivateKey getPrivateKey(String alias){return "adb".equals(alias)?identity.privateKey:null;}
        };
    }
    @SuppressLint("TrustAllX509TrustManager")
    private static X509TrustManager localAdbTrust() {
        // Android adbd uses self-signed certificates. This client cannot accept a
        // remote endpoint: transport is fixed to device loopback. Pairing proves
        // the 6-digit secret using PAKE+TLS exporter; bootstrap completion must
        // arrive independently from shell UID 2000 via the authenticated handoff.
        return new X509TrustManager(){
            @Override public void checkClientTrusted(X509Certificate[] c,String a){}
            @Override public void checkServerTrusted(X509Certificate[] c,String a){}
            @Override public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
        };
    }
    private static void writePair(DataOutputStream out,int type,byte[] bytes)throws IOException {
        out.writeByte(1);out.writeByte(type);out.writeInt(bytes.length);out.write(bytes);out.flush();
    }
    private static byte[] readPair(DataInputStream in,int type,int exactSize)throws IOException {
        int version=in.readUnsignedByte(),actualType=in.readUnsignedByte(),size=in.readInt();
        if(version!=1||actualType!=type||size!=exactSize)throw new IOException("Invalid pairing protocol message");
        byte[] payload=new byte[size];in.readFully(payload);return payload;
    }
    private void checkOpen()throws IOException {if(closed||Thread.currentThread().isInterrupted())throw new IOException("Operation cancelled");}
    @Override public void close(){
        Socket socket;
        synchronized(this){closed=true;socket=ownedSocket;ownedSocket=null;}
        if(socket!=null)try{socket.close();}catch(IOException ignored){}
    }
}
