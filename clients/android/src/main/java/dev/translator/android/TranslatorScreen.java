package dev.translator.android;

import android.content.Context;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import dev.translator.client.TranslationSession.Cost;
import dev.translator.client.TranslationSession.Example;
import dev.translator.client.TranslationSession.Model;
import dev.translator.client.TranslationSession.Translation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class TranslatorScreen extends ScrollView {
    final EditText serverUrl;
    final EditText username;
    final EditText password;
    final Button signIn;
    final TextView loginStatus;

    final Spinner model;
    final Button reloadModels;
    final CheckBox autoDetect;
    final EditText sourceLanguage;
    final EditText targetLanguage;
    final EditText sourceText;
    final CheckBox includeUsage;
    final Button translate;
    final Button signOut;
    final TextView workspaceStatus;
    final TextView result;
    final Button copy;
    final TextView accountCost;
    final Button reloadCost;

    private final LinearLayout login;
    private final LinearLayout workspace;
    private final LinearLayout resultSection;
    private final TextView languagePair;
    private final TextView requestCost;
    private final TextView alternativesTitle;
    private final TextView alternatives;
    private final TextView examplesTitle;
    private final TextView examples;

    TranslatorScreen(Context context) {
        super(context);
        setFillViewport(true);
        setSaveFromParentEnabled(false);
        setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        applyPadding(0, 0, 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setOnApplyWindowInsetsListener((view, windowInsets) -> {
                int types = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.ime();
                var insets = windowInsets.getInsets(types);
                applyPadding(insets.left, insets.top, insets.right, insets.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        LinearLayout content = column();
        content.setFocusableInTouchMode(true);
        addView(content);
        heading(content, R.string.app_name, android.R.style.TextAppearance_Material_Headline);

        login = column();
        content.addView(login);
        heading(login, R.string.sign_in_title, android.R.style.TextAppearance_Material_Title);
        serverUrl = input(login, R.string.server_label, InputType.TYPE_TEXT_VARIATION_URI);
        serverUrl.setHint(R.string.server_hint);
        username = input(login, R.string.username_label, InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        password = input(login, R.string.password_label, InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        signIn = button(login, R.string.sign_in);
        loginStatus = status(login);

        workspace = column();
        content.addView(workspace);
        workspace.setVisibility(GONE);
        signOut = button(workspace, R.string.sign_out);
        model = new Spinner(context, Spinner.MODE_DROPDOWN);
        label(workspace, R.string.model_label, model);
        workspace.addView(model);
        showModels(Collections.emptyList());
        reloadModels = button(workspace, R.string.reload_models);
        autoDetect = checkBox(workspace, R.string.auto_detect, true);
        sourceLanguage = input(workspace, R.string.source_label, InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        sourceLanguage.setHint(R.string.language_hint);
        targetLanguage = input(workspace, R.string.target_label, InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        targetLanguage.setHint(R.string.language_hint);
        sourceText = multilineInput(workspace);
        includeUsage = checkBox(workspace, R.string.include_usage, false);
        translate = button(workspace, R.string.translate);
        workspaceStatus = status(workspace);

        resultSection = column();
        workspace.addView(resultSection);
        resultSection.setVisibility(GONE);
        heading(resultSection, R.string.result_title, android.R.style.TextAppearance_Material_Title);
        languagePair = textView(resultSection, android.R.style.TextAppearance_Material_Body1);
        result = textView(resultSection, android.R.style.TextAppearance_Material_Body2);
        result.setTextIsSelectable(true);
        copy = button(resultSection, R.string.copy);
        alternativesTitle = heading(resultSection, R.string.alternatives_title,
            android.R.style.TextAppearance_Material_Subhead);
        alternatives = textView(resultSection, android.R.style.TextAppearance_Material_Body1);
        alternatives.setTextIsSelectable(true);
        examplesTitle = heading(resultSection, R.string.examples_title,
            android.R.style.TextAppearance_Material_Subhead);
        examples = textView(resultSection, android.R.style.TextAppearance_Material_Body1);
        examples.setTextIsSelectable(true);
        requestCost = textView(resultSection, android.R.style.TextAppearance_Material_Body1);
        accountCost = status(workspace);
        reloadCost = button(workspace, R.string.reload_cost);
    }

    void showWorkspace() {
        loginStatus.setText("");
        login.setVisibility(GONE);
        workspace.setVisibility(VISIBLE);
        sourceLanguage.setText(R.string.default_source_language);
        targetLanguage.setText(R.string.default_target_language);
        autoDetect.setChecked(true);
        includeUsage.setChecked(false);
        scrollTo(0, 0);
    }

    void showLogin(String message) {
        clearTranslation();
        sourceText.setText("");
        sourceLanguage.setText("");
        targetLanguage.setText("");
        password.setText("");
        username.setText("");
        accountCost.setText("");
        workspaceStatus.setText("");
        signOut.setText(R.string.sign_out);
        showModels(Collections.emptyList());
        workspace.setVisibility(GONE);
        login.setVisibility(VISIBLE);
        loginStatus.setText(message);
        scrollTo(0, 0);
    }

    void showModels(List<Model> models) {
        List<String> names = new ArrayList<>(models.size());
        for (Model entry : models) {
            names.add(entry.name());
        }
        if (names.isEmpty()) {
            names.add(getContext().getString(R.string.no_models));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            getContext(), android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        model.setAdapter(adapter);
    }

    void showTranslation(Translation translation) {
        Context context = getContext();
        languagePair.setText(context.getString(R.string.language_pair,
            languageLabel(translation.sourceLanguage()), languageLabel(translation.targetLanguage())));
        result.setText(translation.text());
        showOptional(alternativesTitle, alternatives, String.join("\n", translation.alternatives()));
        List<String> exampleLines = new ArrayList<>(translation.examples().size());
        for (Example example : translation.examples()) {
            exampleLines.add(context.getString(
                R.string.example_format, example.sourceText(), example.translatedText()));
        }
        showOptional(examplesTitle, examples, String.join("\n\n", exampleLines));

        BigDecimal cost = translation.costUsd();
        requestCost.setText(cost == null ? context.getString(R.string.request_cost_unknown)
            : context.getString(R.string.request_cost, formatCost(cost)));
        resultSection.setVisibility(VISIBLE);
        post(() -> smoothScrollTo(0, workspace.getTop() + resultSection.getTop()));
    }

    void clearTranslation() {
        resultSection.setVisibility(GONE);
        languagePair.setText("");
        result.setText("");
        alternatives.setText("");
        examples.setText("");
        requestCost.setText("");
    }

    void showCost(Cost cost) {
        String total = formatCost(cost.totalCostUsd());
        Context context = getContext();
        accountCost.setText(cost.unpricedRequests() == 0
            ? context.getString(R.string.account_cost, total)
            : context.getString(R.string.account_cost_partial, total, cost.unpricedRequests()));
    }

    void updateControls(boolean canSignIn, boolean canTranslate, boolean hasModel, boolean canSignOut) {
        serverUrl.setEnabled(canSignIn);
        username.setEnabled(canSignIn);
        password.setEnabled(canSignIn);
        signIn.setEnabled(canSignIn);
        signOut.setEnabled(canSignOut);
        reloadModels.setEnabled(canTranslate);
        reloadCost.setEnabled(canTranslate);
        model.setEnabled(canTranslate && hasModel);
        autoDetect.setEnabled(canTranslate);
        sourceLanguage.setEnabled(canTranslate && !autoDetect.isChecked());
        targetLanguage.setEnabled(canTranslate);
        sourceText.setEnabled(canTranslate);
        includeUsage.setEnabled(canTranslate);
        translate.setEnabled(canTranslate && hasModel);
        copy.setEnabled(resultSection.getVisibility() == VISIBLE);
    }

    private void applyPadding(int left, int top, int right, int bottom) {
        int margin = dp(16);
        setPadding(left + margin, top + margin, right + margin, bottom + margin);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView textView(LinearLayout parent, int appearance) {
        TextView text = new TextView(getContext());
        text.setTextAppearance(appearance);
        text.setPadding(0, dp(6), 0, dp(6));
        parent.addView(text);
        return text;
    }

    private TextView heading(LinearLayout parent, int text, int appearance) {
        TextView title = textView(parent, appearance);
        title.setText(text);
        return title;
    }

    private TextView status(LinearLayout parent) {
        TextView message = textView(parent, android.R.style.TextAppearance_Material_Body1);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return message;
    }

    private EditText input(LinearLayout parent, int labelText, int flags) {
        EditText field = new EditText(getContext());
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | flags);
        field.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        label(parent, labelText, field);
        parent.addView(field);
        return field;
    }

    private EditText multilineInput(LinearLayout parent) {
        EditText field = new EditText(getContext());
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setMinLines(4);
        field.setMaxLines(10);
        label(parent, R.string.text_label, field);
        parent.addView(field);
        return field;
    }

    private void label(LinearLayout parent, int text, View field) {
        field.setId(View.generateViewId());
        field.setSaveEnabled(false);
        TextView label = textView(parent, android.R.style.TextAppearance_Material_Body2);
        label.setText(text);
        label.setLabelFor(field.getId());
    }

    private Button button(LinearLayout parent, int text) {
        Button button = new Button(getContext());
        button.setText(text);
        button.setAllCaps(false);
        parent.addView(button);
        return button;
    }

    private CheckBox checkBox(LinearLayout parent, int text, boolean checked) {
        CheckBox checkBox = new CheckBox(getContext());
        checkBox.setText(text);
        checkBox.setChecked(checked);
        checkBox.setMinHeight(dp(48));
        parent.addView(checkBox);
        return checkBox;
    }

    private String languageLabel(String language) {
        return "und".equalsIgnoreCase(language)
            ? getContext().getString(R.string.language_undetermined)
            : language;
    }

    private static void showOptional(TextView title, TextView view, String text) {
        int visibility = text.isEmpty() ? GONE : VISIBLE;
        title.setVisibility(visibility);
        view.setVisibility(visibility);
        view.setText(text);
    }

    private static String formatCost(BigDecimal cost) {
        return cost.stripTrailingZeros().toPlainString();
    }

    private int dp(int pixels) {
        return Math.round(pixels * getResources().getDisplayMetrics().density);
    }
}
