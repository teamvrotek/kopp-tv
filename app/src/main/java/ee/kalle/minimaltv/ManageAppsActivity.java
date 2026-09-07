package ee.kalle.minimaltv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Remote-friendly app selection with a draft that is saved only by Done. */
public final class ManageAppsActivity extends Activity {
    private static final String STATE_DRAFT = "app_selection_draft";
    private final ArrayList<String> catalogPackages = new ArrayList<>();
    private final Map<String, String> catalogLabels = new LinkedHashMap<>();
    private AppPreferences preferences;
    private AppSelection selection;
    private ListView appList;
    private TextView selectedCount;
    private Button arrangeButton;
    private Button cancelButton;
    private Button doneButton;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        preferences = new AppPreferences(this);
        preferences.migrateLegacyIfNeeded();
        ArrayList<String> restored = state == null ? null : state.getStringArrayList(STATE_DRAFT);
        selection = new AppSelection(restored == null ? preferences.selectedPackages() : restored);
        for (AppCatalog.Entry entry : AppCatalog.discover(this)) {
            catalogPackages.add(entry.packageName);
            catalogLabels.put(entry.packageName, entry.label);
        }
        // Retain unavailable choices so users can keep or explicitly remove them.
        for (String packageName : selection.packages()) {
            if (!catalogLabels.containsKey(packageName)) {
                catalogPackages.add(packageName);
                catalogLabels.put(packageName, getString(R.string.apps_unavailable, packageName));
            }
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(32), dp(20), dp(32), dp(20));
        root.setBackgroundColor(Color.rgb(15, 19, 24));
        root.addView(text(getString(R.string.apps_title), 26));
        TextView instructions = text(getString(R.string.apps_instructions), 15);
        instructions.setPadding(0, dp(6), 0, dp(8));
        root.addView(instructions);
        selectedCount = text("", 15);
        selectedCount.setPadding(0, 0, 0, dp(8));
        root.addView(selectedCount);

