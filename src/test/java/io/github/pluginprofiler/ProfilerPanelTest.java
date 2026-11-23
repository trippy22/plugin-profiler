package io.github.pluginprofiler;

import java.awt.Component;
import java.awt.Container;
import java.awt.BorderLayout;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfilerPanelTest
{
    @Test public void selectionFollowsTheSamePluginThroughLiveSortAndRosterRefresh() throws Exception
    {
        SwingUtilities.invokeAndWait(() ->
        {
            ProfilerPanel panel = new ProfilerPanel(false, ignored -> {});
            ProfilerPlugin.Entry alpha = new ProfilerPlugin.Entry(1, "Alpha", true, true);
            ProfilerPlugin.Entry beta = new ProfilerPlugin.Entry(2, "Beta", true, true);
            panel.setRoster(List.of(alpha, beta));
            SampleHistory history = new SampleHistory();
            OwnershipIndex roster = SampleHistoryTest.roster();
            history.record(1, roster, SampleHistoryTest.hit(1));
            panel.update(history.snapshot(2));
            List<Component> controls = new ArrayList<>();
            collect(panel, controls);
            JTable table = (JTable) controls.stream().filter(x -> x instanceof JTable).findFirst().orElseThrow();
            JComboBox<?> mode = (JComboBox<?>) controls.stream().filter(x -> x instanceof JComboBox).findFirst().orElseThrow();
            assertEquals("Last 60 sec", mode.getSelectedItem().toString());
            assertEquals("Alpha", table.getValueAt(0, 0));
            table.setRowSelectionInterval(0, 0);
            history.record(3, roster, SampleHistoryTest.hit(2));
            history.record(4, roster, SampleHistoryTest.hit(2));
            panel.update(history.snapshot(5));
            assertEquals("Beta", table.getValueAt(0, 0));
            assertEquals("Alpha", table.getValueAt(table.getSelectedRow(), 0));
            panel.setRoster(List.of(beta, alpha));
            mode.setSelectedIndex(2);
            assertEquals("Session", mode.getSelectedItem().toString());
            assertEquals("Alpha", table.getValueAt(table.getSelectedRow(), 0));
            mode.setSelectedIndex(1);
            assertEquals("Slow-frame lift · 5 min", mode.getSelectedItem().toString());
            assertEquals("Δ pp", table.getColumnModel().getColumn(1).getHeaderValue());
            assertNull(table.getValueAt(0, 1));
            panel.setRoster(List.of(beta));
            assertEquals(-1, table.getSelectedRow());
        });
    }

    @Test public void statusIsReadOnlyAndSortsOnHeaderClicksAndRosterChanges() throws Exception
    {
        SwingUtilities.invokeAndWait(() ->
        {
            ProfilerPanel panel = new ProfilerPanel(false, ignored -> {});
            // An enabled plugin can be inactive. The visible column follows its configured state.
            panel.setRoster(List.of(new ProfilerPlugin.Entry(1, "Alpha", true, false),
                new ProfilerPlugin.Entry(2, "Beta", false, false)));
            List<Component> controls = new ArrayList<>();
            collect(panel, controls);
            JTable table = (JTable) controls.stream().filter(c -> c instanceof JTable).findFirst().orElseThrow();
            table.setSize(225, 200);
            table.doLayout();
            table.getTableHeader().setSize(table.getWidth(), 20);
            int x = table.getTableHeader().getHeaderRect(2).x + 5;
            assertEquals("Status", table.getColumnName(2));
            assertEquals("On", statusText(table, 0));
            assertEquals("Off", statusText(table, 1));
            click(table, x);
            assertFalse(table.editCellAt(0, 2));
            assertEquals("On", statusText(table, 0));

            click(table.getTableHeader(), x);
            assertEquals("Beta", table.getValueAt(0, 0));
            click(table.getTableHeader(), x);
            assertEquals("Alpha", table.getValueAt(0, 0));
            panel.setRoster(List.of(new ProfilerPlugin.Entry(1, "Alpha", false, false),
                new ProfilerPlugin.Entry(2, "Beta", true, true)));
            assertEquals("Beta", table.getValueAt(0, 0));
            assertEquals("On", statusText(table, 0));
            assertEquals("Off", statusText(table, 1));
        });
    }

    private static String statusText(JTable table, int row)
    {
        JLabel label = (JLabel) table.prepareRenderer(table.getCellRenderer(row, 2), row, 2);
        return label.getText();
    }

    @Test public void filterHasItsOwnLineAndSelectingRowsKeepsFullHeight() throws Exception
    {
        SwingUtilities.invokeAndWait(() ->
        {
            boolean initial = new ProfilerConfig() {}.hideZeroUsage();
            assertFalse(initial);
            List<Boolean> persisted = new ArrayList<>();
            ProfilerPanel panel = new ProfilerPanel(initial, persisted::add);
            panel.setSize(225, 600);
            ProfilerPlugin.Entry alpha = new ProfilerPlugin.Entry(1, "Alpha", true, true);
            ProfilerPlugin.Entry beta = new ProfilerPlugin.Entry(2, "Beta", true, true);
            panel.setRoster(List.of(alpha, beta));
            List<Component> controls = new ArrayList<>();
            collect(panel, controls);
            JTable table = (JTable) controls.stream().filter(c -> c instanceof JTable).findFirst().orElseThrow();
            JCheckBox hide = (JCheckBox) controls.stream().filter(c -> c instanceof JCheckBox && "Hide 0% Usage".equals(((JCheckBox)c).getText())).findFirst().orElseThrow();
            JComboBox<?> mode = (JComboBox<?>) controls.stream().filter(c -> c instanceof JComboBox).findFirst().orElseThrow();
            JScrollPane list = (JScrollPane)((BorderLayout)panel.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            panel.doLayout();
            hide.getParent().doLayout();
            mode.getParent().doLayout();
            assertTrue(SwingUtilities.convertPoint(hide, 0, 0, panel).y >=
                SwingUtilities.convertPoint(mode, 0, mode.getHeight(), panel).y);
            int full = list.getHeight();
            assertTrue(full > panel.getHeight() * .8);
            assertEquals(2, table.getRowCount());
            assertTrue(table.getColumnModel().getColumn(1).getMinWidth() >
                table.getTableHeader().getFontMetrics(table.getTableHeader().getFont()).stringWidth("Share %") + 16);
            SampleHistory history = new SampleHistory();
            OwnershipIndex roster = SampleHistoryTest.roster();
            history.record(1, roster, SampleHistoryTest.hit(1));
            history.record(2, roster, SampleHistoryTest.hit(0));
            panel.update(history.snapshot(3));
            assertEquals(50.0, (Double) table.getValueAt(0, 1), 0.001);
            table.setSize(210, 200);
            table.doLayout();
            click(table, 5);
            panel.doLayout();
            assertEquals(full, list.getHeight());
            assertEquals("Alpha", table.getValueAt(table.getSelectedRow(), 0));
            assertFalse(controls.stream().anyMatch(c -> c instanceof JTextArea && ((JTextArea)c).getText().contains("demo.AlphaPlugin.work")));
            click(table, 5);
            panel.doLayout();
            assertEquals(full, list.getHeight());
            hide.doClick();
            assertEquals(1, table.getRowCount());
            hide.doClick();
            assertEquals(2, table.getRowCount());
            assertEquals(List.of(true, false), persisted);
            panel.setRoster(List.of(new ProfilerPlugin.Entry(1, "Alpha", false, false), beta));
            assertEquals("Beta", table.getValueAt(0, 0));
            table.getRowSorter().toggleSortOrder(1);
            table.getRowSorter().toggleSortOrder(1);
            assertEquals("Beta", table.getValueAt(0, 0));
            panel.update(history.snapshot(TimeUnit.SECONDS.toNanos(60) + 10));
            assertNull(table.getValueAt(0, 1));
        });
    }

    @Test public void panelRefreshIsThrottledOnlyWhileVisibleAndReopeningRequestsFreshData()
    {
        int seconds = new ProfilerConfig() {}.panelRefreshSeconds();
        assertEquals(5, seconds);
        ProfilerPlugin.Session session = new ProfilerPlugin.Session(seconds);
        long second = TimeUnit.SECONDS.toNanos(1);
        assertFalse(session.beginPanelUpdate(0));
        session.setPanelVisible(true);
        assertTrue(session.beginPanelUpdate(0));
        assertFalse(session.beginPanelUpdate(5 * second)); // UI still has an outstanding snapshot.
        session.snapshotQueued.set(false);
        assertFalse(session.beginPanelUpdate(5 * second - 1));
        assertTrue(session.beginPanelUpdate(5 * second));
        session.setPanelVisible(false);
        session.snapshotQueued.set(false);
        assertFalse(session.beginPanelUpdate(6 * second));
        session.setPanelVisible(true);
        assertTrue(session.beginPanelUpdate(6 * second));
        session.snapshotQueued.set(false);
        session.setRefreshInterval(0);
        assertFalse(session.beginPanelUpdate(7 * second - 1));
        assertTrue(session.beginPanelUpdate(7 * second));
        session.snapshotQueued.set(false);
        session.setRefreshInterval(100);
        assertFalse(session.beginPanelUpdate(67 * second - 1));
        assertTrue(session.beginPanelUpdate(67 * second));
    }

    private static void click(Component target, int x)
    {
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED, 1, 0, x, 6, 1, false, MouseEvent.BUTTON1));
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_RELEASED, 2, 0, x, 6, 1, false, MouseEvent.BUTTON1));
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_CLICKED, 3, 0, x, 6, 1, false, MouseEvent.BUTTON1));
    }

    private static void collect(Container parent, List<Component> controls)
    {
        for (Component child : parent.getComponents())
        {
            controls.add(child);
            if (child instanceof Container) { collect((Container) child, controls); }
        }
    }
}
