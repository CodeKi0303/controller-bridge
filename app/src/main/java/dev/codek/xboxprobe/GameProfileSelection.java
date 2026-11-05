package dev.codek.xboxprobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owns the profile displayed by game cards and the scope shown before shared edits. */
public final class GameProfileSelection {
    private GameProfileSelection() {}

    public static String resolveId(GameCatalog.GameEntry game,ProfileCodec.StoreDocument settings) {
        Objects.requireNonNull(game,"game");Objects.requireNonNull(settings,"settings");
        String assigned=settings.gameAssignments.get(game.packageName);
        if(assigned!=null)return assigned;
        return settings.defaultProfileId;
    }

    public static boolean inLibrary(GameCatalog.GameEntry game,ProfileCodec.StoreDocument settings) {
        return game.categorizedGame||settings.pinnedGames.contains(game.packageName)
                ||settings.gameAssignments.containsKey(game.packageName);
    }

    /** Current library coverage, including configured packages missing from the current inventory. */
    public static List<String> affectedGames(List<GameCatalog.GameEntry> inventory,ProfileCodec.StoreDocument settings,String profileId) {
        Objects.requireNonNull(inventory,"inventory");Objects.requireNonNull(profileId,"profileId");
        Map<String,String> affected=new LinkedHashMap<>();
        for(GameCatalog.GameEntry game:inventory)if(inLibrary(game,settings)&&profileId.equals(resolveId(game,settings)))
            affected.put(game.packageName,game.label);
        for(Map.Entry<String,String> assignment:settings.gameAssignments.entrySet())if(profileId.equals(assignment.getValue()))
            affected.putIfAbsent(assignment.getKey(),assignment.getKey()+" · 설치 목록에서 확인되지 않음");
        return Collections.unmodifiableList(new ArrayList<>(affected.values()));
    }
}
