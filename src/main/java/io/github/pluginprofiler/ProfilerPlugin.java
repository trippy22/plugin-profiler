package io.github.pluginprofiler;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ExternalPluginsChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.config.ConfigPlugin;
import net.runelite.client.plugins.jagexauth.JagexAuthPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(name = "Plugin Profiler", description = "Rank sampled main-thread activity and toggle RuneLite plugins",
    tags = {"performance", "diagnostics", "profiling"})
public class ProfilerPlugin extends Plugin
{
    private static final Logger LOG = LoggerFactory.getLogger(ProfilerPlugin.class);
    @Inject private Client client;
    @Inject private ClientToolbar toolbar;
    @Inject private DrawManager drawManager;
    @Inject private PluginManager plugins;
    @Inject private ConfigManager configs;
    @Inject private ProfilerConfig settings;
    @Inject private ScheduledExecutorService executor;

    static final class Entry
    {
        final int id;
        final String name;
        final boolean enabled;
        final boolean active;
        final boolean hidden;

        Entry(int id, String name, boolean enabled, boolean active, boolean hidden)
        {
            this.id = id;
            this.name = name;
            this.enabled = enabled;
            this.active = active;
            this.hidden = hidden;
        }
    }

    static final class Session
    {
        final SampleHistory history = new SampleHistory();
        final Runnable draw = () -> history.frames.finish(System.nanoTime());
        final Map<Plugin, Integer> ids = new IdentityHashMap<>();
        final Map<Plugin, String> groups = new IdentityHashMap<>();
        final Map<Integer, Plugin> instances = new HashMap<>();
        final AtomicBoolean snapshotQueued = new AtomicBoolean();
        private final AtomicBoolean refreshOnShow = new AtomicBoolean();
        HierarchyListener visibilityListener;
        volatile boolean panelVisible;
        volatile OwnershipIndex index;
        volatile boolean refreshQueued;
        ScheduledFuture<?> future;
        private volatile long refreshInterval;
        private long lastUpdate;
        int nextId = 1;

        Session(int refreshSeconds) { setRefreshInterval(refreshSeconds); }

        void setRefreshInterval(int seconds)
        {
            refreshInterval = TimeUnit.SECONDS.toNanos(Math.max(1, Math.min(60, seconds)));
        }

        void setPanelVisible(boolean visible)
        {
            if (visible) { refreshOnShow.set(true); }
            panelVisible = visible;
        }

        boolean beginPanelUpdate(long now)
        {
            if (!panelVisible || (!refreshOnShow.get() && now - lastUpdate < refreshInterval)) { return false; }
            if (!snapshotQueued.compareAndSet(false, true)) { return false; }
            refreshOnShow.set(false);
            lastUpdate = now;
            return true;
        }
    }

    private volatile Session current;
    private ProfilerPanel panel;
    private NavigationButton nav;

    @Provides
    ProfilerConfig provideConfig(ConfigManager manager) { return manager.getConfig(ProfilerConfig.class); }

