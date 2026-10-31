package dev.reed.spawners;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import static dev.reed.spawners.SpawnerPlusPlus.MAX_AMOUNT;
import static dev.reed.spawners.SpawnerPlusPlus.parseType;
import static dev.reed.spawners.SpawnerPlusPlus.spawnable;

final class SpawnerCommand implements TabExecutor {
    private static final String ADMIN = "spawnerplusplus.admin";
    private static final List<String> EDITS = List.of("add", "remove", "set");
    private final SpawnerPlusPlus plugin;

    SpawnerCommand(SpawnerPlusPlus plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            plugin.text.reply(sender, "help");
            if (sender.hasPermission(ADMIN)) sendUsage(sender);
            return true;
        }
        if (!sender.hasPermission(ADMIN)) {
            plugin.text.error(sender, "no-permission");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (EDITS.contains(action)) {
            if (!(sender instanceof Player p)) {
                plugin.text.error(sender, "players-only");
                return true;
            }
            if (args.length > 2 || action.equals("set") && args.length != 2) {
                plugin.text.reply(sender, "usage", "/spawners " + action + (action.equals("set") ? " <amount>" : " [amount]"));
                return true;
            }
            int minimum = action.equals("set") ? 0 : 1;
            int amount = amount(args.length == 2 ? args[1] : "1", minimum);
            if (amount < 0) {
                plugin.text.error(sender, "invalid-amount", Integer.toString(minimum), Integer.toString(MAX_AMOUNT));
                return true;
            }
            editSpawner(p, action, amount);
            return true;
        }
        if (args.length < 3 || args.length > 4 || !args[0].equalsIgnoreCase("give")) {
            sendUsage(sender);
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) {
            plugin.text.error(sender, "player-offline");
            return true;
        }
        var type = parseType(args[2].toUpperCase(Locale.ROOT));
        int amount = amount(args.length == 4 ? args[3] : "1", 1);
        if (!spawnable(type) || amount < 0) {
            plugin.text.error(sender, "invalid-give", Integer.toString(MAX_AMOUNT));
            return true;
        }
        int remaining = amount;
        while (remaining > 0) {
            int count = Math.min(remaining, Material.SPAWNER.getMaxStackSize());
            for (ItemStack left : target.getInventory().addItem(plugin.item(type, count)).values())
                target.getWorld().dropItem(target.getLocation(), left);
            remaining -= count;
        }
        plugin.text.reply(sender, "given", Integer.toString(amount), plugin.label(type), target.getName());
        return true;
    }

    private int amount(String value, int minimum) {
        int amount;
        try {
            amount = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
        return amount >= minimum && amount <= MAX_AMOUNT ? amount : -1;
    }

    private void sendUsage(CommandSender sender) {
        plugin.text.reply(sender, "usage", "/spawners give <player> <type> [amount]");
        plugin.text.reply(sender, "usage", "/spawners add [amount] | remove [amount] | set <amount>");
    }

    private void editSpawner(Player p, String action, int amount) {
        Block block = p.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof CreatureSpawner state)) {
            plugin.text.error(p, "look-at-spawner");
            return;
        }
        if (state.getSpawnedType() == null) {
            plugin.text.error(p, "unknown-type");
            return;
        }
        var spawner = plugin.spawners.get(block);
        if (spawner == null)
            spawner = new MoneySpawner(block, state.getSpawnedType(), 1, plugin.maxStack, 0, 0);
        if (spawner.upgrading) {
            plugin.text.error(p, "spawner-busy");
            return;
        }
        int count = switch (action) {
            case "add" -> spawner.count + amount;
            case "remove" -> spawner.count - amount;
            default -> amount;
        };
        if (count < 0) {
            plugin.text.error(p, "not-enough-spawners", Integer.toString(spawner.count));
            return;
        }
        if (count > spawner.capacity) {
            plugin.text.error(p, "capacity-limit", Integer.toString(spawner.capacity));
            return;
        }
        if (count == 0) {
            if (spawner.balance > 0 && !plugin.collect(p, spawner)) return;
            block.setType(Material.AIR);
            plugin.spawners.remove(block, spawner);
            plugin.text.reply(p, "spawner-removed");
            return;
        }
        if (!plugin.save(spawner, count)) {
            plugin.text.error(p, "save-failed");
            return;
        }
        plugin.spawners.put(block, spawner);
        plugin.text.reply(p, "count-set", plugin.label(spawner.type), Integer.toString(count), Integer.toString(spawner.capacity));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0 || !sender.hasPermission(ADMIN)) return List.of();
        String action = args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        switch (args.length) {
            case 1 -> {
                options.add("give");
                options.addAll(EDITS);
            }
            case 2 -> {
                if (action.equals("give")) {
                    for (Player p : plugin.getServer().getOnlinePlayers()) options.add(p.getName());
                } else if (EDITS.contains(action)) {
                    options.addAll(List.of("1", "16", "64"));
                    if (action.equals("set")) options.add("0");
                }
            }
            case 3 -> {
                if (action.equals("give")) {
                    for (EntityType type : EntityType.values()) {
                        if (spawnable(type)) options.add(type.name());
                    }
                }
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
        options.sort(String::compareTo);
        return options;
    }
}
