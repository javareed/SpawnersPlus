package dev.reed.spawners;

import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;

final class MoneySpawner {
    final Block block;
    final EntityType type;
    int count;
    int capacity;
    boolean upgrading;
    long balance;
    long remainder;

    MoneySpawner(Block block, EntityType type, int count, int capacity, long balance, long remainder) {
        this.block = block;
        this.type = type;
        this.count = count;
        this.capacity = capacity;
        this.balance = balance;
        this.remainder = remainder;
    }
}
