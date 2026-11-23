package io.github.pluginprofiler;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import net.runelite.client.ui.PluginPanel;

final class ProfilerPanel extends PluginPanel
{
    private enum Metric
    {
        LIVE("Last 60 sec"), SLOW_FRAMES("Slow-frame lift · 5 min"), SESSION("Session");

        private final String label;
        Metric(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private static final SampleHistory.Stats EMPTY = new SampleHistory.Stats();
    private final List<ProfilerPlugin.Entry> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JComboBox<Metric> metric = new JComboBox<>(Metric.values());
    private final JCheckBox hideZero = new JCheckBox("Hide 0% Usage");
    private final TableRowSorter<Model> sorter = new TableRowSorter<Model>(model)
    {
        @Override public void toggleSortOrder(int column)
        {
            if (column != 1) { super.toggleSortOrder(column); return; }
            for (RowSorter.SortKey key : getSortKeys())
            {
                if (key.getColumn() == 3) { continue; }
                if (key.getColumn() == 1 && key.getSortOrder() == SortOrder.DESCENDING)
                {
                    sortMetric(SortOrder.ASCENDING);
                    return;
                }
                break;
            }
            sortMetric(SortOrder.DESCENDING);
        }
    };
    private final JTextArea suggestion = textArea();
    private final JTextArea message = textArea();
    private SampleHistory.Snapshot snapshot = new SampleHistory.Snapshot(Collections.emptyMap(), 0);

    ProfilerPanel(boolean hideInitially, Consumer<Boolean> saveHideZero)
    {
        super(false);
        setLayout(new BorderLayout(0, 5));
        setBorder(BorderFactory.createEmptyBorder(6, 5, 5, 5));
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JPanel picker = new JPanel(new BorderLayout(4, 0));
        picker.add(new JLabel("Rank by"), BorderLayout.WEST);
        picker.add(metric, BorderLayout.CENTER);
        hideZero.setToolTipText("Hide zero and unavailable values for this metric");
        hideZero.addActionListener(e ->
        {
            setHideZero(hideZero.isSelected());
            saveHideZero.accept(hideZero.isSelected());
        });
        picker.setAlignmentX(LEFT_ALIGNMENT);
        header.add(picker);
        hideZero.setAlignmentX(LEFT_ALIGNMENT);
        header.add(hideZero);
        suggestion.setAlignmentX(LEFT_ALIGNMENT);
        suggestion.setVisible(false);
        header.add(suggestion);
        add(header, BorderLayout.NORTH);

        table.setRowHeight(23);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        sorter.setSortsOnUpdates(true);
        table.setRowSorter(sorter);
        table.removeColumn(table.getColumnModel().getColumn(3));
        sortMetric(SortOrder.DESCENDING);
        fitHeader(1, "Share %");
        fitHeader(2, "Status");
        table.setDefaultRenderer(Double.class, new DefaultTableCellRenderer()
        {
            @Override protected void setValue(Object value)
            {
                setHorizontalAlignment(SwingConstants.RIGHT);
                setText(value == null ? "—" : String.format(Locale.ROOT, "%.1f", (Double) value));
            }
        });
        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer()
        {
            @Override protected void setValue(Object value)
            {
                setHorizontalAlignment(SwingConstants.CENTER);
                setText(Boolean.TRUE.equals(value) ? "On" : "Off");
            }
        });
        JScrollPane list = new JScrollPane(table);
        add(list, BorderLayout.CENTER);

        message.setVisible(false);
        add(message, BorderLayout.SOUTH);
        metric.addActionListener(e ->
        {
            table.getColumnModel().getColumn(1).setHeaderValue(metric.getSelectedItem() == Metric.SLOW_FRAMES ? "Δ pp" : "Share %");
            table.getTableHeader().repaint();
            sortMetric(SortOrder.DESCENDING);
            describeMetric();
            refreshMetrics();
        });

        setHideZero(hideInitially);
        describeMetric();
    }

    private void describeMetric()
    {
        String meaning = metric.getSelectedItem() == Metric.SLOW_FRAMES
            ? "Over five minutes, share during slow frames (50 ms or longer) minus share during normal frames, in percentage points."
            : "Percentage of all main-thread samples attributed to this plugin. Includes time spent waiting.";
        metric.setToolTipText(meaning);
        table.getTableHeader().setToolTipText(meaning);
    }

