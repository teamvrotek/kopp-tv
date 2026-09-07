package ee.kalle.minimaltv;

/** Pixel geometry shared by the fixed six-slot viewport and longer app rows. */
public final class HomeLayout {
    public static final int VISIBLE_APPS = 6;
    public final int viewportWidth;
    public final int frameHeight;
    public final int gap;
    public final int count;
    private final int baseWidth;
    private final int extraPixels;

    public HomeLayout(int viewportWidth, int gap, int thumbnailInset, int count) {
        this.viewportWidth = Math.max(1, viewportWidth);
        this.gap = Math.max(0, gap);
        this.count = Math.max(0, count);
        int available = Math.max(VISIBLE_APPS, this.viewportWidth - (VISIBLE_APPS - 1) * this.gap);
        baseWidth = available / VISIBLE_APPS;
        extraPixels = available % VISIBLE_APPS;
        frameHeight = Math.max(1, Math.round((available / 6f - 2 * thumbnailInset) * 9f / 16f + 2 * thumbnailInset));
    }

    public int frameWidth(int index) { return baseWidth + (index % VISIBLE_APPS < extraPixels ? 1 : 0); }

    public int offset(int index) {
        int slots = Math.max(0, index);
        return slots * (baseWidth + gap) + (slots / VISIBLE_APPS) * extraPixels
                + Math.min(slots % VISIBLE_APPS, extraPixels);
    }

    public int contentWidth() { return count == 0 ? 0 : offset(count) - gap; }

    public int firstVisible(int focused, int previousFirst) {
        int focus = Math.max(0, Math.min(count - 1, focused));
        int first = Math.max(0, Math.min(Math.max(0, count - VISIBLE_APPS), previousFirst));
        if (focus < first) first = focus;
        if (focus >= first + VISIBLE_APPS) first = focus - VISIBLE_APPS + 1;
        return first;
    }
}
