package dev.translator.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

import dev.translator.client.ClientMessages;
import dev.translator.client.TranslationSession;
import dev.translator.client.TranslationSession.LocalHttp;
import dev.translator.client.TranslationSession.Model;
import dev.translator.client.TranslationSession.Translation;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final String SERVER_URL = "serverUrl";
    private static final int TEXT_LIMIT = 10_000;
    private static final int LANGUAGE_LIMIT = 80;

    private final ExecutorService requests = Executors.newSingleThreadExecutor();
    private TranslatorScreen screen;
    private TranslationSession session;
    private AndroidSessionStore sessionStore;
    private List<Model> models = Collections.emptyList();
    private boolean busy;
    private boolean sessionEnded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        screen = new TranslatorScreen(this);
        sessionStore = AndroidSessionStore.get(getApplicationContext());
        setContentView(screen);
        String defaultUrl = BuildConfig.DEBUG ? "http://10.0.2.2:8080" : "";
        screen.serverUrl.setText(getPreferences(MODE_PRIVATE).getString(SERVER_URL, defaultUrl));
        screen.signIn.setOnClickListener(view -> signIn());
        screen.password.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE && screen.signIn.isEnabled()) {
                signIn();
                return true;
            }
            return false;
        });
        screen.signOut.setOnClickListener(view -> signOut());
        screen.reloadModels.setOnClickListener(view -> loadModels());
        screen.reloadCost.setOnClickListener(view -> loadCost());
        screen.translate.setOnClickListener(view -> translate());
        screen.copy.setOnClickListener(view -> copyTranslation());
        screen.autoDetect.setOnCheckedChangeListener((button, checked) -> updateControls());
        updateControls();
        restoreSession();
    }

    private void signIn() {
        String address = input(screen.serverUrl).trim();
        String username = input(screen.username).trim();
        String password = input(screen.password);
        if (username.isEmpty() || password.isEmpty()) {
            screen.loginStatus.setText(R.string.credentials_required);
            return;
        }

        TranslationSession candidate;
        try {
            LocalHttp localHttp = BuildConfig.DEBUG ? LocalHttp.ANDROID_EMULATOR : LocalHttp.DISABLED;
            candidate = new TranslationSession(address, localHttp, sessionStore);
        } catch (IllegalArgumentException error) {
            screen.loginStatus.setText(ClientMessages.describe(error));
            return;
        }

        hideKeyboard();
        screen.password.setText("");
        screen.loginStatus.setText(R.string.signing_in);
        runRequest(() -> {
            candidate.login(username, password);
            return candidate;
        }, result -> {
            session = result;
            getPreferences(MODE_PRIVATE).edit().putString(SERVER_URL, address).apply();
            screen.showWorkspace();
            loadModels();
        }, error -> screen.loginStatus.setText(ClientMessages.describe(error)));
    }

    private void restoreSession() {
        screen.loginStatus.setText(R.string.restoring_session);
        runRequest(() -> {
            var saved = sessionStore.load();
            if (saved == null) {
                return null;
            }
            LocalHttp localHttp = BuildConfig.DEBUG ? LocalHttp.ANDROID_EMULATOR : LocalHttp.DISABLED;
            var restored = new TranslationSession(saved.serverUrl(), localHttp, sessionStore);
            restored.resume(saved);
            return restored;
        }, restored -> {
            screen.loginStatus.setText("");
            if (restored != null) {
                session = restored;
                screen.serverUrl.setText(restored.serverUrl());
                screen.username.setText(restored.username());
                screen.showWorkspace();
                loadModels();
            }
        }, error -> screen.loginStatus.setText(R.string.restore_failed));
    }

    private void signOut() {
        if (sessionEnded) {
            showLogin("");
            updateControls();
            return;
        }
        TranslationSession activeSession = session;
        screen.workspaceStatus.setText(R.string.signing_out);
        runRequest(() -> {
            activeSession.logout();
            return null;
        }, result -> showLogin(""), error -> showLogin(ClientMessages.describe(error)));
    }

    private void loadModels() {
        TranslationSession activeSession = session;
        screen.workspaceStatus.setText(R.string.loading_models);
        runRequest(activeSession::getModels, loaded -> {
            models = loaded;
            screen.showModels(loaded);
            screen.workspaceStatus.setText(loaded.isEmpty() ? getString(R.string.no_models) : "");
            loadCost();
        }, error -> workspaceError(activeSession, error));
    }

    private void translate() {
        Model model = selectedModel();
        if (model == null) {
            return;
        }
        String sourceLanguage = screen.autoDetect.isChecked() ? null : input(screen.sourceLanguage).trim();
        String targetLanguage = input(screen.targetLanguage).trim();
        String text = input(screen.sourceText);
        if (!validInput(text, sourceLanguage, targetLanguage, model)) {
            return;
        }

        TranslationSession activeSession = session;
        boolean includeUsage = screen.includeUsage.isChecked();
        hideKeyboard();
        screen.clearTranslation();
        screen.workspaceStatus.setText(R.string.translating);
        runRequest(
            () -> activeSession.translate(text, sourceLanguage, targetLanguage, model.id(), includeUsage),
            this::showTranslation,
            error -> workspaceError(activeSession, error));
    }

    private Model selectedModel() {
        int position = screen.model.getSelectedItemPosition();
        return position >= 0 && position < models.size() ? models.get(position) : null;
    }

    private boolean validInput(String text, String source, String target, Model model) {
        if (text.trim().isEmpty()) {
            screen.workspaceStatus.setText(R.string.text_required);
            return false;
        }
        if (text.length() > TEXT_LIMIT
            || text.getBytes(StandardCharsets.UTF_8).length > model.maxInputBytes()) {
            screen.workspaceStatus.setText(getString(R.string.text_limit, TEXT_LIMIT, model.maxInputBytes()));
            return false;
        }
        if ((source != null && !validLanguage(source)) || !validLanguage(target)) {
            screen.workspaceStatus.setText(R.string.language_required);
            return false;
        }
        return true;
    }

    private static boolean validLanguage(String value) {
        return !value.isEmpty() && value.length() <= LANGUAGE_LIMIT;
    }

    private void showTranslation(Translation translation) {
        screen.workspaceStatus.setText("");
        screen.showTranslation(translation);
        loadCost();
    }

    private void loadCost() {
        TranslationSession activeSession = session;
        screen.accountCost.setText(R.string.loading_cost);
        runRequest(activeSession::getCost, screen::showCost, error -> showCostFailure(activeSession));
    }

    private void showCostFailure(TranslationSession activeSession) {
        screen.accountCost.setText(R.string.account_cost_unknown);
        if (activeSession.isSignedIn()) {
            return;
        }
        if (screen.result.length() == 0) {
            showLogin(getString(R.string.session_ended));
            return;
        }
        sessionEnded = true;
        screen.signOut.setText(R.string.sign_in_again);
        screen.accountCost.setText(R.string.session_ended_with_result);
    }

    private void copyTranslation() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData translation = ClipData.newPlainText(
            getString(R.string.result_title), screen.result.getText().toString());
        clipboard.setPrimaryClip(translation);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void workspaceError(TranslationSession activeSession, Exception error) {
        // Called only after the single request worker returns to the UI.
        if (activeSession.isSignedIn()) {
            screen.workspaceStatus.setText(ClientMessages.describe(error));
        } else {
            showLogin(getString(R.string.session_ended));
        }
    }

    private void showLogin(String message) {
        session = null;
        sessionEnded = false;
        models = Collections.emptyList();
        screen.showLogin(message);
    }

    private <T> void runRequest(Callable<T> action, Consumer<T> success, Consumer<Exception> failure) {
        busy = true;
        updateControls();
        requests.execute(() -> {
            try {
                T result;
                // Activity recreation can overlap a request from the previous screen.
                synchronized (sessionStore) {
                    result = action.call();
                }
                runOnUiThread(() -> completeRequest(() -> success.accept(result)));
            } catch (Exception error) {
                runOnUiThread(() -> completeRequest(() -> failure.accept(error)));
            }
        });
    }

    private void completeRequest(Runnable update) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        busy = false;
        update.run();
        updateControls();
    }

    private void updateControls() {
        boolean canSignIn = !busy && session == null;
        boolean canUseWorkspace = !busy && session != null;
        boolean hasModel = selectedModel() != null;
        screen.updateControls(canSignIn, canUseWorkspace && !sessionEnded, hasModel, canUseWorkspace);
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
    }

    private static String input(TextView view) {
        return view.getText().toString();
    }

    @Override
    protected void onDestroy() {
        requests.shutdown();
        session = null;
        super.onDestroy();
    }
}
