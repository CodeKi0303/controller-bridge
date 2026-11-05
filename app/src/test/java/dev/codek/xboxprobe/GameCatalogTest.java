package dev.codek.xboxprobe;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class GameCatalogTest {
    @Test public void misclassifiedGamesRemainAvailableInFullPickerAndCanBePinned() {
        GameCatalog.GameEntry category=entry("example.category","장르 게임",true,false,true);
        GameCatalog.GameEntry wrongCategory=entry("example.unclassified","분류 없는 게임",false,false,true);
        GameCatalog.GameEntry pinned=entry("example.pinned","직접 추가",false,true,true);
        GameCatalog.GameEntry namedApp=entry("example.named","Example app",false,false,true);
        List<GameCatalog.GameEntry> all=Arrays.asList(category,wrongCategory,pinned,namedApp);
        List<GameCatalog.GameEntry> shortList=GameCatalog.shortlist(all);
        assertEquals(Arrays.asList(category,pinned),shortList);
        assertEquals(Arrays.asList(wrongCategory),GameCatalog.filter(all,"분류 없는"));
        assertEquals(Arrays.asList(wrongCategory),GameCatalog.filter(all,"EXAMPLE.UNCLASSIFIED"));
        assertEquals(all,GameCatalog.filter(all,""));
        assertThrows(UnsupportedOperationException.class,()->shortList.clear());
    }

    @Test public void unknownLabelDoesNotChangeCategory() {
        GameCatalog.GameEntry fallback=entry("example.uncategorized","example.uncategorized",false,false,false);
        assertFalse(fallback.labelObserved);
        assertTrue(GameCatalog.shortlist(Arrays.asList(fallback)).isEmpty());
    }

    private static GameCatalog.GameEntry entry(String pkg,String label,boolean category,boolean pinned,boolean observed) {
        return new GameCatalog.GameEntry(pkg,label,null,category,pinned,observed);
    }
}
