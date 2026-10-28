package com.example.holo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class HologramPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private static final TextColor BLUE = TextColor.color(0x00a2ff);
    private static final NamedTextColor WHITE = NamedTextColor.WHITE;
    private static final NamedTextColor RED = NamedTextColor.RED;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final List<String> SUBS =
            List.of("create", "delete", "list", "settext", "addline", "move", "offset", "scale", "tp");

    private static final class Holo {
        String id;
        String world;
        double x, y, z;
        UUID uuid;
        float scale = 1f;
        List<String> lines = new ArrayList<>();
    }

    private final Map<String, Holo> holos = new LinkedHashMap<>();
    private NamespacedKey key;
    private File file;

    // ----------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        key = new NamespacedKey(this, "hologram");
        file = new File(getDataFolder(), "holograms.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("hologram").setExecutor(this);
        getCommand("hologram").setTabCompleter(this);

        // Re-create any hologram whose entity is missing in chunks that are already loaded.
        getServer().getScheduler().runTask(this, () -> {
            for (Holo h : holos.values()) {
                World w = Bukkit.getWorld(h.world);
                if (w != null && w.isChunkLoaded(chunkCoord(h.x), chunkCoord(h.z))) ensure(h);
            }
        });
    }

    /** When a chunk's entities load, bring back any hologram whose entity went missing. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        Chunk ch = e.getChunk();
        for (Holo h : holos.values()) {
            if (!h.world.equals(ch.getWorld().getName())) continue;
            if (chunkCoord(h.x) != ch.getX() || chunkCoord(h.z) != ch.getZ()) continue;
            getServer().getScheduler().runTask(this, () -> ensure(h));
        }
    }

    private static int chunkCoord(double v) {
        return ((int) Math.floor(v)) >> 4;
    }

    // ----------------------------------------------------------------- storage

    private void load() {
        holos.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("holograms");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            Holo h = new Holo();
            h.id = id;
            h.world = s.getString("world", "world");
            h.x = s.getDouble("x");
            h.y = s.getDouble("y");
            h.z = s.getDouble("z");
            h.scale = (float) s.getDouble("scale", 1.0);
            h.lines = new ArrayList<>(s.getStringList("lines"));
            String u = s.getString("uuid");
            if (u != null) {
                try {
                    h.uuid = UUID.fromString(u);
                } catch (IllegalArgumentException ignored) {
                }
            }
            holos.put(id.toLowerCase(), h);
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Holo h : holos.values()) {
            String b = "holograms." + h.id;
            yml.set(b + ".world", h.world);
            yml.set(b + ".x", h.x);
            yml.set(b + ".y", h.y);
            yml.set(b + ".z", h.z);
            yml.set(b + ".scale", h.scale);
            yml.set(b + ".lines", h.lines);
            if (h.uuid != null) yml.set(b + ".uuid", h.uuid.toString());
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            getLogger().warning("Could not save holograms.yml: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------- entities

    private Component buildText(Holo h) {
        List<Component> parts = new ArrayList<>();
        for (String line : h.lines) parts.add(LEGACY.deserialize(line));
        return Component.join(JoinConfiguration.newlines(), parts);
    }

    private TextDisplay find(Holo h) {
        World w = Bukkit.getWorld(h.world);
        if (w == null) return null;
        Location l = new Location(w, h.x, h.y, h.z);
        w.getChunkAt(l); // make sure the chunk is loaded
        if (h.uuid != null) {
            Entity e = w.getEntity(h.uuid);
            if (e instanceof TextDisplay td) return td;
        }
        for (TextDisplay td : w.getNearbyEntitiesByType(TextDisplay.class, l, 4)) {
            String id = td.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            if (h.id.equalsIgnoreCase(id)) {
                h.uuid = td.getUniqueId();
                return td;
            }
        }
        return null;
    }

    private void apply(TextDisplay td, Holo h) {
        td.setBillboard(Display.Billboard.CENTER); // always turns to face the viewer
        td.text(buildText(h));
        td.setAlignment(TextDisplay.TextAlignment.CENTER);
        td.setShadowed(getConfig().getBoolean("text-shadow", true));
        td.setSeeThrough(false);
        if (getConfig().getBoolean("background", false)) {
            td.setDefaultBackground(true);
        } else {
            td.setDefaultBackground(false);
            td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        }
        td.setPersistent(true);
        td.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                new Vector3f(h.scale, h.scale, h.scale), new AxisAngle4f()));
    }

    private void spawn(Holo h) {
        World w = Bukkit.getWorld(h.world);
        if (w == null) return;
        TextDisplay td = w.spawn(new Location(w, h.x, h.y, h.z), TextDisplay.class, d -> {
            d.getPersistentDataContainer().set(key, PersistentDataType.STRING, h.id);
            apply(d, h);
        });
        h.uuid = td.getUniqueId();
    }

    /** Makes sure the entity exists and matches the stored data (position, text, scale). */
    private void ensure(Holo h) {
        TextDisplay td = find(h);
        if (td == null) {
            spawn(h);
        } else {
            apply(td, h);
            td.teleport(new Location(Bukkit.getWorld(h.world), h.x, h.y, h.z));
        }
        save();
    }

    private void removeEntity(Holo h) {
        TextDisplay td = find(h);
        if (td != null) td.remove();
    }

    // ----------------------------------------------------------------- commands

    private void msg(CommandSender s, Component c) {
        s.sendMessage(c);
    }

    private void ok(CommandSender s, String text) {
        msg(s, Component.text(text, WHITE));
    }

    private void err(CommandSender s, String text) {
        msg(s, Component.text(text, RED));
    }

    private void usage(CommandSender s) {
        err(s, "Usage:");
        String[] lines = {
                "/holo create <id> <text>",
                "/holo delete <id>",
                "/holo list",
                "/holo settext <id> <text>",
                "/holo addline <id> <text>",
                "/holo move <id>",
                "/holo offset <id> <up/down>",
                "/holo scale <id> <size>",
                "/holo tp <id>",
                "Use | for a new line and & for colours (hex: &#00a2ff)"
        };
        for (String l : lines) msg(s, Component.text(l, BLUE));
    }

    private static String join(String[] a, int from) {
        return String.join(" ", Arrays.copyOfRange(a, from, a.length));
    }

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split("\\|")) out.add(part.strip());
        return out;
    }

    private Holo get(CommandSender s, String id) {
        Holo h = holos.get(id.toLowerCase());
        if (h == null) err(s, "No hologram named '" + id + "'. Use /holo list.");
        return h;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("hologram.admin")) {
            err(s, "You don't have permission.");
            return true;
        }
        if (a.length == 0) {
            usage(s);
            return true;
        }
        String sub = a[0].toLowerCase();
        switch (sub) {
            case "create" -> {
                if (!(s instanceof Player p)) {
                    err(s, "Players only.");
                    return true;
                }
                if (a.length < 3) {
                    err(s, "Usage: /holo create <id> <text>");
                    return true;
                }
                if (!ID.matcher(a[1]).matches()) {
                    err(s, "The id may only contain letters, numbers, _ and - (max 32).");
                    return true;
                }
                if (holos.containsKey(a[1].toLowerCase())) {
                    err(s, "A hologram with that id already exists.");
                    return true;
                }
                Holo h = new Holo();
                h.id = a[1];
                h.world = p.getWorld().getName();
                h.x = p.getLocation().getX();
                h.y = p.getLocation().getY() + getConfig().getDouble("y-offset", 2.2);
                h.z = p.getLocation().getZ();
                h.lines = splitLines(join(a, 2));
                holos.put(h.id.toLowerCase(), h);
                ensure(h);
                ok(s, "Created hologram '" + h.id + "'. Adjust the height with /holo offset " + h.id + " <up/down>.");
            }
            case "delete" -> {
                if (a.length < 2) {
                    err(s, "Usage: /holo delete <id>");
                    return true;
                }
                Holo h = get(s, a[1]);
                if (h == null) return true;
                removeEntity(h);
                holos.remove(h.id.toLowerCase());
                save();
                ok(s, "Deleted hologram '" + h.id + "'.");
            }
            case "list" -> {
                if (holos.isEmpty()) {
                    ok(s, "No holograms yet.");
                    return true;
                }
                for (Holo h : holos.values()) {
                    msg(s, Component.text(h.id, BLUE).append(Component.text(
                            "  " + h.world + " " + Math.round(h.x) + ", " + Math.round(h.y) + ", " + Math.round(h.z), WHITE)));
                }
            }
            case "settext", "addline" -> {
                if (a.length < 3) {
                    err(s, "Usage: /holo " + sub + " <id> <text>");
                    return true;
                }
                Holo h = get(s, a[1]);
                if (h == null) return true;
                List<String> text = splitLines(join(a, 2));
                if (sub.equals("settext")) h.lines = text;
                else h.lines.addAll(text);
                ensure(h);
                ok(s, "Updated hologram '" + h.id + "'.");
            }
            case "move" -> {
                if (!(s instanceof Player p)) {
                    err(s, "Players only.");
                    return true;
                }
                if (a.length < 2) {
                    err(s, "Usage: /holo move <id>");
                    return true;
                }
                Holo h = get(s, a[1]);
                if (h == null) return true;
                h.world = p.getWorld().getName();
                h.x = p.getLocation().getX();
                h.y = p.getLocation().getY() + getConfig().getDouble("y-offset", 2.2);
                h.z = p.getLocation().getZ();
                ensure(h);
                ok(s, "Moved hologram '" + h.id + "' to your position.");
            }
            case "offset" -> {
                if (a.length < 3) {
                    err(s, "Usage: /holo offset <id> <up/down> (e.g. 0.3 or -0.3)");
                    return true;
                }
                Holo h = get(s, a[1]);
                if (h == null) return true;
                try {
                    h.y += Double.parseDouble(a[2]);
                } catch (NumberFormatException ex) {
                    err(s, "That's not a number.");
                    return true;
                }
                ensure(h);
                ok(s, "Moved hologram '" + h.id + "' vertically by " + a[2] + ".");
            }
            case "scale" -> {
                if (a.length < 3) {
                    err(s, "Usage: /holo scale <id> <size> (1 = normal)");
                    return true;
                }
                Holo h = get(s, a[1]);
                if (h == null) return true;
                try {
                    float v = Float.parseFloat(a[2]);
                    if (v < 0.1f || v > 10f) {
                        err(s, "Size must be between 0.1 and 10.");
                        return true;
                    }
                    h.scale = v;
                } catch (NumberFormatException ex) {
                    err(s, "That's not a number.");
                    return true;
                }
                ensure(h);
                ok(s, "Scale set to " + a[2] + ".");
            }
            case "tp" -> {
                if (!(s instanceof Player p)) {
                    err(s, "Players only.");
                    return true;
                }
                if (a.length < 2) {
                    err(s, "Usage: /holo tp <id>");
                    return true;
                }
                Holo h = get(s, a[1]);
                if (h == null) return true;
                World w = Bukkit.getWorld(h.world);
                if (w == null) {
                    err(s, "That world isn't loaded.");
                    return true;
                }
                p.teleport(new Location(w, h.x, h.y - 1.5, h.z));
            }
            default -> usage(s);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("hologram.admin")) return List.of();
        if (a.length == 1) {
            String t = a[0].toLowerCase();
            return SUBS.stream().filter(x -> x.startsWith(t)).toList();
        }
        if (a.length == 2 && List.of("delete", "settext", "addline", "move", "offset", "scale", "tp")
                .contains(a[0].toLowerCase())) {
            String t = a[1].toLowerCase();
            return holos.values().stream().map(h -> h.id).filter(x -> x.toLowerCase().startsWith(t)).toList();
        }
        return List.of();
    }
}
