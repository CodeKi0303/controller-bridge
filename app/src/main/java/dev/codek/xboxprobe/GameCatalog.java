package dev.codek.xboxprobe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** PackageManager launcher inventory. Run load() on the caller's background executor. */
public final class GameCatalog {
    private GameCatalog() {}

    public static final class GameEntry {
        public final String packageName;
        public final String label;
        public final Drawable icon;
        public final boolean categorizedGame;
        public final boolean pinned;
        public final boolean labelObserved;

        public GameEntry(String packageName, String label, Drawable icon, boolean categorizedGame,
                         boolean pinned, boolean labelObserved) {
            ProfileCodec.validatePackageName(packageName);
            this.packageName=packageName;
            this.label=Objects.requireNonNull(label,"label");
            this.icon=icon;
            this.categorizedGame=categorizedGame;
            this.pinned=pinned;
            this.labelObserved=labelObserved;
        }
    }

    public static final class Catalog {
        public final List<GameEntry> allLaunchable;
        public final List<GameEntry> shortlist;
        public final List<String> errors;
        private Catalog(List<GameEntry> all,List<String> errors) {
            this.allLaunchable=Collections.unmodifiableList(new ArrayList<>(all));
            this.shortlist=shortlist(all);
            this.errors=Collections.unmodifiableList(new ArrayList<>(errors));
        }
    }

    /** Requires a manifest queries intent for MAIN/LAUNCHER, never QUERY_ALL_PACKAGES. */
    @SuppressWarnings("deprecation")
    public static Catalog load(Context context, Set<String> pinnedPackages) {
        Objects.requireNonNull(context,"context");
        Set<String> pins=new LinkedHashSet<>(Objects.requireNonNull(pinnedPackages,"pinnedPackages"));
        PackageManager manager=context.getPackageManager();
        Intent launcher=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved=manager.queryIntentActivities(launcher,0);
        Map<String,GameEntry> entries=new LinkedHashMap<>();
        List<String> errors=new ArrayList<>();
        for(ResolveInfo info:resolved) {
            ActivityInfo activity=info.activityInfo;
            if(activity==null||activity.applicationInfo==null||!activity.enabled||!activity.exported||!activity.applicationInfo.enabled)continue;
            ApplicationInfo application=activity.applicationInfo;
            String packageName=application.packageName;
            if(packageName==null||packageName.equals(context.getPackageName())||entries.containsKey(packageName))continue;
            try {ProfileCodec.validatePackageName(packageName);}catch(IllegalArgumentException error){errors.add("Invalid package identity in launcher result");continue;}
            String label=packageName;boolean observed=false;Drawable icon=null;
            try {
                CharSequence loaded=application.loadLabel(manager);
                if(loaded!=null&&!loaded.toString().trim().isEmpty()){label=loaded.toString();observed=true;}
            }catch(RuntimeException error){errors.add(packageName+": label unavailable ("+error.getClass().getSimpleName()+")");}
            try {icon=application.loadIcon(manager);}catch(RuntimeException error){errors.add(packageName+": icon unavailable ("+error.getClass().getSimpleName()+")");}
            entries.put(packageName,new GameEntry(packageName,label,icon,application.category==ApplicationInfo.CATEGORY_GAME,pins.contains(packageName),observed));
        }
        List<GameEntry> all=new ArrayList<>(entries.values());
        Collator labels=Collator.getInstance();labels.setStrength(Collator.SECONDARY);
        all.sort((a,b)->{int comparison=labels.compare(a.label,b.label);return comparison!=0?comparison:a.packageName.compareTo(b.packageName);});
        return new Catalog(all,errors);
    }

    /** Include categorized games and manually pinned apps. */
    public static List<GameEntry> shortlist(List<GameEntry> entries) {
        List<GameEntry> result=new ArrayList<>();
        for(GameEntry entry:entries)if(entry.categorizedGame||entry.pinned)result.add(entry);
        return Collections.unmodifiableList(result);
    }

    /** Search the full launchable list so incorrectly categorized games remain selectable. */
    public static List<GameEntry> filter(List<GameEntry> entries,String query) {
        String needle=query==null?"":query.trim().toLowerCase(Locale.ROOT);
        List<GameEntry> result=new ArrayList<>();
        for(GameEntry entry:entries)if(needle.isEmpty()||entry.label.toLowerCase(Locale.ROOT).contains(needle)
                ||entry.packageName.toLowerCase(Locale.ROOT).contains(needle))result.add(entry);
        return Collections.unmodifiableList(result);
    }

    /** Resolve a fresh platform launch intent; no arbitrary component or invented package fallback. */
    public static Intent launchIntent(Context context,String packageName) {
        ProfileCodec.validatePackageName(packageName);
        Intent launch=context.getPackageManager().getLaunchIntentForPackage(packageName);
        return launch==null?null:new Intent(launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
