package dev.codek.xboxprobe;

import java.util.List;
import java.util.Objects;

/** Pure launch admission state; Android owns transport cleanup and actual game launch. */
final class GameLaunchGate {
    enum Phase { IDLE, CLEANING, CONNECTING, LISTING, NEEDS_SOURCE, STARTING, WAITING_READY, READY, HANDED_OFF, CANCELLED, ERROR, BLOCKED }
    static final class Snapshot {
        final long token;
        final String packageName, profileLabel, sourcesJson, error;
        final boolean direct;
        final Phase phase;
        Snapshot(long token,String packageName,String profileLabel,boolean direct,Phase phase,String sourcesJson,String error) {
            this.token=token;this.packageName=packageName;this.profileLabel=profileLabel;this.direct=direct;
            this.phase=phase;this.sourcesJson=sourcesJson;this.error=error;
        }
    }
    private long nextToken;
    private boolean active;
    private String cleanupBlock="";
    private Snapshot state=new Snapshot(0,"","",false,Phase.IDLE,"","");

    synchronized long begin(String packageName,String label,boolean direct) {
        long token=++nextToken;
        active=cleanupBlock.isEmpty();
        state=new Snapshot(token,packageName,label,direct,active?Phase.CLEANING:Phase.BLOCKED,"",cleanupBlock);
        return token;
    }
    synchronized Snapshot snapshot() {return state;}
    synchronized boolean current(long token) {return active&&state.token==token;}
    synchronized boolean afterCleanup(long token) {
        if(!at(token,Phase.CLEANING))return false;
        move(state.direct?Phase.READY:Phase.CONNECTING,"","");return true;
    }
    synchronized boolean afterConnected(long token) {
        if(!at(token,Phase.CONNECTING))return false;move(Phase.LISTING,"","");return true;
    }
    /** Catalog owns capability/exclusion policy; this gate only chooses among its opaque tokens. */
    synchronized String sourcesListed(long token,String catalog,List<String> sourceTokens,boolean complete) {
        if(!at(token,Phase.LISTING))return null;
        Objects.requireNonNull(catalog,"catalog");Objects.requireNonNull(sourceTokens,"sourceTokens");
        for(String sourceToken:sourceTokens) {
            if(sourceToken==null||sourceToken.isEmpty())throw new IllegalArgumentException("Source catalog contains an absent token");
        }
        if(complete&&sourceTokens.size()==1) {
            move(Phase.STARTING,catalog,"");return sourceTokens.get(0);
        }
        move(Phase.NEEDS_SOURCE,catalog,"");return null;
    }
    synchronized boolean relist(long token) {
        if(!at(token,Phase.NEEDS_SOURCE))return false;move(Phase.LISTING,"","");return true;
    }
    synchronized boolean sourceSelected(long token) {
        if(!current(token)||(state.phase!=Phase.LISTING&&state.phase!=Phase.NEEDS_SOURCE))return false;
        move(Phase.STARTING,state.sourcesJson,"");return true;
    }
    synchronized boolean waitingForInput(long token) {
        if(!at(token,Phase.STARTING))return false;move(Phase.WAITING_READY,state.sourcesJson,"");return true;
    }
    synchronized boolean inputReady(long token,boolean opened,boolean kernelStarted,boolean consumerOpen,boolean bridgeRunning) {
        if(!at(token,Phase.WAITING_READY)||state.direct||!opened||!kernelStarted||!consumerOpen||!bridgeRunning)return false;
        move(Phase.READY,state.sourcesJson,"");return true;
    }
    synchronized boolean consumeReady(long token) {
        if(!at(token,Phase.READY))return false;move(Phase.HANDED_OFF,state.sourcesJson,"");return true;
    }
    synchronized boolean cancel(long token) {
        if(state.token!=token)return false;active=false;move(Phase.CANCELLED,state.sourcesJson,"");return true;
    }
    synchronized boolean fail(long token,String error) {
        if(!current(token))return false;active=false;move(Phase.ERROR,state.sourcesJson,error);return true;
    }
    synchronized void blockCleanup(String reason) {
        cleanupBlock=reason;active=false;move(Phase.BLOCKED,state.sourcesJson,reason);
    }
    synchronized void cleanupRecovered() {
        cleanupBlock="";
        if(state.phase==Phase.BLOCKED){active=false;move(Phase.CANCELLED,"","");}
    }
    private boolean at(long token,Phase phase) {return current(token)&&state.phase==phase;}
    private void move(Phase phase,String sources,String error) {
        state=new Snapshot(state.token,state.packageName,state.profileLabel,state.direct,phase,sources,error);
    }
}