    @Override
    protected void startUp()
    {
        Session session = new Session(settings.panelRefreshSeconds());
        panel = new ProfilerPanel(this::toggle, settings.hideZeroUsage(),
            hidden -> configs.setConfiguration(ProfilerConfig.GROUP, ProfilerConfig.HIDE_ZERO, hidden));
        ProfilerPanel view = panel;
        session.visibilityListener = event ->
        {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) { session.setPanelVisible(view.isShowing()); }
        };
        view.addHierarchyListener(session.visibilityListener);
        nav = NavigationButton.builder().tooltip("Plugin Profiler").icon(icon()).priority(7).panel(panel).build();
        current = session;
        rebuild(session);
        toolbar.addNavigation(nav);
        drawManager.registerEveryFrameListener(session.draw);
        schedule(session);
    }

    @Override
    protected void shutDown()
    {
        Session session = current;
        current = null;
        if (session != null)
        {
            synchronized (session)
            {
                if (session.future != null) { session.future.cancel(false); }
            }
            drawManager.unregisterEveryFrameListener(session.draw);
            panel.removeHierarchyListener(session.visibilityListener);
        }
        if (nav != null) { toolbar.removeNavigation(nav); }
        nav = null;
        panel = null;
    }

    @Subscribe public void onPluginChanged(PluginChanged event) { queueRoster(); }
    @Subscribe public void onExternalPluginsChanged(ExternalPluginsChanged event) { queueRoster(); }
    @Subscribe public void onProfileChanged(ProfileChanged event) { queueRoster(); refreshSettings(); }

    @Subscribe public void onConfigChanged(ConfigChanged event)
    {
        if (ProfilerConfig.GROUP.equals(event.getGroup())
            && (ProfilerConfig.HIDE_ZERO.equals(event.getKey()) || ProfilerConfig.REFRESH_SECONDS.equals(event.getKey())))
        {
            refreshSettings();
        }
    }

    private void refreshSettings()
    {
        SwingUtilities.invokeLater(() ->
        {
            Session session = current;
            if (session == null) { return; }
            session.setRefreshInterval(settings.panelRefreshSeconds());
            panel.setHideZero(settings.hideZeroUsage());
        });
    }

    private void queueRoster()
    {
        Session session = current;
        if (session == null) { return; }
        synchronized (session)
        {
            if (session.refreshQueued) { return; }
            session.refreshQueued = true;
        }
        SwingUtilities.invokeLater(() ->
        {
            session.refreshQueued = false;
            if (current == session) { rebuild(session); }
        });
    }

    private void rebuild(Session session)
    {
        List<Plugin> loaded = new ArrayList<>(plugins.getPlugins());
        session.ids.keySet().retainAll(loaded);
        session.groups.keySet().retainAll(loaded);
        session.instances.clear();
        List<OwnershipIndex.Owner> owners = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        for (Plugin plugin : loaded)
        {
            int id = session.ids.computeIfAbsent(plugin, ignored -> session.nextId++);
            String name = plugin.getName();
            boolean active = plugins.isPluginActive(plugin);
            if (!session.groups.containsKey(plugin))
            {
                session.groups.put(plugin, configGroup(plugin));
            }
            owners.add(new OwnershipIndex.Owner(id, name, session.groups.get(plugin), active));
            entries.add(new Entry(id, name, plugins.isPluginEnabled(plugin), active, protectedPlugin(plugin)));
            session.instances.put(id, plugin);
        }
        entries.sort(Comparator.comparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
        session.index = new OwnershipIndex(owners);
        panel.setRoster(entries);
    }

    private String configGroup(Plugin plugin)
    {
        try
        {
            Config config = plugins.getPluginConfigProxy(plugin);
            if (config != null) { return configs.getConfigDescriptor(config).getGroup().value(); }
        }
        catch (RuntimeException ex)
        {
            LOG.debug("Could not read config alias for {}", plugin.getName(), ex);
        }
        return null;
    }

    private static boolean protectedPlugin(Plugin plugin)
    {
        return plugin instanceof ConfigPlugin || plugin instanceof JagexAuthPlugin;
    }

    private void toggle(int id, boolean enabled)
    {
        Session session = current;
        if (session == null) { return; }
        Plugin plugin = session.instances.get(id);
        if (plugin == null || !plugins.getPlugins().contains(plugin) || protectedPlugin(plugin)) { return; }
        if (enabled)
        {
            String conflicts = plugins.conflictsForPlugin(plugin).stream().filter(plugins::isPluginEnabled)
                .map(Plugin::getName).collect(Collectors.joining(", "));
            if (!conflicts.isEmpty())
            {
                String prompt = "Enabling " + plugin.getName() + " also disables " + conflicts + ". Continue?";
                if (JOptionPane.showConfirmDialog(panel, prompt, "Switch plugins",
                    JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return; }
            }
        }
        boolean previous = plugins.isPluginEnabled(plugin);
        try
        {
            plugins.setPluginEnabled(plugin, enabled);
            if (enabled) { plugins.startPlugin(plugin); }
            else { plugins.stopPlugin(plugin); }
        }
        catch (PluginInstantiationException | RuntimeException ex)
        {
            if (enabled && !plugins.isPluginActive(plugin)) { plugins.setPluginEnabled(plugin, previous); }
            if (current == session) { panel.notice("Switch failed for " + plugin.getName() + ". See client log."); }
            LOG.warn("Could not switch {}", plugin.getName(), ex);
        }
        if (current == session) { queueRoster(); }
    }

    private void schedule(Session session)
    {
        synchronized (session)
        {
            if (current == session)
            {
                long delay = ThreadLocalRandom.current().nextLong(80, 141);
                session.future = executor.schedule(() -> sample(session), delay, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void sample(Session session)
    {
        if (current != session) { return; }
        try
        {
            OwnershipIndex index = session.index;
            Thread thread = client.getClientThread();
            long start = System.nanoTime();
            StackTraceElement[] stack = thread == null ? null : thread.getStackTrace();
            long now = System.nanoTime();
            if (stack != null && stack.length != 0 && index == session.index)
            {
                session.history.record(start + (now - start) / 2, index, stack);
            }
            else
            {
                // A missing stack must not prevent old method paths from expiring.
                session.history.advance(now);
            }
            if (session.beginPanelUpdate(now))
            {
                SampleHistory.Snapshot snapshot = session.history.snapshot(now);
                SwingUtilities.invokeLater(() ->
                {
                    try
                    {
                        if (current == session && session.panelVisible) { panel.update(snapshot); }
                    }
                    finally { session.snapshotQueued.set(false); }
                });
            }
        }
        catch (RuntimeException ex)
        {
            session.history.clearWindow();
            LOG.warn("Main-thread sampling stopped", ex);
            SwingUtilities.invokeLater(() ->
            {
                if (current == session) { panel.notice("Sampling stopped. See the client log for details."); }
            });
            return;
        }
        schedule(session);
    }

    private static BufferedImage icon()
    {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(105, 205, 202));
        g.fillRect(1, 10, 3, 5);
        g.fillRect(6, 6, 3, 9);
        g.fillRect(11, 1, 3, 14);
        g.dispose();
        return image;
    }
}