        appList = new ListView(this);
        appList.setId(View.generateViewId());
        appList.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        appList.setSelector(new ColorDrawable(Color.rgb(43, 61, 75)));
        appList.setDrawSelectorOnTop(false);
        appList.setDividerHeight(dp(1));
        appList.setAdapter(checkedAdapter(catalogNames(), android.R.layout.simple_list_item_multiple_choice));
        for (int index = 0; index < catalogPackages.size(); index++) {
            appList.setItemChecked(index, selection.contains(catalogPackages.get(index)));
        }
        appList.setOnItemClickListener((parent, view, position, id) -> {
            selection.setSelected(catalogPackages.get(position), appList.isItemChecked(position));
            updateCount();
        });
        root.addView(appList, new LinearLayout.LayoutParams(-1, 0, 1));
        if (catalogPackages.isEmpty()) {
            TextView empty = text(getString(R.string.apps_none_installed), 18);
            empty.setPadding(0, dp(8), 0, dp(12));
            root.addView(empty);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(10), 0, 0);
        arrangeButton = button(R.string.apps_arrange);
        cancelButton = button(R.string.apps_cancel);
        doneButton = button(R.string.apps_done);
        addAction(actions, arrangeButton);
        addAction(actions, cancelButton);
        addAction(actions, doneButton);
        root.addView(actions);
        arrangeButton.setOnClickListener(view -> showArrangeDialog());
        cancelButton.setOnClickListener(view -> finish());
        doneButton.setOnClickListener(view -> {
            preferences.save(selection.packages());
            setResult(RESULT_OK);
            finish();
        });
        appList.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                (arrangeButton.isEnabled() ? arrangeButton : doneButton).requestFocus();
            }
            return true;
        });
        for (Button action : new Button[] {arrangeButton, cancelButton, doneButton}) {
            action.setOnKeyListener((view, key, event) -> {
                if (key != KeyEvent.KEYCODE_DPAD_UP || catalogPackages.isEmpty()) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) appList.requestFocus();
                return true;
            });
        }
        updateCount();
        setContentView(root);
        if (catalogPackages.isEmpty()) doneButton.requestFocus();
        else appList.requestFocus();
    }

    private void updateCount() {
        int count = selection.packages().size();
        selectedCount.setText(getString(R.string.apps_selected_count, count));
        arrangeButton.setEnabled(count > 1);
    }

    private void showArrangeDialog() {
        AppSelection draft = new AppSelection(selection.packages());
        if (draft.packages().size() < 2) return;
        int[] selected = {0};
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView help = text(getString(R.string.apps_arrange_instructions), 14);
        help.setPadding(0, 0, 0, dp(10));
        panel.addView(help);
        LinearLayout content = new LinearLayout(this);
        panel.addView(content);
        ListView orderList = new ListView(this);
        orderList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        orderList.setSelector(new ColorDrawable(Color.rgb(43, 61, 75)));
        ArrayAdapter<String> adapter = checkedAdapter(namesFor(draft.packages()),
            android.R.layout.simple_list_item_single_choice);
        orderList.setAdapter(adapter);
        orderList.setItemChecked(0, true);
        int height = Math.min(dp(230), Math.round(getResources().getDisplayMetrics().heightPixels * .44f));
        content.addView(orderList, new LinearLayout.LayoutParams(0, height, 1));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(12), 0, 0, 0);
        content.addView(controls, new LinearLayout.LayoutParams(dp(156), -2));
        Button earlier = button(R.string.apps_move_earlier);
        Button later = button(R.string.apps_move_later);
        controls.addView(earlier, new LinearLayout.LayoutParams(-1, dp(52)));
        LinearLayout.LayoutParams laterParams = new LinearLayout.LayoutParams(-1, dp(52));
        laterParams.topMargin = dp(8);
        controls.addView(later, laterParams);
        Runnable updateControls = () -> {
            earlier.setEnabled(selected[0] > 0);
            later.setEnabled(selected[0] < draft.packages().size() - 1);
        };
        orderList.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selected[0] = position;
                orderList.setItemChecked(position, true);
                updateControls.run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        orderList.setOnItemClickListener((parent, view, position, id) -> {
            selected[0] = position;
            updateControls.run();
        });
        View.OnClickListener move = view -> {
            int direction = view == earlier ? -1 : 1;
            String packageName = draft.packages().get(selected[0]);
            if (!draft.move(packageName, direction)) return;
            selected[0] += direction;
            adapter.setNotifyOnChange(false);
            adapter.clear();
            adapter.addAll(namesFor(draft.packages()));
            adapter.notifyDataSetChanged();
            orderList.setItemChecked(selected[0], true);
            orderList.setSelection(selected[0]);
            updateControls.run();
            if (!view.isEnabled()) (direction < 0 ? later : earlier).requestFocus();
        };
        earlier.setOnClickListener(move);
        later.setOnClickListener(move);
        orderList.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                (earlier.isEnabled() ? earlier : later).requestFocus();
            }
            return true;
        });
        for (Button control : new Button[] {earlier, later}) {
            control.setOnKeyListener((view, key, event) -> {
                if (key != KeyEvent.KEYCODE_DPAD_LEFT) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) orderList.requestFocus();
                return true;
            });
        }
        updateControls.run();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.apps_arrange)
            .setView(panel).setNegativeButton(R.string.apps_cancel, null)
            .setPositiveButton(R.string.apps_use_order, (whichDialog, which) -> {
                selection = new AppSelection(draft.packages());
                updateCount();
            }).create();
        dialog.setOnShowListener(ignored -> orderList.requestFocus());
        dialog.show();
    }

    private ArrayList<String> catalogNames() { return namesFor(catalogPackages); }

    private ArrayList<String> namesFor(List<String> packages) {
        ArrayList<String> labels = new ArrayList<>();
        for (String packageName : packages) {
            String label = catalogLabels.get(packageName);
            labels.add(label == null ? getString(R.string.apps_unavailable, packageName) : label);
        }
        return labels;
    }

    private ArrayAdapter<String> checkedAdapter(List<String> labels, int layout) {
        return new ArrayAdapter<String>(this, layout, new ArrayList<>(labels)) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                CheckedTextView view = (CheckedTextView) super.getView(position, convertView, parent);
                view.setTextColor(Color.rgb(242, 246, 249));
                view.setTextSize(18);
                view.setMinHeight(dp(48));
                view.setFocusable(false);
                return view;
            }
        };
    }

    private TextView text(String value, int size) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(Color.rgb(242, 246, 249));
        return result;
    }

    private Button button(int label) {
        Button result = new Button(this);
        result.setId(View.generateViewId());
        result.setText(label);
        result.setAllCaps(false);
        result.setTextSize(15);
        result.setPadding(dp(8), 0, dp(8), 0);
        result.setFocusable(true);
        result.setFocusableInTouchMode(false);
        result.setStateListAnimator(null);
        result.setTextColor(new ColorStateList(new int[][] {
            new int[] {-android.R.attr.state_enabled}, new int[] {}
        }, new int[] {Color.rgb(115, 126, 135), Color.rgb(242, 246, 249)}));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_focused}, buttonBackground(true));
        background.addState(new int[] {android.R.attr.state_pressed}, buttonBackground(true));
        background.addState(new int[] {}, buttonBackground(false));
        result.setBackground(background);
        return result;
    }

    private GradientDrawable buttonBackground(boolean focused) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(focused ? Color.rgb(43, 61, 75) : Color.rgb(27, 35, 43));
        shape.setCornerRadius(dp(5));
        if (focused) shape.setStroke(dp(2), Color.rgb(242, 246, 249));
        return shape;
    }

    private void addAction(LinearLayout parent, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        if (parent.getChildCount() > 0) params.leftMargin = dp(10);
        parent.addView(button, params);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putStringArrayList(STATE_DRAFT, new ArrayList<>(selection.packages()));
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() { finish(); }
}
