package dev.translator.windows;

import dev.translator.client.ClientMessages;
import dev.translator.client.TranslationSession;
import dev.translator.client.TranslationSession.Cost;
import dev.translator.client.TranslationSession.Example;
import dev.translator.client.TranslationSession.LocalHttp;
import dev.translator.client.TranslationSession.Model;
import dev.translator.client.TranslationSession.Translation;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public final class TranslatorWindow {
    private static final String LOGIN_CARD = "login";
    private static final String WORKSPACE_CARD = "workspace";

    private final JFrame frame = new JFrame("Переводчик");
    private final CardLayout layout = new CardLayout();
    private final JPanel cards = new JPanel(layout);
    private final JLabel status = WindowStyle.caption(" ");
    private final JTextArea loginStatus = WindowStyle.notice();

    private final JTextField server = new JTextField("http://localhost:8080", 28);
    private final JTextField username = new JTextField(28);
    private final JPasswordField password = new JPasswordField(28);
    private final JButton signIn = WindowStyle.button("Войти", true);
    private final JButton signOut = WindowStyle.button("Выйти", false);

    private final JComboBox<Model> models = WindowStyle.combo(Model::name);
    private final JButton reloadModels = WindowStyle.button("Обновить модели", false);
    private final JLabel totalCost = WindowStyle.caption(" ");
    private final JCheckBox detect = WindowStyle.checkBox("Определить язык", true);
    private final JTextField sourceLanguage = new JTextField("en", 8);
    private final JTextField targetLanguage = new JTextField("ru", 8);
    private final JCheckBox usage = WindowStyle.checkBox("Варианты и примеры", false);
    private final JTextArea source = WindowStyle.editor(true, "Введите или вставьте текст");
    private final JTextArea output = WindowStyle.editor(false, "Здесь появится перевод");
    private final JButton translate = WindowStyle.button("Перевести", true);
    private final JButton copy = WindowStyle.button("Копировать", false);
    private final JLabel resultInfo = WindowStyle.caption(" ");

    private TranslationSession session;
    private WindowsSessionStore sessionStore;
    private Translation result;
    private boolean busy;
    private boolean sessionEnded;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            installLookAndFeel();
            new TranslatorWindow().frame.setVisible(true);
        });
    }

    private static void installLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ReflectiveOperationException | UnsupportedLookAndFeelException ignored) {
            // Swing's default look and feel is available as a fallback.
        }
    }

    private TranslatorWindow() {
        cards.setOpaque(false);
        cards.add(loginPanel(), LOGIN_CARD);
        cards.add(workspacePanel(), WORKSPACE_CARD);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBackground(WindowStyle.CANVAS);
        content.setBorder(BorderFactory.createEmptyBorder(18, 22, 14, 22));
        content.add(cards, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(760, 450));
        frame.setSize(980, 640);
        frame.setLocationRelativeTo(null);
        bindActions();
        showLogin();
        restoreSession();
    }

    private JPanel loginPanel() {
        WindowStyle.styleField(server);
        WindowStyle.styleField(username);
        WindowStyle.styleField(password);

        JPanel fields = new JPanel();
        fields.setOpaque(false);
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.add(labeled("Адрес сервера", server));
        fields.add(Box.createVerticalStrut(10));
        fields.add(labeled("Имя пользователя", username));
        fields.add(Box.createVerticalStrut(10));
        fields.add(labeled("Пароль", password));
        fields.add(Box.createVerticalStrut(14));
        JPanel action = new JPanel(new BorderLayout());
        action.setOpaque(false);
        action.add(signIn, BorderLayout.CENTER);
        fields.add(action);
        fields.add(Box.createVerticalStrut(8));
        fields.add(loginStatus);

        JPanel card = WindowStyle.card(24);
        card.setLayout(new BorderLayout(0, 18));
        card.add(brand("Вход в аккаунт"), BorderLayout.NORTH);
        card.add(fields, BorderLayout.CENTER);
        card.setPreferredSize(new Dimension(424, card.getPreferredSize().height));

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.add(card);
        return panel;
    }

    private JPanel workspacePanel() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setOpaque(false);
        header.add(brand("Текстовый перевод"), BorderLayout.WEST);
        totalCost.setHorizontalAlignment(SwingConstants.TRAILING);
        header.add(totalCost, BorderLayout.CENTER);
        header.add(signOut, BorderLayout.EAST);

        JPanel top = new JPanel(new BorderLayout(0, 12));
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        top.add(settingsPanel(), BorderLayout.CENTER);

        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setOpaque(false);
        panel.add(top, BorderLayout.NORTH);
        panel.add(editors(), BorderLayout.CENTER);
        panel.add(workspaceFooter(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel settingsPanel() {
        WindowStyle.styleField(sourceLanguage);
        WindowStyle.styleField(targetLanguage);
        models.setPrototypeDisplayValue(new Model("", "Модель перевода", 0));

        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        GridBagConstraints slot = new GridBagConstraints();
        slot.gridy = 0;
        slot.anchor = GridBagConstraints.SOUTH;
        slot.fill = GridBagConstraints.HORIZONTAL;
        slot.insets = new Insets(0, 0, 0, 12);
        fields.add(labeled("Исходный язык", sourceLanguage), slot);
        fields.add(labeled("Язык перевода", targetLanguage), slot);
        slot.weightx = 1;
        fields.add(labeled("Модель", models), slot);
        slot.weightx = 0;
        slot.insets = new Insets(0, 0, 0, 0);
        fields.add(reloadModels, slot);

        JPanel settings = WindowStyle.card(12);
        settings.setLayout(new BorderLayout(0, 8));
        settings.add(fields, BorderLayout.NORTH);
        settings.add(horizontalRow(detect, usage), BorderLayout.CENTER);
        return settings;
    }

    private JSplitPane editors() {
        copy.getAccessibleContext().setAccessibleName("Копировать перевод");
        JPanel input = editorPanel("Исходный текст", source, null);
        JPanel resultPanel = editorPanel("Перевод", output, copy);
        JSplitPane texts = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, input, resultPanel);
        texts.setUI(new BasicSplitPaneUI() {
            @Override
            public BasicSplitPaneDivider createDefaultDivider() {
                return new BasicSplitPaneDivider(this) {
                    @Override
                    public void paint(Graphics graphics) {
                        graphics.setColor(splitPane.hasFocus() ? WindowStyle.ACCENT : WindowStyle.LINE);
                        graphics.fillRoundRect(getWidth() / 2 - 1, (getHeight() - 28) / 2, 3, 28, 3, 3);
                    }
                };
            }
        });
        texts.setOpaque(false);
        texts.setBorder(null);
        texts.setDividerSize(14);
        texts.setResizeWeight(0.5);
        texts.setContinuousLayout(true);
        return texts;
    }

    private JPanel editorPanel(String title, JTextArea text, JButton action) {
        JLabel label = WindowStyle.label(title, 15, Font.BOLD, WindowStyle.INK);
        label.setLabelFor(text);
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        header.setPreferredSize(new Dimension(0, copy.getPreferredSize().height));
        header.add(label, BorderLayout.CENTER);
        if (action != null) {
            header.add(action, BorderLayout.EAST);
        }

        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, WindowStyle.LINE));
        scroll.setViewportBorder(null);
        JPanel card = WindowStyle.card(10);
        card.setMinimumSize(new Dimension(230, 92));
        card.setLayout(new BorderLayout(0, 7));
        card.add(header, BorderLayout.NORTH);
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    private JPanel workspaceFooter() {
        JPanel footer = new JPanel(new BorderLayout(16, 0));
        footer.setOpaque(false);
        footer.add(horizontalRow(translate, WindowStyle.caption("Ctrl + Enter")), BorderLayout.WEST);
        resultInfo.setHorizontalAlignment(SwingConstants.TRAILING);
        footer.add(resultInfo, BorderLayout.CENTER);
        return footer;
    }

    private static JPanel brand(String subtitle) {
        JPanel words = new JPanel(new BorderLayout(0, 1));
        words.setOpaque(false);
        words.add(WindowStyle.label("Переводчик", 23, Font.BOLD, WindowStyle.INK), BorderLayout.NORTH);
        words.add(WindowStyle.caption(subtitle), BorderLayout.CENTER);

        JPanel brand = new JPanel(new BorderLayout(12, 0));
        brand.setOpaque(false);
        brand.add(WindowStyle.mark(), BorderLayout.WEST);
        brand.add(words, BorderLayout.CENTER);
        return brand;
    }

    private void bindActions() {
        signIn.addActionListener(event -> signIn());
        signOut.addActionListener(event -> signOut());
        reloadModels.addActionListener(event -> loadModels());
        detect.addActionListener(event -> updateControls());
        models.addActionListener(event -> updateControls());
        translate.addActionListener(event -> translate());
        copy.addActionListener(event -> copyResult());
        var shortcut = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK);
        source.getInputMap().put(shortcut, "translate");
        source.getActionMap().put("translate", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                translate.doClick();
            }
        });
    }

    private void signIn() {
        TranslationSession candidate;
        try {
            candidate = new TranslationSession(server.getText(), LocalHttp.LOOPBACK, sessionStore);
        } catch (IllegalArgumentException error) {
            showError(error);
            return;
        }

        String user = username.getText().trim();
        char[] secret = password.getPassword();
        String enteredPassword = new String(secret);
        Arrays.fill(secret, '\0');
        password.setText("");
        runTask("Вход…", () -> {
            candidate.login(user, enteredPassword);
            return candidate;
        }, this::showWorkspace, this::showError);
    }

    private void restoreSession() {
        runTask("Восстановление сессии…", () -> {
            sessionStore = WindowsSessionStore.open();
            var saved = sessionStore.load();
            if (saved == null) {
                return null;
            }
            var restored = new TranslationSession(saved.serverUrl(), LocalHttp.LOOPBACK, sessionStore);
            restored.resume(saved);
            return restored;
        }, restored -> {
            if (restored != null) {
                showWorkspace(restored);
            }
        }, error -> {
            showLogin();
            String message = sessionStore == null
                    ? "Хранилище сессий недоступно. Вход сохранится только до закрытия окна."
                    : "Не удалось восстановить сессию. Войдите снова.";
            showStatus(message, WindowStyle.ERROR);
        });
    }

    private void showWorkspace(TranslationSession active) {
        session = active;
        server.setText(active.serverUrl());
        username.setText(active.username());
        layout.show(cards, WORKSPACE_CARD);
        status.setVisible(true);
        frame.getRootPane().setDefaultButton(null);
        source.requestFocusInWindow();
        loadModels();
    }

    private void signOut() {
        if (sessionEnded) {
            showLogin();
            showStatus(" ");
            return;
        }
        TranslationSession active = session;
        runTask("Выход…", () -> {
            active.logout();
            return null;
        }, ignored -> showLogin(), error -> {
            showLogin();
            showStatus(ClientMessages.describe(error), WindowStyle.ERROR);
        });
    }

    private void loadModels() {
        TranslationSession active = session;
        Model previous = (Model) models.getSelectedItem();
        runTask("Загрузка моделей…", active::getModels, available -> {
            models.removeAllItems();
            for (Model model : available) {
                models.addItem(model);
                if (previous != null && previous.id().equals(model.id())) {
                    models.setSelectedItem(model);
                }
            }
            if (available.isEmpty()) {
                showStatus("Для этого аккаунта нет доступных моделей.");
            }
            loadCost();
        }, this::showError);
    }

    private void loadCost() {
        TranslationSession active = session;
        setMetadata(totalCost, "Загрузка стоимости по аккаунту…");
        runTask(null, active::getCost, cost -> setMetadata(totalCost, accountCost(cost)), error -> {
            if (session == null || sessionEnded) {
                showError(error);
            }
            if (session != null) {
                setMetadata(totalCost, "Общая стоимость недоступна");
            }
        });
    }

    private void translate() {
        Model model = (Model) models.getSelectedItem();
        String text = source.getText();
        if (text.getBytes(StandardCharsets.UTF_8).length > model.maxInputBytes()) {
            showStatus("Текст слишком длинный для выбранной модели. Максимум "
                    + model.maxInputBytes() + " байт UTF-8.", WindowStyle.ERROR);
            return;
        }
        String from = detect.isSelected() ? null : sourceLanguage.getText();
        String to = targetLanguage.getText();
        boolean includeUsage = usage.isSelected();
        TranslationSession active = session;
        clearResult();
        runTask("Перевод…", () -> active.translate(text, from, to, model.id(), includeUsage),
                translation -> {
                    showResult(translation);
                    loadCost();
                }, this::showError);
    }

    private void showResult(Translation translation) {
        result = translation;
        var text = new StringBuilder(translation.text());
        if (!translation.alternatives().isEmpty()) {
            text.append("\n\nВарианты перевода\n");
            for (String alternative : translation.alternatives()) {
                text.append("\n• ").append(alternative);
            }
        }
        if (!translation.examples().isEmpty()) {
            text.append("\n\nПримеры употребления\n");
            for (Example example : translation.examples()) {
                text.append("\n").append(example.sourceText()).append("\n→ ")
                        .append(example.translatedText()).append("\n");
            }
        }
        output.setText(text.toString());
        output.setCaretPosition(0);
        String language = "und".equals(translation.sourceLanguage())
                ? "не определён" : translation.sourceLanguage();
        setMetadata(resultInfo, "Язык источника: " + language + "  ·  Стоимость запроса: "
                + usd(translation.costUsd()));
        updateControls();
    }

    private void copyResult() {
        try {
            var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(result.text()), null);
            showStatus("Перевод скопирован.");
        } catch (IllegalStateException error) {
            showStatus("Буфер обмена занят. Попробуйте ещё раз.", WindowStyle.ERROR);
        }
    }

    private <T> void runTask(String progress, Callable<T> action,
                             Consumer<T> success, Consumer<Exception> failure) {
        busy = true;
        if (progress != null) {
            showStatus(progress, WindowStyle.ACCENT);
        }
        updateControls();
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return action.call();
            }

            @Override
            protected void done() {
                busy = false;
                updateControls();
                try {
                    T value = get();
                    if (progress != null) {
                        showStatus(" ");
                    }
                    success.accept(value);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    failed(error);
                } catch (ExecutionException error) {
                    failed(error.getCause() instanceof Exception cause ? cause : error);
                }
            }

            private void failed(Exception error) {
                if (session != null && !session.isSignedIn()) {
                    endSession();
                }
                failure.accept(error);
            }
        }.execute();
    }

    private void endSession() {
        if (result == null) {
            showLogin();
            return;
        }
        sessionEnded = true;
        signOut.setText("Войти снова");
        updateControls();
    }

    private void showLogin() {
        session = null;
        sessionEnded = false;
        signOut.setText("Выйти");
        models.removeAllItems();
        source.setText("");
        setMetadata(totalCost, " ");
        clearResult();
        layout.show(cards, LOGIN_CARD);
        status.setVisible(false);
        frame.getRootPane().setDefaultButton(signIn);
        username.requestFocusInWindow();
        updateControls();
    }

    private void clearResult() {
        result = null;
        output.setText("");
        setMetadata(resultInfo, " ");
    }

    private void updateControls() {
        boolean editable = session != null && !sessionEnded && !busy;
        signIn.setEnabled(session == null && !busy);
        server.setEnabled(signIn.isEnabled());
        username.setEnabled(signIn.isEnabled());
        password.setEnabled(signIn.isEnabled());
        signOut.setEnabled(session != null && !busy);
        reloadModels.setEnabled(editable);
        models.setEnabled(editable && models.getItemCount() > 0);
        detect.setEnabled(editable);
        sourceLanguage.setEnabled(editable && !detect.isSelected());
        targetLanguage.setEnabled(editable);
        usage.setEnabled(editable);
        source.setEditable(editable);
        translate.setEnabled(editable && models.getSelectedItem() != null);
        copy.setEnabled(session != null && result != null);
    }

    private void showError(Exception error) {
        showStatus(ClientMessages.describe(error), WindowStyle.ERROR);
    }

    private void showStatus(String message) {
        showStatus(message, WindowStyle.MUTED);
    }

    private void showStatus(String message, Color color) {
        setMetadata(status, message);
        status.setForeground(color);
        loginStatus.setText(message);
        loginStatus.setForeground(color);
    }

    private static void setMetadata(JLabel label, String message) {
        label.setText(message);
        label.setToolTipText(message.isBlank() ? null : message);
    }

    private static String accountCost(Cost cost) {
        String unpriced = cost.unpricedRequests() == 0 ? "" : "  ·  без расчёта: " + cost.unpricedRequests();
        return "Учтено по аккаунту: " + usd(cost.totalCostUsd()) + unpriced;
    }

    private static String usd(BigDecimal cost) {
        return cost == null ? "недоступна" : cost.stripTrailingZeros().toPlainString() + " USD";
    }

    private static JPanel labeled(String label, JComponent component) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        JLabel caption = WindowStyle.caption(label);
        caption.setLabelFor(component);
        panel.add(caption, BorderLayout.NORTH);
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel horizontalRow(JComponent first, JComponent second) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.add(first);
        row.add(Box.createHorizontalStrut(16));
        row.add(second);
        return row;
    }
}
