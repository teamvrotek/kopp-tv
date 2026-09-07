package ee.kalle.minimaltv;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AppSelectionTest {
    @Test public void openingAndCancellingADraftDoesNotChangeSavedChoices() {
        List<String> saved = new ArrayList<>(Arrays.asList("netflix", "go3", "youtube"));
        AppSelection draft = new AppSelection(saved);
        draft.setSelected("go3", false);
        draft.setSelected("disney", true);
        draft.move("disney", -1);
        assertEquals(Arrays.asList("netflix", "go3", "youtube"), saved);
        assertEquals(Arrays.asList("netflix", "disney", "youtube"), draft.packages());
    }

    @Test public void cancellingAnOrderDialogKeepsThePickersPreviousDraft() {
        AppSelection picker = new AppSelection(Arrays.asList("netflix", "go3", "youtube"));
        AppSelection ordering = new AppSelection(picker.packages());
        ordering.move("youtube", -1);
        ordering.move("youtube", -1);
        assertEquals(Arrays.asList("netflix", "go3", "youtube"), picker.packages());
        assertEquals(Arrays.asList("youtube", "netflix", "go3"), ordering.packages());
    }

    @Test public void moreThanSixAppsKeepTheirChosenOrder() {
        List<String> initial = Arrays.asList("netflix", "go3", "youtube", "telia", "disney", "apple");
        AppSelection draft = new AppSelection(initial);
        draft.setSelected("seventh", true);
        draft.setSelected("eighth", true);
        assertEquals(Arrays.asList("netflix", "go3", "youtube", "telia", "disney", "apple",
            "seventh", "eighth"), draft.packages());
    }

    @Test public void anUnavailableAppIsRetainedUntilExplicitlyDeselected() {
        AppSelection draft = new AppSelection(Arrays.asList("installed", "temporarily.uninstalled", "other"));
        draft.setSelected("newly.installed", true);
        assertEquals(Arrays.asList("installed", "temporarily.uninstalled", "other", "newly.installed"),
            draft.packages());
        draft.setSelected("temporarily.uninstalled", false);
        assertEquals(Arrays.asList("installed", "other", "newly.installed"), draft.packages());
    }

    @Test public void deselectingAndReselectingAppendsWithoutReorderingTheOthers() {
        AppSelection draft = new AppSelection(Arrays.asList("first", "middle", "last"));
        draft.setSelected("middle", false);
        draft.setSelected("middle", true);
        assertEquals(Arrays.asList("first", "last", "middle"), draft.packages());
    }

    @Test public void movingAnAppChangesOnlyItsAdjacentPosition() {
        AppSelection draft = new AppSelection(Arrays.asList("a", "b", "c", "d"));
        assertTrue(draft.move("c", -1));
        assertEquals(Arrays.asList("a", "c", "b", "d"), draft.packages());
        assertTrue(draft.move("c", 1));
        assertEquals(Arrays.asList("a", "b", "c", "d"), draft.packages());
    }

    @Test public void edgesAndUnknownAppsCannotWrapOrCorruptTheOrder() {
        AppSelection draft = new AppSelection(Arrays.asList("first", "last"));
        assertFalse(draft.move("first", -1));
        assertFalse(draft.move("last", 1));
        assertFalse(draft.move("missing", 1));
        assertFalse(draft.move("first", 7));
        assertEquals(Arrays.asList("first", "last"), draft.packages());
    }

    @Test public void allAppsCanBeDeselected() {
        AppSelection draft = new AppSelection(Arrays.asList("a", "b"));
        draft.setSelected("a", false);
        draft.setSelected("b", false);
        assertEquals(Collections.emptyList(), draft.packages());
        assertFalse(draft.move("a", 1));
    }

    @Test public void duplicateOrBlankPreferenceEntriesDoNotCreateDuplicateCards() {
        AppSelection draft = new AppSelection(Arrays.asList("app.a", "", null, " app.b ", "app.a", "  "));
        draft.setSelected("app.b", true);
        draft.setSelected(null, true);
        assertEquals(Arrays.asList("app.a", "app.b"), draft.packages());
    }

    @Test public void callersCannotMutateTheDraftThroughReturnedLists() {
        AppSelection draft = new AppSelection(Arrays.asList("a", "b"));
        List<String> exported = draft.packages();
        exported.clear();
        assertEquals(Arrays.asList("a", "b"), draft.packages());
    }
}
