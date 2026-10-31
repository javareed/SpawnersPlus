package dev.reed.spawners;

import java.util.List;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

final class SpawnerMenu implements InventoryHolder {
    static final int LEFT_SLOT = 10;
    static final int MIDDLE_SLOT = 13;
    static final int RIGHT_SLOT = 16;

    final SpawnersPlus plugin;
    final MoneySpawner spawner;
    final boolean upgrades;
    boolean purchased;
    private final Inventory inventory;

    SpawnerMenu(SpawnersPlus plugin, MoneySpawner spawner) {
        this(plugin, spawner, false);
    }

    SpawnerMenu(SpawnersPlus plugin, MoneySpawner spawner, boolean upgrades) {
        this.plugin = plugin;
        this.spawner = spawner;
        this.upgrades = upgrades;
        Component title = Component.text(upgrades ? "Capacity upgrades" : plugin.label(spawner.type));
        inventory = plugin.getServer().createInventory(this, 27, title);
    }

    void refresh(Player p) {
        if (plugin.spawners.get(spawner.block) != spawner || !plugin.canUse(p)) {
            p.closeInventory();
            return;
        }
        SpawnerText text = plugin.text;
        var next = plugin.upgrades.higherEntry(spawner.capacity);
        if (upgrades) {
            inventory.setItem(LEFT_SLOT, button(Material.ARROW, text.line("Back", text.accent),
                    List.of(text.line("Return to {0}", text.muted, plugin.label(spawner.type)))));
            inventory.setItem(MIDDLE_SLOT, button(Material.SPAWNER, plugin.spawnerName(spawner.type),
                    List.of(text.line("Spawners: {0}", text.muted, Integer.toString(spawner.count)),
                            text.line("Current capacity: {0}", text.muted, Integer.toString(spawner.capacity)))));
            inventory.setItem(RIGHT_SLOT, next == null
                    ? button(Material.GRAY_DYE, text.line("Fully upgraded", text.accent), List.of())
                    : button(Material.EMERALD, text.line("Upgrade to {0}", text.accent, Integer.toString(next.getKey())),
                    List.of(text.line("New capacity: {0}", text.muted, Integer.toString(next.getKey())),
                            text.line("Price: {0}", text.muted, plugin.money(next.getValue())),
                            Component.empty(), text.line("Click to buy", text.muted))));
            return;
        }
        long rate = plugin.rate(spawner.type) * spawner.count;
        Material icon = Material.matchMaterial(spawner.type.name() + "_SPAWN_EGG");
        inventory.setItem(LEFT_SLOT, button(icon == null ? Material.SPAWNER : icon, plugin.spawnerName(spawner.type),
                List.of(text.line("Spawners: {0} / {1}", text.muted, Integer.toString(spawner.count), Integer.toString(spawner.capacity)),
                        text.line("Income: {0}/min", text.muted, plugin.money(rate)))));
        inventory.setItem(MIDDLE_SLOT, button(spawner.balance > 0 ? Material.EMERALD : Material.GRAY_DYE,
                text.line("Collect earnings", text.accent),
                List.of(text.line("Available: {0}", text.muted, plugin.money(spawner.balance)),
                        text.line("Storage limit: {0}", text.muted, plugin.money(plugin.maxBalance)), Component.empty(),
                        text.line("Click to withdraw", text.muted))));
        inventory.setItem(RIGHT_SLOT, button(Material.ANVIL, text.line("Upgrade capacity", text.accent), next == null
                ? List.of(text.line("Capacity: {0}", text.muted, Integer.toString(spawner.capacity)), text.line("Fully upgraded", text.muted))
                : List.of(text.line("Next capacity: {0}", text.muted, Integer.toString(next.getKey())),
                text.line("Price: {0}", text.muted, plugin.money(next.getValue())), Component.empty(), text.line("Click to view upgrades", text.muted))));
    }

    private ItemStack button(Material material, Component name, List<Component> lines) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.customName(name);
            meta.lore(lines);
        });
        return stack;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
