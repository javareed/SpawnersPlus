package dev.reed.spawners;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

final class SpawnerListener implements Listener {
    private final SpawnersPlus plugin;

    SpawnerListener(SpawnersPlus plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void checkPlace(BlockPlaceEvent event) {
        if (plugin.itemType(event.getItemInHand()) != null && !plugin.canUse(event.getPlayer())) {
            event.setCancelled(true);
            plugin.text.error(event.getPlayer(), "no-permission");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        var type = plugin.itemType(event.getItemInHand());
        if (type == null || !(event.getBlockPlaced().getState() instanceof CreatureSpawner)) return;
        MoneySpawner spawner = new MoneySpawner(event.getBlockPlaced(), type, 1, plugin.itemCapacity(event.getItemInHand()), 0, 0);
        if (plugin.save(spawner)) plugin.spawners.put(spawner.block, spawner);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null || event.useInteractedBlock() == Event.Result.DENY) return;
        MoneySpawner spawner = plugin.spawners.get(event.getClickedBlock());
        if (spawner == null) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player p = event.getPlayer();
        if (!plugin.canUse(p)) {
            plugin.text.error(p, "no-permission");
            return;
        }
        ItemStack held = p.getInventory().getItemInMainHand();
        if (p.isSneaking() && plugin.itemType(held) == spawner.type) {
            if (plugin.itemCapacity(held) > plugin.maxStack) {
                plugin.text.error(p, "place-upgraded");
                return;
            }
            int amount = Math.min(held.getAmount(), spawner.capacity - spawner.count);
            if (amount <= 0) {
                plugin.text.error(p, "stack-full");
                return;
            }
            if (!plugin.save(spawner, spawner.count + amount)) {
                plugin.text.error(p, "save-failed");
                return;
            }
            held.setAmount(held.getAmount() - amount);
            plugin.text.reply(p, "stacked", Integer.toString(amount), Integer.toString(spawner.count), Integer.toString(spawner.capacity));
            return;
        }
        SpawnerMenu menu = new SpawnerMenu(plugin, spawner);
        menu.refresh(p);
        p.openInventory(menu.getInventory());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void checkBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof CreatureSpawner state)) return;
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) return;
        MoneySpawner spawner = plugin.spawners.get(event.getBlock());
        if (!plugin.canUse(event.getPlayer())) {
            event.setCancelled(true);
            plugin.text.error(event.getPlayer(), "no-permission");
            return;
        }
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        if (!Tag.ITEMS_PICKAXES.isTagged(tool.getType()) || !tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
            event.setCancelled(true);
            plugin.text.error(event.getPlayer(), "silk-required");
            return;
        }
        if (state.getSpawnedType() == null) {
            event.setCancelled(true);
            plugin.text.error(event.getPlayer(), "unknown-type");
            return;
        }
        if (spawner != null && spawner.balance > 0 && !plugin.collect(event.getPlayer(), spawner)) {
            event.setCancelled(true);
            return;
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof CreatureSpawner state) || state.getSpawnedType() == null) return;
        boolean creative = event.getPlayer().getGameMode() == GameMode.CREATIVE;
        var tracked = plugin.spawners.get(event.getBlock());
        var spawner = tracked != null ? tracked
                : new MoneySpawner(event.getBlock(), state.getSpawnedType(), 1, plugin.maxStack, 0, 0);
        // wait a tick so cancelled breaks never drop anything
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (event.isCancelled()) return;
            plugin.spawners.remove(spawner.block, spawner);
            if (creative) return;
            int left = spawner.count;
            if (spawner.capacity > plugin.maxStack) {
                spawner.block.getWorld().dropItem(spawner.block.getLocation().add(.5, .5, .5), plugin.item(spawner.type, 1, spawner.capacity));
                left--;
            }
            while (left > 0) {
                int amount = Math.min(left, Material.SPAWNER.getMaxStackSize());
                spawner.block.getWorld().dropItem(spawner.block.getLocation().add(.5, .5, .5), plugin.item(spawner.type, amount));
                left -= amount;
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SpawnerMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p) || plugin.spawners.get(menu.spawner.block) != menu.spawner
                || !plugin.canUse(p)) return;
        if (menu.upgrades) {
            if (event.getRawSlot() == SpawnerMenu.LEFT_SLOT && event.isLeftClick()) openMenu(p, menu.spawner, false);
            if (event.getRawSlot() == SpawnerMenu.RIGHT_SLOT && event.isLeftClick() && !menu.purchased) {
                menu.purchased = plugin.upgrade(p, menu.spawner);
                if (menu.purchased) openMenu(p, menu.spawner, false);
                else menu.refresh(p);
            }
        } else if (event.getRawSlot() == SpawnerMenu.RIGHT_SLOT && event.isLeftClick()) {
            openMenu(p, menu.spawner, true);
        } else if (event.getRawSlot() == SpawnerMenu.MIDDLE_SLOT && event.isLeftClick()) {
            plugin.collect(p, menu.spawner);
            menu.refresh(p);
        }
    }

    private void openMenu(Player p, MoneySpawner spawner, boolean upgrades) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || plugin.spawners.get(spawner.block) != spawner || !plugin.canUse(p)) return;
            SpawnerMenu menu = new SpawnerMenu(plugin, spawner, upgrades);
            menu.refresh(p);
            p.openInventory(menu.getInventory());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SpawnerMenu) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(SpawnerSpawnEvent event) {
        if (plugin.tagged(event.getSpawner().getBlock())) event.setCancelled(true);
    }

    @EventHandler
    public void onLoad(ChunkLoadEvent event) {
        plugin.load(event.getChunk().getTileEntities());
    }

    @EventHandler
    public void onUnload(ChunkUnloadEvent event) {
        var entries = plugin.spawners.values().iterator();
        while (entries.hasNext()) {
            MoneySpawner spawner = entries.next();
            if (spawner.block.getWorld().equals(event.getWorld()) && (spawner.block.getX() >> 4) == event.getChunk().getX()
                    && (spawner.block.getZ() >> 4) == event.getChunk().getZ()) {
                plugin.save(spawner);
                entries.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(plugin::tagged);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(plugin::tagged);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) {
        for (var block : event.getBlocks()) if (plugin.tagged(block)) { event.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRetract(BlockPistonRetractEvent event) {
        for (var block : event.getBlocks()) if (plugin.tagged(block)) { event.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        if (plugin.tagged(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler
    public void onEconomyRegister(ServiceRegisterEvent event) {
        if (event.getProvider().getService() == Economy.class) plugin.hookEconomy();
    }

    @EventHandler
    public void onEconomyUnregister(ServiceUnregisterEvent event) {
        if (event.getProvider().getService() == Economy.class) plugin.hookEconomy();
    }
}
