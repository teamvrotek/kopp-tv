package ee.kalle.minimaltv;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Ordered package choices, independent of which apps are currently installed. */
final class AppSelection {
    private final ArrayList<String> packages;

    AppSelection(Collection<String> initial) {
        packages = new ArrayList<>(normalize(initial));
    }

    static List<String> normalize(Collection<String> input) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (input != null) {
            for (String value : input) {
                if (value != null && !value.trim().isEmpty()) unique.add(value.trim());
            }
        }
        return new ArrayList<>(unique);
    }

    List<String> packages() { return new ArrayList<>(packages); }

    boolean contains(String packageName) { return packages.contains(packageName); }

    void setSelected(String packageName, boolean selected) {
        if (packageName == null || packageName.trim().isEmpty()) return;
        String value = packageName.trim();
        if (!selected) packages.remove(value);
        else if (!packages.contains(value)) packages.add(value);
    }

    boolean move(String packageName, int direction) {
        if (direction != -1 && direction != 1) return false;
        int from = packages.indexOf(packageName);
        int to = from + direction;
        if (from < 0 || to < 0 || to >= packages.size()) return false;
        packages.remove(from);
        packages.add(to, packageName);
        return true;
    }
}
