package dev.translator.windows;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JToolTip;
import javax.swing.SwingConstants;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.ComboPopup;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Function;

final class WindowStyle {
    static final Color CANVAS = new Color(0xF4F6FB);
    static final Color SURFACE = Color.WHITE;
    static final Color INK = new Color(0x19233C);
    static final Color MUTED = new Color(0x526079);
    static final Color LINE = new Color(0xD9DFEA);
    static final Color ACCENT = new Color(0x4549C8);
    static final Color ERROR = new Color(0xA32943);

    private static final Color CONTROL_LINE = new Color(0xA8B4CA);
    private static final Color DISABLED_FILL = new Color(0xEEF1F7);
    private static final Color TINT = new Color(0xEDF0FF);
    private static final Font BODY = new Font("Segoe UI", Font.PLAIN, 14);
    private static final int CONTROL_ARC = 10;

    private WindowStyle() {}

    static JLabel label(String text, int size, int weight, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(BODY.deriveFont(weight, (float) size));
        label.setForeground(color);
        return label;
    }

    static JLabel caption(String text) {
        return label(text, 13, Font.PLAIN, MUTED);
    }

    static JPanel card(int padding) {
        JPanel panel = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics graphics) {
                paintSurface(graphics, getWidth(), getHeight(), 18, SURFACE, LINE);
            }
        };
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(padding, padding, padding, padding));
        return panel;
    }

    static JLabel mark() {
        JLabel mark = new JLabel("↔", SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics graphics) {
                paintSurface(graphics, getWidth(), getHeight(), 13, ACCENT, null);
                super.paintComponent(graphics);
            }
        };
        mark.setFont(BODY.deriveFont(Font.BOLD, 27f));
        mark.setForeground(Color.WHITE);
        mark.setPreferredSize(new Dimension(42, 42));
        return mark;
    }

    static JButton button(String text, boolean primary) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics graphics) {
                paintSurface(graphics, getWidth(), getHeight(), CONTROL_ARC,
                        buttonFill(this, primary), buttonLine(this, primary));
                super.paintComponent(graphics);
            }
        };
        button.setUI(new BasicButtonUI());
        button.setFont(BODY.deriveFont(Font.BOLD));
        button.setForeground(primary ? Color.WHITE : INK);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        button.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        repaintOnFocus(button, button);
        return button;
    }

    static JCheckBox checkBox(String text, boolean selected) {
        JCheckBox checkBox = new JCheckBox(text, selected);
        checkBox.setOpaque(false);
        checkBox.setFont(BODY);
        checkBox.setForeground(INK);
        checkBox.setMargin(new Insets(0, 0, 0, 0));
        return checkBox;
    }

    static void styleField(JTextComponent field) {
        field.setFont(BODY);
        field.setForeground(INK);
        field.setDisabledTextColor(MUTED);
        field.setCaretColor(ACCENT);
        field.setBackground(SURFACE);
        field.setBorder(new ControlBorder(new Insets(7, 10, 7, 10)));
        repaintOnFocus(field, field);
    }

    static <T> JComboBox<T> combo(Function<T, String> displayName) {
        JComboBox<T> combo = new JComboBox<>() {
            @Override
            public JToolTip createToolTip() {
                JToolTip tip = super.createToolTip();
                tip.putClientProperty("html.disable", Boolean.TRUE);
                return tip;
            }
        };
        combo.setUI(new BasicComboBoxUI() {
            @Override
            protected JButton createArrowButton() {
                return comboArrow();
            }

            @Override
            protected ComboPopup createPopup() {
                return new BasicComboPopup(comboBox) {
                    @Override
                    public void show() {
                        Insets insets = getInsets();
                        int scrollbar = scroller.getVerticalScrollBar().getPreferredSize().width;
                        int width = comboBox.getWidth() - insets.left - insets.right - scrollbar;
                        getList().setFixedCellWidth(Math.max(1, width));
                        super.show();
                    }
                };
            }
        });
        combo.setFont(BODY);
        combo.setForeground(INK);
        combo.setBackground(SURFACE);
        combo.setBorder(new ControlBorder(new Insets(6, 8, 6, 6)));
        repaintOnFocus(combo, combo);

        DefaultListCellRenderer selection = new DefaultListCellRenderer();
        selection.putClientProperty("html.disable", Boolean.TRUE);
        JTextArea option = new JTextArea();
        option.setFont(BODY);
        option.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        option.setEditable(false);
        option.setLineWrap(true);
        option.setWrapStyleWord(true);
        combo.setRenderer((list, item, index, selected, focused) -> {
            String name = item == null ? "" : displayName.apply(item);
            if (index < 0) {
                selection.getListCellRendererComponent(list, name, index, selected, focused);
                selection.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
                selection.setFont(BODY);
                selection.setForeground(combo.isEnabled() ? INK : MUTED);
                selection.setBackground(SURFACE);
                return selection;
            }
            option.setText(name);
            option.getAccessibleContext().setAccessibleName(name);
            option.setSize(Math.max(80, list.getFixedCellWidth()), Short.MAX_VALUE);
            option.setForeground(selected ? ACCENT : INK);
            option.setBackground(selected ? TINT : SURFACE);
            return option;
        });
        combo.addActionListener(event -> {
            T item = combo.getItemAt(combo.getSelectedIndex());
            combo.setToolTipText(item == null ? null : displayName.apply(item));
        });
        return combo;
    }

    private static JButton comboArrow() {
        JButton arrow = new JButton() {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D copy = (Graphics2D) graphics.create();
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                copy.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                copy.setColor(isEnabled() ? MUTED : CONTROL_LINE);
                int x = getWidth() / 2;
                int y = getHeight() / 2;
                copy.drawPolyline(new int[] {x - 4, x, x + 4}, new int[] {y - 2, y + 2, y - 2}, 3);
                copy.dispose();
            }
        };
        arrow.setUI(new BasicButtonUI());
        arrow.setPreferredSize(new Dimension(18, 18));
        arrow.setBorder(BorderFactory.createEmptyBorder());
        arrow.setContentAreaFilled(false);
        arrow.setFocusable(false);
        return arrow;
    }

    static JTextArea editor(boolean editable, String placeholder) {
        JTextArea area = new JTextArea(0, 18) {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                if (getDocument().getLength() == 0) {
                    Insets insets = getInsets();
                    Graphics copy = graphics.create();
                    copy.setFont(getFont());
                    copy.setColor(MUTED);
                    int baseline = insets.top + copy.getFontMetrics().getAscent();
                    copy.drawString(placeholder, insets.left, baseline);
                    copy.dispose();
                }
            }
        };
        area.setFont(BODY.deriveFont(17f));
        area.setForeground(INK);
        area.setBackground(SURFACE);
        area.setCaretColor(ACCENT);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(editable);
        area.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        return area;
    }

    static JTextArea notice() {
        JTextArea area = new JTextArea(2, 1);
        area.setFont(BODY.deriveFont(13f));
        area.setForeground(MUTED);
        area.setOpaque(false);
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    static void repaintOnFocus(JComponent target, JComponent surface) {
        target.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                surface.repaint();
            }

            @Override
            public void focusLost(FocusEvent event) {
                surface.repaint();
            }
        });
    }

    static void paintSurface(Graphics graphics, int width, int height, int arc, Color fill, Color line) {
        Graphics2D copy = (Graphics2D) graphics.create();
        copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (fill != null) {
            copy.setColor(fill);
            copy.fillRoundRect(0, 0, width, height, arc, arc);
        }
        if (line != null) {
            copy.setColor(line);
            copy.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            copy.draw(new RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, arc, arc));
        }
        copy.dispose();
    }

    private static Color buttonFill(JButton button, boolean primary) {
        if (!button.isEnabled()) {
            return DISABLED_FILL;
        }
        if (button.getModel().isPressed()) {
            return primary ? new Color(0x30339D) : new Color(0xE0E5FF);
        }
        if (button.getModel().isRollover()) {
            return primary ? new Color(0x383CB4) : TINT;
        }
        return primary ? ACCENT : SURFACE;
    }

    private static Color buttonLine(JButton button, boolean primary) {
        if (button.hasFocus()) {
            return primary ? INK : ACCENT;
        }
        return primary && button.isEnabled() ? null : CONTROL_LINE;
    }

    private static final class ControlBorder extends AbstractBorder {
        private final Insets padding;

        private ControlBorder(Insets padding) {
            this.padding = padding;
        }

        @Override
        public Insets getBorderInsets(Component component, Insets insets) {
            insets.set(padding.top, padding.left, padding.bottom, padding.right);
            return insets;
        }

        @Override
        public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
            Graphics copy = graphics.create(x, y, width, height);
            Color line = component.hasFocus() ? ACCENT : CONTROL_LINE;
            paintSurface(copy, width, height, CONTROL_ARC, null, line);
            copy.dispose();
        }
    }
}
