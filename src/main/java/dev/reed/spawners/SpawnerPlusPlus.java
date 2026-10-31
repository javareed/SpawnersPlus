package dev.reed.spawners;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class SpawnerPlusPlus extends JavaPlugin {
    static final int MAX_AMOUNT = 10_000;
    // cents per minute per spawner
    static final long MAX_RATE = 100_000_000;
    final Map<Block, MoneySpawner> spawners = new HashMap<>();
    final Map<EntityType, Long> rates = new HashMap<>();
    final NavigableMap<Integer, Long> upgrades = new TreeMap<>();
    NamespacedKey typeKey;
    NamespacedKey countKey;
    NamespacedKey balanceKey;
    NamespacedKey remainderKey;
    NamespacedKey capacityKey;
    Economy economy;
    SpawnerText text;
    int maxStack;
    long maxBalance;
    long defaultRate;
    private int seconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        typeKey = new NamespacedKey(this, "type");
        countKey = new NamespacedKey(this, "count");
        balanceKey = new NamespacedKey(this, "balance");
        remainderKey = new NamespacedKey(this, "remainder");
        capacityKey = new NamespacedKey(this, "capacity");
        try {
            text = new SpawnerText(getConfig());
            maxStack = getConfig().getInt("max-stack");
            maxBalance = cents(getConfig().getString("max-stored-money"));
            defaultRate = cents(getConfig().getString("default-income-per-minute"));
            if (maxStack < 1 || maxStack > MAX_AMOUNT || maxBalance <= 0 || defaultRate <= 0 || defaultRate > MAX_RATE)
                throw new IllegalArgumentException("Invalid stack limit or storage cap");
            var section = getConfig().getConfigurationSection("income-per-minute");
            if (section == null) throw new IllegalArgumentException("Missing income-per-minute");
            for (String name : section.getKeys(false)) {
                var type = parseType(name.toUpperCase(Locale.ROOT));
                long rate = cents(section.getString(name));
                if (!spawnable(type) || rate <= 0 || rate > MAX_RATE)
                    throw new IllegalArgumentException("Invalid rate for " + name);
                rates.put(type, rate);
            }
            var tiers = getConfig().getConfigurationSection("capacity-upgrades");
            if (tiers == null) throw new IllegalArgumentException("Missing capacity-upgrades");
            for (String key : tiers.getKeys(false)) {
                int capacity = Integer.parseInt(key);
                long cost = cents(tiers.getString(key));
                if (capacity < 1 || capacity > MAX_AMOUNT || cost <= 0)
                    throw new IllegalArgumentException("Invalid capacity upgrade: " + key);
                upgrades.put(capacity, cost);
            }
        } catch (IllegalArgumentException | ArithmeticException e) {
            getLogger().severe(e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(new SpawnerListener(this), this);
        var command = getCommand("spawners");
        var executor = new SpawnerCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        hookEconomy();
        // vault providers can register after onEnable
        getServer().getScheduler().runTask(this, () -> {
            if (!hookEconomy()) getLogger().warning("No Vault economy provider; earnings paused");
        });
        for (var world : getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) load(chunk.getTileEntities());
        }
        getServer().getScheduler().runTaskTimer(this, this::tick, 20, 20);
    }

    @Override
    public void onDisable() {
        for (MoneySpawner spawner : spawners.values()) save(spawner);
        spawners.clear();
    }

    private long cents(String value) {
        long amount = new BigDecimal(value).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        if (amount > 100000000000000L) throw new IllegalArgumentException("Money value too large");
        return amount;
    }

    boolean hookEconomy() {
        var provider = getServer().getServicesManager().getRegistration(Economy.class);
        economy = provider != null && provider.getProvider().isEnabled() ? provider.getProvider() : null;
        return economy != null;
    }

    void load(BlockState[] states) {
        for (var state : states) {
            if (!(state instanceof CreatureSpawner spawner)) continue;
            var pdc = spawner.getPersistentDataContainer();
            int count = pdc.getOrDefault(countKey, PersistentDataType.INTEGER, 0);
            if (count == 0) continue;
            try {
                var type = parseType(pdc.getOrDefault(typeKey, PersistentDataType.STRING, ""));
                int capacity = pdc.getOrDefault(capacityKey, PersistentDataType.INTEGER, maxStack);
                long balance = pdc.getOrDefault(balanceKey, PersistentDataType.LONG, 0L);
                long remainder = pdc.getOrDefault(remainderKey, PersistentDataType.LONG, 0L);
                if (type == null || count < 1 || count > capacity || capacity > MAX_AMOUNT
                        || balance < 0 || remainder < 0 || remainder >= 60) {
                    getLogger().warning("Invalid spawner at " + spawner.getLocation());
                    continue;
                }
                spawners.put(spawner.getBlock(), new MoneySpawner(spawner.getBlock(), type, count, capacity, balance, remainder));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Couldn't read spawner at " + spawner.getLocation());
            }
        }
    }

    boolean save(MoneySpawner data) {
        return save(data, data.count);
    }

    boolean save(MoneySpawner data, int count) {
        if (!(data.block.getState() instanceof CreatureSpawner spawner)) return false;
        var pdc = spawner.getPersistentDataContainer();
        pdc.set(typeKey, PersistentDataType.STRING, data.type.name());
        pdc.set(countKey, PersistentDataType.INTEGER, count);
        pdc.set(balanceKey, PersistentDataType.LONG, data.balance);
        pdc.set(remainderKey, PersistentDataType.LONG, data.remainder);
        pdc.set(capacityKey, PersistentDataType.INTEGER, data.capacity);
        spawner.setSpawnedType(data.type);
        spawner.setSpawnCount(0);
        if (!spawner.update(false, false)) return false;
        data.count = count;
        return true;
    }

    boolean tagged(Block block) {
        return block.getState() instanceof CreatureSpawner spawner
                && spawner.getPersistentDataContainer().getOrDefault(countKey, PersistentDataType.INTEGER, 0) > 0;
    }

    boolean canUse(Player p) {
        return p.hasPermission("spawnerplusplus.use");
    }

    static boolean spawnable(EntityType type) {
        return type != null && type.isAlive() && type.isSpawnable();
    }

    static EntityType parseType(String name) {
        try {
            return EntityType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    EntityType itemType(ItemStack stack) {
        if (stack == null || stack.getType() != Material.SPAWNER) return null;
        var type = parseType(stack.getPersistentDataContainer().getOrDefault(typeKey, PersistentDataType.STRING, ""));
        return spawnable(type) ? type : null;
    }

    ItemStack item(EntityType type, int amount) {
        return item(type, amount, maxStack);
    }

    int itemCapacity(ItemStack stack) {
        return Math.clamp(stack.getPersistentDataContainer().getOrDefault(capacityKey, PersistentDataType.INTEGER, maxStack), maxStack, MAX_AMOUNT);
    }

    Component spawnerName(EntityType type) {
        return Component.text(label(type) + " Spawner", text.accent)
                .decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false);
    }

    ItemStack item(EntityType type, int amount, int capacity) {
        ItemStack stack = new ItemStack(Material.SPAWNER, amount);
        stack.editMeta(meta -> {
            meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.name());
            if (capacity > maxStack) meta.getPersistentDataContainer().set(capacityKey, PersistentDataType.INTEGER, capacity);
            meta.customName(spawnerName(type));
            meta.lore(List.of(text.line("Mob: {0}", text.muted, label(type)),
                    text.line("Income: {0}/min each", text.muted, money(rate(type))),
                    text.line("Capacity: {0}", text.muted, Integer.toString(capacity)),
                    Component.empty(), text.line("Sneak + right-click to stack", text.muted)));
        });
        return stack;
    }

    boolean upgrade(Player p, MoneySpawner spawner) {
        var next = upgrades.higherEntry(spawner.capacity);
        if (next == null || spawner.upgrading || spawners.get(spawner.block) != spawner || !canUse(p)) return false;
        if (!hookEconomy()) {
            text.error(p, "economy-unavailable");
            return false;
        }
        Economy provider = economy;
        long cost = next.getValue();
        spawner.upgrading = true;
        try {
            if (!provider.has(p, cost / 100.0)) {
                text.error(p, "upgrade-funds", money(cost));
                return false;
            }
            var payment = provider.withdrawPlayer(p, cost / 100.0);
            if (!payment.transactionSuccess()) {
                text.error(p, "upgrade-failed");
                return false;
            }
            int capacity = spawner.capacity;
            spawner.capacity = next.getKey();
            if (save(spawner)) {
                text.reply(p, "upgraded", Integer.toString(spawner.capacity));
                return true;
            }
            spawner.capacity = capacity;
            var refund = provider.depositPlayer(p, cost / 100.0);
            if (refund.transactionSuccess()) {
                text.error(p, "upgrade-refunded");
            } else {
                getLogger().severe("Upgrade refund failed: " + p.getName() + " " + cost + "c");
                text.error(p, "payout-check");
            }
            return false;
        } catch (RuntimeException e) {
            getLogger().severe("Upgrade payment error: " + p.getName() + " " + cost + "c: " + e.getMessage());
            text.error(p, "payout-check");
            return false;
        } finally {
            spawner.upgrading = false;
        }
    }

    String label(EntityType type) {
        StringBuilder label = new StringBuilder();
        for (String word : type.name().toLowerCase(Locale.ROOT).split("_")) {
            if (!label.isEmpty()) label.append(' ');
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }

    String money(long cents) {
        return economy != null ? economy.format(cents / 100.0) : BigDecimal.valueOf(cents, 2).toPlainString();
    }

    long rate(EntityType type) {
        return rates.getOrDefault(type, defaultRate);
    }

    boolean collect(Player p, MoneySpawner spawner) {
        if (!hookEconomy()) {
            text.error(p, "economy-unavailable");
            return false;
        }
        Economy provider = economy;
        long balance = spawner.balance;
        if (balance == 0) {
            text.reply(p, "nothing-to-collect");
            return true;
        }
        if (!provider.hasAccount(p) && !provider.createPlayerAccount(p)) {
            text.error(p, "account-unavailable");
            return false;
        }
        // clear before deposit so a callback can't collect it twice
        spawner.balance = 0;
        if (!save(spawner)) {
            spawner.balance = balance;
            text.error(p, "save-failed");
            return false;
        }
        EconomyResponse response;
        try {
            response = provider.depositPlayer(p, balance / 100.0);
        } catch (RuntimeException e) {
            getLogger().severe("Payout error: " + p.getName() + " " + balance + "c: " + e.getMessage());
            text.error(p, "payout-check");
            return false;
        }
        if (!response.transactionSuccess()) {
            spawner.balance = balance;
            save(spawner);
            text.error(p, "payout-failed");
            return false;
        }
        text.reply(p, "collected", money(balance));
        return true;
    }

    private void tick() {
        if (economy == null || !economy.isEnabled()) hookEconomy();
        seconds++;
        var entries = spawners.values().iterator();
        while (entries.hasNext()) {
            MoneySpawner spawner = entries.next();
            if (!tagged(spawner.block)) {
                entries.remove();
                continue;
            }
            long income = rate(spawner.type) * spawner.count + spawner.remainder;
            if (economy != null && spawner.balance < maxBalance) {
                spawner.balance = Math.min(maxBalance, spawner.balance + income / 60);
                spawner.remainder = income % 60;
            }
            if (seconds % 60 == 0) save(spawner);
        }
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof SpawnerMenu menu)
                menu.refresh(p);
        }
    }
}
