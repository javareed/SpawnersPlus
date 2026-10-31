Spawner++ 1.5.1 stacks spawners into shared money generators on Paper 1.21.11. Requires Java 21 and Vault with an economy plugin.

![Spawner menu](docs/gui.png)

| Command | Action | Permission |
| --- | --- | --- |
| `/spawners` | Show help | Everyone |
| `/spawners give <player> <mob> [amount]` | Give spawners | `spawnerplusplus.admin` |
| `/spawners add [amount]` | Add to the spawner you look at | `spawnerplusplus.admin` |
| `/spawners remove [amount]` | Remove from the spawner you look at | `spawnerplusplus.admin` |
| `/spawners set <amount>` | Set its count or use 0 to remove it | `spawnerplusplus.admin` |

Add and remove default to 1. Spawner interactions require `spawnerplusplus.use`.

Build: `mvn -B -ntp verify`
