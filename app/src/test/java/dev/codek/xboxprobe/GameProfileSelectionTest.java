package dev.codek.xboxprobe;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GameProfileSelectionTest {
    private static ProfileCodec.StoreDocument settings(Map<String,String> assignments,String...pins){
        return new ProfileCodec.StoreDocument(Arrays.asList(
                ProfileStore.builtinDefault(ProfileStore.PS4_ID),ProfileStore.builtinDefault(ProfileStore.PS5_ID),
                ProfileStore.builtinDefault(ProfileStore.XBOX_ID),ProfileStore.builtinDefault(ProfileStore.KISHI_ID)),
                assignments,ProfileStore.XBOX_ID,true,new LinkedHashSet<>(Arrays.asList(pins)),false);
    }
    private static GameCatalog.GameEntry game(String pkg,String label,boolean categorized){
        return new GameCatalog.GameEntry(pkg,label,null,categorized,false,true);
    }
    @Test public void explicitAssignmentOverridesDefaultInBothCardAndEditScope(){
        GameCatalog.GameEntry game=game("game.example","Example game",true);
        ProfileCodec.StoreDocument settings=settings(Collections.singletonMap(game.packageName,ProfileStore.PS5_ID));
        assertEquals(ProfileStore.PS5_ID,GameProfileSelection.resolveId(game,settings));
        assertEquals(Collections.singletonList("Example game"),GameProfileSelection.affectedGames(Collections.singletonList(game),settings,ProfileStore.PS5_ID));
        assertTrue(GameProfileSelection.affectedGames(Collections.singletonList(game),settings,ProfileStore.KISHI_ID).isEmpty());
    }
    @Test public void defaultAssignmentsAppearInEditScopeOnlyForLibraryEntries(){
        List<GameCatalog.GameEntry> games=Arrays.asList(game("game.example","Example game",false),game("game.other","Other game",true));
        ProfileCodec.StoreDocument settings=settings(Collections.emptyMap());
        assertTrue(GameProfileSelection.affectedGames(games,settings,ProfileStore.KISHI_ID).isEmpty());
        assertEquals(Collections.singletonList("Other game"),GameProfileSelection.affectedGames(games,settings,ProfileStore.XBOX_ID));
    }
    @Test public void nonGamesAreNotListedUntilPinnedOrAssigned(){
        GameCatalog.GameEntry entry=game("app.utility","Utility",false);
        ProfileCodec.StoreDocument settings=settings(Collections.emptyMap());
        assertFalse(GameProfileSelection.inLibrary(entry,settings));
        assertTrue(GameProfileSelection.affectedGames(Collections.singletonList(entry),settings,ProfileStore.XBOX_ID).isEmpty());
        ProfileCodec.StoreDocument pinned=settings(Collections.emptyMap(),entry.packageName);
        assertTrue(GameProfileSelection.inLibrary(entry,pinned));
        assertEquals(Collections.singletonList("Utility"),GameProfileSelection.affectedGames(Collections.singletonList(entry),pinned,ProfileStore.XBOX_ID));
    }
    @Test public void absentAssignedAppsKeepIdentityAndSameLabelsAreNotCollapsed(){
        Map<String,String> assignments=new LinkedHashMap<>();assignments.put("game.missing",ProfileStore.XBOX_ID);
        List<GameCatalog.GameEntry> games=Arrays.asList(game("game.one","Same name",true),game("game.two","Same name",true));
        List<String> affected=GameProfileSelection.affectedGames(games,settings(assignments),ProfileStore.XBOX_ID);
        assertEquals(3,affected.size());assertEquals("Same name",affected.get(0));assertEquals("Same name",affected.get(1));
        assertTrue(affected.get(2).startsWith("game.missing"));
    }
}
