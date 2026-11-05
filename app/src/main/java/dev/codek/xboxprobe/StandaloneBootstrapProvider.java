package dev.codek.xboxprobe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** The sole exported handoff boundary; it does not expose pairing secrets or commands. */
public final class StandaloneBootstrapProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras) {
        BootstrapAuthorization.requireUid(Binder.getCallingUid(),2000);
        if(!"handoff".equals(method)||extras==null||extras.size()>6)throw new SecurityException("Unsupported bootstrap operation");
        return StandaloneBackend.acceptHandoff(getContext(),Binder.getCallingUid(),extras);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){throw new SecurityException("No query API");}
    @Override public String getType(Uri uri){return null;}
    @Override public Uri insert(Uri uri,ContentValues values){throw new SecurityException("No insert API");}
    @Override public int delete(Uri uri,String selection,String[] args){throw new SecurityException("No delete API");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new SecurityException("No update API");}
}