    private void fitHeader(int col, String text)
    {
        TableColumn column = table.getColumnModel().getColumn(col);
        Component renderer = table.getTableHeader().getDefaultRenderer()
            .getTableCellRendererComponent(table, text, false, false, -1, col);
        int width = Math.max(renderer.getPreferredSize().width,
            table.getTableHeader().getFontMetrics(table.getTableHeader().getFont()).stringWidth(text) + 26);
        column.setMinWidth(width);
        column.setPreferredWidth(width);
        column.setMaxWidth(width);
    }

    void setHideZero(boolean hide)
    {
        hideZero.setSelected(hide);
        sorter.setRowFilter(hide ? new RowFilter<Model, Integer>()
        {
            @Override public boolean include(Entry<? extends Model, ? extends Integer> row)
            {
                Double value = (Double) row.getValue(1);
                return value != null && Math.abs(value) >= 0.05;
            }
        } : null);
    }

    private void sortMetric(SortOrder order)
    {
        List<RowSorter.SortKey> keys = new ArrayList<>();
        if (metric.getSelectedItem() != Metric.SESSION) { keys.add(new RowSorter.SortKey(3, SortOrder.DESCENDING)); }
        keys.add(new RowSorter.SortKey(1, order));
        keys.add(new RowSorter.SortKey(0, SortOrder.ASCENDING));
        sorter.setSortKeys(keys);
    }

    private static JTextArea textArea()
    {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    private int selectedId()
    {
        int selected = table.getSelectedRow();
        return selected < 0 ? -1 : rows.get(table.convertRowIndexToModel(selected)).id;
    }

    void setRoster(List<ProfilerPlugin.Entry> entries)
    {
        int selected = selectedId();
        table.clearSelection();
        rows.clear();
        rows.addAll(entries);
        refresh(selected);
    }

    void update(SampleHistory.Snapshot latest)
    {
        snapshot = latest;
        ProfilerPlugin.Entry candidate = null;
        double highest = 0;
        for (ProfilerPlugin.Entry entry : rows)
        {
            if (!entry.active) { continue; }
            Double lift = latest.slowDifference(latest.stats.getOrDefault(entry.id, EMPTY));
            if (lift != null && lift > highest)
            {
                candidate = entry;
                highest = lift;
            }
        }
        if (candidate == null) { suggestion.setText(""); }
        else
        {
            SampleHistory.Stats stats = latest.stats.get(candidate.id);
            String label = stats.onlyGuesses ? "Name guess in slow frames: " : "More frequent in slow frames: ";
            suggestion.setText(label + candidate.name);
        }
        suggestion.setVisible(candidate != null);
        refreshMetrics();
    }

    void notice(String text)
    {
        message.setText(text);
        message.setVisible(true);
    }

    private void refreshMetrics()
    {
        if (!rows.isEmpty()) { model.fireTableRowsUpdated(0, rows.size() - 1); }
    }

    private void refresh(int selected)
    {
        model.fireTableDataChanged();
        if (selected >= 0)
        {
            for (int i = 0; i < rows.size(); i++)
            {
                if (rows.get(i).id == selected)
                {
                    int view = table.convertRowIndexToView(i);
                    if (view >= 0) { table.setRowSelectionInterval(view, view); }
                    break;
                }
            }
        }
    }

    private final class Model extends AbstractTableModel
    {
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 4; }
        @Override public String getColumnName(int col) { return col == 0 ? "Plugin" : col == 1 ? "Share %" : col == 2 ? "Status" : "Running"; }
        @Override public Class<?> getColumnClass(int col) { return col == 0 ? String.class : col == 1 ? Double.class : Boolean.class; }
        @Override public Object getValueAt(int row, int col)
        {
            ProfilerPlugin.Entry e = rows.get(row);
            if (col == 0) { return e.name; }
            if (col == 2) { return e.enabled; }
            if (col == 3) { return e.active; }
            SampleHistory.Stats s = snapshot.stats.getOrDefault(e.id, EMPTY);
            switch ((Metric) metric.getSelectedItem())
            {
                case SESSION: return snapshot.total == 0 ? null : 100.0 * snapshot.session.getOrDefault(e.id, 0L) / snapshot.total;
                case SLOW_FRAMES: return snapshot.slowDifference(s);
                default: return snapshot.liveSamples == 0 ? null : 100.0 * s.liveSamples / snapshot.liveSamples;
            }
        }
    }
}
