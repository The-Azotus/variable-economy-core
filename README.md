# Variable Economy

An economy for Minecraft servers. It brings its own coins and bank: every player has a bank account, and all money moves through it, so there are no coins to carry. Add-ons build on it with a server-wide market whose prices rise and fall with supply and demand, a board of delivery contracts, and a player exchange.

This repository is **core**, the bank that every Variable Economy mod builds on. It works alone:

- **Bank payments.** All money moves through each player's bank account, so there are no coins to carry. Coins go in and out of the bank at a Bank block, or anywhere with a Bank Card.
- **Its own coins.** Eight coins, from the Bit up to the Omega, each worth 8 of the one before. Money is counted in bits.
- **Accounts.** Every player has a personal account; servers can let them open named accounts and share them with members or deposit-only members, and FTB Teams parties get a shared account. `/pay` moves money between players, each account keeps its history, and admins see the whole money supply.
- **Leaderboard.** The richest players by bank balance, counting money the add-ons hold for them, shown beside the bank.
- **Item locks.** With ProgressiveStages, items a player hasn't unlocked are locked for them, and every add-on respects it.
- **For scripts and other mods.** KubeJS server scripts can read balances, pay, charge and hear balance changes, and other mods use a Java API.

## The four mods

Variable Economy is four mods. **Core** is required; the **market**, **contracts** and **exchange** are optional add-ons, and any combination of them works on top of core:

| Mod | Mod id | Adds | Repository |
| --- | --- | --- | --- |
| Core | `variable_economy` | The coins and bank, the Bank block and Bank Card, accounts, `/pay`, account history, the leaderboard, money-supply stats, `/bank` | This one |
| Market | `variable_economy_market` | A server-owned market whose prices move with supply and demand: the Market Terminal, prices and price tooltips, `/market` | [variable-economy-market](https://github.com/The-Azotus/variable-economy-market) |
| Contracts | `variable_economy_contracts` | Delivery contracts with deposits: the Contract Board, `/contracts`. Without the market, only fixed-payout contracts. | [variable-economy-contracts](https://github.com/The-Azotus/variable-economy-contracts) |
| Exchange | `variable_economy_exchange` | Player buy and sell orders: the Exchange block, `/exchange` | [variable-economy-exchange](https://github.com/The-Azotus/variable-economy-exchange) |

Each mod has its own block, screen, config file and commands. A mod that isn't installed simply isn't there.

| You are | Read |
| --- | --- |
| A player | [Player guide](docs/player-guide.md) |
| A server admin | [Admin guide](docs/admin-guide.md) |
| A mod or modpack author building on the bank | [Add-on guide](docs/api.md) |

## Requirements

- Minecraft 1.21.1 with NeoForge 21.1.228 or newer, and Java 21
- Nothing else. Optional:
  - [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-forge) 2101.1.x: its parties share an account.
  - [KubeJS](https://www.curseforge.com/minecraft/mc-mods/kubejs) 2101.7.x: server scripts can read balances, pay, charge and hear balance changes.
  - [ProgressiveStages](https://modrinth.com/mod/progressivestages) 3.x (3.1.1 or later): items a player hasn't unlocked are locked for them: hidden in the market, greyed out on the Contract Board, and not tradeable on the exchange.

Install the mods you use on the **server and every client**, all of the same version. Clients need them for the screens.

What changed in each version is in the [changelog](CHANGELOG.md).

**Coming from an older version?** Worlds from Dynamic-Economy, this mod's name before 0.6.0, can't be carried over: the mod id changed to `variable_economy` and the add-ons to their own ids, so their market, bank accounts, coins and terminals don't load.

## Quick start

**Players:** craft a **Bank** (8 Bits around a chest) and right-click it, or type `/bank` if your server allows it. **Deposit coins** pays the coins you carry into your account; click a coin, or type an amount and press **Withdraw**, to take money out as coins. `/pay <player> <amount>` pays another player. See the [player guide](docs/player-guide.md).

**Admins:**

1. Install core (and the add-ons you want) on the server and every client, then start the server. Core's settings are in `config/variable_economy-server.toml`.
2. To let players open named accounts and share them, set `extra_enabled` and `shared_enabled` to `true` under `[accounts]`. With FTB Teams, each party gets a shared account by default.
3. To make the Bank block (and the Bank Card) the only way in, set `access_mode = "BLOCK_ONLY"`. Each add-on has its own `access_mode` in its own config file.
4. `/bank admin stats` shows the whole money supply, and `/bank admin money give <player> <amount>` pays a player.

The [admin guide](docs/admin-guide.md) covers every command and setting.

## For add-on authors

Other mods can read accounts and balances, pay, charge and move money, lock items for players, add the money they hold to the leaderboard, write to the audit log and listen for balance and account changes through core's Java API. `/bank admin money` commands and the KubeJS bindings work without any Java. The [add-on guide](docs/api.md) covers both, and how to build against core.

## Building from source

Run `gradlew build` (Java 21: Gradle's toolchain finds an installed JDK or downloads one; the Gradle wrapper is in the repository). It compiles the mod, checks the module boundary (see [docs/architecture.md](docs/architecture.md)), and builds the jar, a sources jar and a javadoc jar (of the API, `variableeconomy.core.api`) in `build/libs/`.

- `gradlew runGameTestServer` runs core's automated tests on a GameTest server, then checks that it started cleanly. It loads the optional mods (FTB Teams, ProgressiveStages and KubeJS); `-PwithoutMods=all` (or a comma-separated list of `ftbteams`, `progressivestages`, `kubejs`) leaves them out, with a test world of its own.
- `gradlew runClient` and `gradlew runServer` start a dev client or server.
- `gradlew publishToMavenLocal` publishes `variableeconomy:core:<version>` to your local Maven, for the add-ons (and other add-ons) to build against.

Released versions are tagged in git (for example `v0.6.1`). To find your way around the code, start with [docs/architecture.md](docs/architecture.md).

## Licence

MIT; see [LICENSE](LICENSE).
