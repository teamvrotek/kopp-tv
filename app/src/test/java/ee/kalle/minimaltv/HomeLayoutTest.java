package ee.kalle.minimaltv;

import org.junit.Test;
import static org.junit.Assert.*;

public class HomeLayoutTest {
    @Test public void sixCardsExactlyFillViewportAtDifferentDensities() {
        for (int width : new int[] {832, 1234, 1792, 2688, 3584}) {
            for (int gap : new int[] {5, 8, 10, 15, 20}) {
                HomeLayout layout = new HomeLayout(width, gap, 12, 24);
                for (int start = 0; start < 18; start++) {
                    assertEquals(width, layout.offset(start + 6) - layout.offset(start) - gap);
                }
            }
        }
    }

    @Test public void smallerSelectionsKeepCardSizesAndRoomToCenter() {
        HomeLayout full = new HomeLayout(1792, 10, 12, 6);
        for (int count = 1; count < 6; count++) {
            HomeLayout layout = new HomeLayout(1792, 10, 12, count);
            assertTrue(layout.contentWidth() < layout.viewportWidth);
            assertEquals(full.frameHeight, layout.frameHeight);
            for (int i = 0; i < count; i++) assertEquals(full.frameWidth(i), layout.frameWidth(i));
            assertEquals(0, layout.firstVisible(count - 1, 99));
        }
    }

    @Test public void movingAcrossLongRowAlwaysRevealsSixWholeCards() {
        for (int count : new int[] {7, 12, 13, 100}) {
            HomeLayout layout = new HomeLayout(1792, 10, 12, count);
            int first = 0;
            for (int focus = 0; focus < count; focus++) {
                first = layout.firstVisible(focus, first);
                assertTrue(focus >= first && focus < first + 6);
                assertTrue(layout.offset(first) <= layout.contentWidth() - layout.viewportWidth);
            }
            assertEquals(layout.contentWidth() - layout.viewportWidth, layout.offset(first));
            for (int focus = count - 1; focus >= 0; focus--) {
                first = layout.firstVisible(focus, first);
                assertTrue(focus >= first && focus < first + 6);
            }
            assertEquals(0, first);
        }
    }

    @Test public void removalClampsPreviousScrollAndEmptyStateHasNoContent() {
        assertEquals(1, new HomeLayout(1792, 10, 12, 7).firstVisible(6, 20));
        assertEquals(0, new HomeLayout(1792, 10, 12, 0).firstVisible(50, 20));
        assertEquals(0, new HomeLayout(1792, 10, 12, 0).contentWidth());
    }
}
