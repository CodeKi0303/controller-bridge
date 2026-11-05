package dev.codek.xboxprobe;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class AdaptiveNavigationGeometryTest {
    @Test public void compactWindowKeepsBottomDestinationsOutsideContentViewport() {
        AdaptiveNavigationGeometry.Plan plan=plan(412,900,1,true);
        assertFalse(plan.rail);assertEquals(64,plan.appBar.height());assertEquals(80,plan.navigation.height());
        assertEquals(64,plan.content.top);assertEquals(820,plan.content.bottom);
        assertTrue(plan.navigation.width()/3>=48);
    }
    @Test public void wideWindowUsesRailAndLeavesContentItsOwnViewport() {
        AdaptiveNavigationGeometry.Plan plan=plan(1280,900,1,true);
        assertTrue(plan.rail);assertEquals(96,plan.navigation.width());assertEquals(96,plan.content.left);
        assertEquals(900,plan.content.bottom);assertEquals(240,plan.navigation.height());
    }
    @Test public void auxiliaryScreenHidesDestinationsWithoutLosingAppBar() {
        AdaptiveNavigationGeometry.Plan plan=plan(412,900,1,false);
        assertNull(plan.navigation);assertEquals(64,plan.content.top);assertEquals(900,plan.content.bottom);
    }
    @Test public void largeFontAndShortWindowUseTallBottomItemsInsteadOfClippedRail() {
        AdaptiveNavigationGeometry.Plan plan=plan(800,360,2,true);
        assertFalse(plan.rail);assertEquals(116,plan.navigation.height());assertEquals(96,plan.appBar.height());
        assertTrue(plan.content.height()>0);
    }
    @Test public void verticalHingeDoesNotCrossTopActionsOrRail() {
        AdaptiveScreenGeometry.Feature hinge=fold(620,0,660,900,true);
        AdaptiveNavigationGeometry.Plan plan=plan(1280,900,1,true,hinge);
        assertTrue(plan.rail);excludes(plan.appBar,hinge.bounds);excludes(plan.navigation,hinge.bounds);
        assertEquals(1280,plan.content.right); // Existing content layout can still use both physical panes.
    }
    @Test public void tabletopRailChoosesOneUnobstructedVerticalSegment() {
        AdaptiveScreenGeometry.Feature hinge=fold(0,250,800,270,false);
        AdaptiveNavigationGeometry.Plan plan=plan(800,900,1,true,hinge);
        assertTrue(plan.rail);excludes(plan.navigation,hinge.bounds);assertTrue(plan.navigation.top>270);
    }
    @Test public void compactSeparatingCreaseKeepsBottomItemsOnSafeSide() {
        AdaptiveScreenGeometry.Feature crease=fold(280,0,280,900,true);
        AdaptiveNavigationGeometry.Plan plan=plan(560,900,1,true,crease);
        assertFalse(plan.rail);assertTrue(plan.navigation.right<280||plan.navigation.left>280);
        assertTrue(plan.navigation.width()/3>=48);
    }
    @Test public void foldAcrossTopBarMovesChromeIntoLargerSafeRegion() {
        AdaptiveScreenGeometry.Feature hinge=fold(0,25,412,45,false);
        AdaptiveNavigationGeometry.Plan plan=plan(412,900,1,true,hinge);
        assertTrue(plan.appBar.top>45);excludes(plan.appBar,hinge.bounds);excludes(plan.navigation,hinge.bounds);
    }
    private static AdaptiveNavigationGeometry.Plan plan(int width,int height,float fontScale,boolean navigation,AdaptiveScreenGeometry.Feature... features) {
        return AdaptiveNavigationGeometry.arrange(new AdaptiveScreenGeometry.Bounds(0,0,width,height),1,fontScale,Arrays.asList(features),navigation);
    }
    private static AdaptiveScreenGeometry.Feature fold(int left,int top,int right,int bottom,boolean vertical) {
        return new AdaptiveScreenGeometry.Feature(new AdaptiveScreenGeometry.Bounds(left,top,right,bottom),vertical,true,right>left&&bottom>top);
    }
    private static void excludes(AdaptiveScreenGeometry.Bounds pane,AdaptiveScreenGeometry.Bounds blocked) {
        assertFalse(pane.left<blocked.right&&pane.right>blocked.left&&pane.top<blocked.bottom&&pane.bottom>blocked.top);
    }
}
