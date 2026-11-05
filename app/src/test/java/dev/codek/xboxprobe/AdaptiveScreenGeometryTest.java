package dev.codek.xboxprobe;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public final class AdaptiveScreenGeometryTest {
    @Test public void portraitPhoneAndNarrowSplitScreenUseOneViewport() {
        for(int width:new int[]{320,412,540,700,719}) {
            AdaptiveScreenGeometry.Plan plan=arrange(width,900,1,1);
            assertEquals(AdaptiveScreenGeometry.Mode.SINGLE,plan.mode);assertEquals(width,plan.primary.width());
        }
    }

    @Test public void landscapeAndExpandedWindowsUseTwoIndependentViewports() {
        for(int width:new int[]{720,800,1024,1280}) {
            AdaptiveScreenGeometry.Plan plan=arrange(width,600,1,1);
            assertEquals(AdaptiveScreenGeometry.Mode.COLUMNS,plan.mode);
            assertEquals(24,plan.secondary.left-plan.primary.right);
            assertTrue(Math.abs(plan.primary.width()-plan.secondary.width())<=1);
            assertEquals(600,plan.primary.height());assertEquals(600,plan.secondary.height());
        }
    }

    @Test public void widthUsesWindowPixelsAndDensityRatherThanPhysicalDisplaySize() {
        assertEquals(AdaptiveScreenGeometry.Mode.SINGLE,arrange(1400,1800,2,1).mode);
        assertEquals(AdaptiveScreenGeometry.Mode.COLUMNS,arrange(1440,1200,2,1).mode);
    }

    @Test public void expandedContentIsCenteredAndCappedAt1280Dp() {
        AdaptiveScreenGeometry.Plan plan=arrange(2000,1000,1,1);
        assertEquals(360,plan.primary.left);assertEquals(1640,plan.secondary.right);
        assertEquals(1280,plan.secondary.right-plan.primary.left);
    }

    @Test public void ordinaryWideDialogUsesWholeCappedContentRegionRatherThanOneUiColumn() {
        AdaptiveScreenGeometry.Plan tablet=arrange(1280,900,1,1);
        assertEquals(AdaptiveScreenGeometry.Mode.COLUMNS,tablet.mode);
        assertEquals(628,tablet.primary.width());assertEquals(1280,tablet.dialogBounds.width());
        assertEquals(0,tablet.dialogBounds.left);assertEquals(900,tablet.dialogBounds.bottom);
        AdaptiveScreenGeometry.Plan larger=arrange(2000,900,1,1);
        assertEquals(360,larger.dialogBounds.left);assertEquals(1640,larger.dialogBounds.right);
    }

    @Test public void dialogRegionNeverUnionsAcrossPhysicalHingeEvenWithUiColumnsWithinSafeSide() {
        AdaptiveScreenGeometry.Plan vertical=arrange(1280,900,1,1,feature(620,0,660,900,true,true,true));
        assertEquals(0,vertical.dialogBounds.left);assertEquals(608,vertical.dialogBounds.right);
        AdaptiveScreenGeometry.Plan tabletop=arrange(700,900,1,1,feature(0,430,700,470,false,true,true));
        assertEquals(0,tabletop.dialogBounds.top);assertEquals(418,tabletop.dialogBounds.bottom);
        // The large safe side can itself have two UI columns without restricting its dialog to half.
        AdaptiveScreenGeometry.Plan edge=arrange(1280,900,1,1,feature(100,0,120,900,true,true,true));
        assertEquals(AdaptiveScreenGeometry.Mode.COLUMNS,edge.mode);
        assertEquals(132,edge.dialogBounds.left);assertEquals(1280,edge.dialogBounds.right);
    }

    @Test public void preferredPrimaryWidthUsesRemainingTabletSpaceButRespectsFontAndPhysicalHinges() {
        AdaptiveScreenGeometry.Bounds available=new AdaptiveScreenGeometry.Bounds(0,0,1280,900);
        AdaptiveScreenGeometry.Plan preferred=AdaptiveScreenGeometry.arrange(available,1,1,Collections.emptyList(),320);
        assertEquals(320,preferred.primary.width());assertEquals(936,preferred.secondary.width());
        assertEquals(1280,preferred.dialogBounds.width());
        AdaptiveScreenGeometry.Plan largeFont=AdaptiveScreenGeometry.arrange(available,1,1.2f,Collections.emptyList(),320);
        assertEquals(384,largeFont.primary.width());assertEquals(872,largeFont.secondary.width());
        AdaptiveScreenGeometry.Plan oversized=AdaptiveScreenGeometry.arrange(available,1,1,Collections.emptyList(),2000);
        assertEquals(936,oversized.primary.width());assertEquals(320,oversized.secondary.width());
        AdaptiveScreenGeometry.Plan folded=AdaptiveScreenGeometry.arrange(available,1,1,
                Collections.singletonList(feature(620,0,660,900,true,true,true)),320);
        assertEquals(608,folded.primary.width());assertEquals(608,folded.secondary.width());
    }

    @Test public void largerFontsRequireMoreSpaceAndCanReturnToOneColumn() {
        assertEquals(AdaptiveScreenGeometry.Mode.COLUMNS,arrange(800,800,1,1).mode);
        assertEquals(AdaptiveScreenGeometry.Mode.SINGLE,arrange(800,800,1,1.3f).mode);
        assertEquals(AdaptiveScreenGeometry.Mode.COLUMNS,arrange(1000,800,1,1.3f).mode);
        assertEquals(AdaptiveScreenGeometry.Mode.SINGLE,arrange(2000,800,1,2).mode);
        assertEquals(AdaptiveScreenGeometry.Mode.SINGLE,arrange(800,800,1,Float.MAX_VALUE).mode);
    }

    @Test public void separatingVerticalHingeForcesPanelsAwayFromOcclusion() {
        AdaptiveScreenGeometry.Feature hinge=feature(340,0,360,900,true,true,true);
        AdaptiveScreenGeometry.Plan plan=arrange(700,900,1,1,hinge);
        assertEquals(AdaptiveScreenGeometry.Mode.COLUMNS,plan.mode);
        assertEquals(328,plan.primary.right);assertEquals(372,plan.secondary.left);
        excludes(plan,hinge.bounds);
    }

    @Test public void separatingZeroWidthFoldReservesSpaceButFlatCreaseDoesNot() {
        AdaptiveScreenGeometry.Plan split=arrange(700,900,1,1,feature(350,0,350,900,true,true,false));
        assertEquals(AdaptiveScreenGeometry.Mode.COLUMNS,split.mode);assertEquals(24,split.secondary.left-split.primary.right);
        assertEquals(AdaptiveScreenGeometry.Mode.SINGLE,
                arrange(700,900,1,1,feature(350,0,350,900,true,false,false)).mode);
    }

    @Test public void tabletopFoldCreatesUpperAndLowerScrollPanes() {
        AdaptiveScreenGeometry.Feature hinge=feature(0,390,700,410,false,true,true);
        AdaptiveScreenGeometry.Plan plan=arrange(700,800,1,1,hinge);
        assertEquals(AdaptiveScreenGeometry.Mode.ROWS,plan.mode);
        assertEquals(378,plan.primary.bottom);assertEquals(422,plan.secondary.top);excludes(plan,hinge.bounds);
    }

    @Test public void horizontalZeroHeightCreaseIsAlsoSeparating() {
        AdaptiveScreenGeometry.Plan plan=arrange(600,700,1,1,feature(0,350,600,350,false,true,false));
        assertEquals(AdaptiveScreenGeometry.Mode.ROWS,plan.mode);assertEquals(24,plan.secondary.top-plan.primary.bottom);
    }

    @Test public void edgeOrPartialOverlapUsesRemainingSafeRegion() {
        AdaptiveScreenGeometry.Feature edge=feature(-10,0,30,900,true,false,true);
        AdaptiveScreenGeometry.Plan plan=arrange(700,900,1,1,edge);
        assertEquals(AdaptiveScreenGeometry.Mode.SINGLE,plan.mode);assertEquals(42,plan.primary.left);excludes(plan,edge.bounds);
        AdaptiveScreenGeometry.Feature offCenter=feature(80,0,100,900,true,true,true);
        plan=arrange(700,900,1,1,offCenter);
        assertEquals(AdaptiveScreenGeometry.Mode.SINGLE,plan.mode);assertEquals(112,plan.primary.left);excludes(plan,offCenter.bounds);
    }

    @Test public void featureOutsideCurrentSplitScreenWindowDoesNotAffectLayout() {
        AdaptiveScreenGeometry.Plan plan=arrange(700,900,1,1,feature(800,0,820,900,true,true,true));
        assertEquals(AdaptiveScreenGeometry.Mode.SINGLE,plan.mode);assertEquals(0,plan.primary.left);assertEquals(700,plan.primary.right);
    }

    @Test public void safeInsetsRemainOutsideBothPanes() {
        AdaptiveScreenGeometry.Plan plan=AdaptiveScreenGeometry.arrange(
                new AdaptiveScreenGeometry.Bounds(20,40,980,700),1,1,Collections.emptyList());
        assertEquals(20,plan.primary.left);assertEquals(40,plan.primary.top);
        assertEquals(980,plan.secondary.right);assertEquals(700,plan.secondary.bottom);
    }

    @Test public void multipleOccludingFeaturesCannotBeCoveredByChosenPanes() {
        AdaptiveScreenGeometry.Feature vertical=feature(390,0,410,1000,true,true,true);
        AdaptiveScreenGeometry.Feature horizontal=feature(0,490,800,510,false,true,true);
        AdaptiveScreenGeometry.Plan plan=arrange(800,1000,1,1,vertical,horizontal);
        excludes(plan,vertical.bounds);excludes(plan,horizontal.bounds);
    }

    @Test public void zeroAvailableSpaceNeverProducesNegativeDimensions() {
        AdaptiveScreenGeometry.Plan plan=arrange(0,0,1,1);
        assertEquals(0,plan.primary.width());assertEquals(0,plan.primary.height());
    }

    private static AdaptiveScreenGeometry.Feature feature(int l,int t,int r,int b,boolean vertical,boolean separating,boolean occluding) {
        return new AdaptiveScreenGeometry.Feature(new AdaptiveScreenGeometry.Bounds(l,t,r,b),vertical,separating,occluding);
    }
    private static AdaptiveScreenGeometry.Plan arrange(int width,int height,float density,float fontScale,AdaptiveScreenGeometry.Feature... features) {
        return AdaptiveScreenGeometry.arrange(new AdaptiveScreenGeometry.Bounds(0,0,width,height),density,fontScale,Arrays.asList(features));
    }
    private static void excludes(AdaptiveScreenGeometry.Plan plan,AdaptiveScreenGeometry.Bounds blocked) {
        for(AdaptiveScreenGeometry.Bounds pane:new AdaptiveScreenGeometry.Bounds[]{plan.primary,plan.secondary}) {
            if(pane==null)continue;
            assertFalse(pane.left<blocked.right&&pane.right>blocked.left&&pane.top<blocked.bottom&&pane.bottom>blocked.top);
        }
    }
}
