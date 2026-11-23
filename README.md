# Variable Economy

An economy for Minecraft servers, for NeoForge 1.21.1. Every player has a bank account. All money lives in accounts, and coins are only how money goes in and out of the bank. Optional add-ons build on top of it: a server-wide market whose prices rise and fall with supply and demand, a board of delivery contracts, and a player exchange.

This repository is **core**, the bank that every Variable Economy mod builds on. Installed alone, it is a complete bank mod with:

- **Its own coins.** Eight coins, from the Bit up to the Omega, each worth 8 of the one before. Every amount is counted in bits.
- **A bank.** Coins go in and out at a Bank block, or anywhere with a Bank Card. The bank screen also shows the leaderboard and each account's history.
- **Accounts.** Every player has a personal account. Servers can let players open named accounts and share them with members or deposit-only members. With FTB Teams, each party gets a shared account.
- **`/pay`.** Players pay each other, online or offline, with an optional fee that is destroyed.
- **History, a leaderboard and the money supply.** Every balance change is recorded with its reason. The leaderboard ranks the richest players or accounts. Admins see where money is created and destroyed, and an audit log keeps their actions.
- **Item locks.** With ProgressiveStages, items a player hasn't unlocked are locked for them, and every add-on respects that.
- **Hooks for packs and mods.** Commands work from functions, KubeJS scripts can read balances, pay and charge, and other mods use a Java API with events.

## The four mods

Variable Economy is four mods. **Core** is required. The **market**, **contracts** and **exchange** are optional add-ons, and any combination of them works on top of core.

| Mod | Mod id | Adds | Repository |
| --- | --- | --- | --- |
| Core | `variable_economy` | The coins and bank, the Bank block and Bank Card, accounts, `/pay`, account history, the leaderboard, money-supply stats, `/bank` | This one |
| Market | `variable_economy_market` | A server-owned market whose prices move with supply and demand: the Market Terminal, prices and price tooltips, `/market` | [variable-economy-market](https://github.com/The-Azotus/variable-economy-market) |
| Contracts | `variable_economy_contracts` | Delivery contracts with deposits: the Contract Board, `/contracts`. Without the market, only fixed-payout contracts. | [variable-economy-contracts](https://github.com/The-Azotus/variable-economy-contracts) |
| Exchange | `variable_economy_exchange` | Player buy and sell orders: the Exchange block, `/exchange` | [variable-economy-exchange](https://github.com/The-Azotus/variable-economy-exchange) |

Each mod has its own block, screen, config file and commands. If a mod isn't installed, its features are simply absent.

## Requirements and installation

- Minecraft 1.21.1 with NeoForge 21.1.228 or newer, and Java 21.
- Nothing else is required. Optional:
  - [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-forge) 2101.1.x: its parties share an account.
  - [KubeJS](https://www.curseforge.com/minecraft/mc-mods/kubejs) 2101.7.x: server scripts can read balances, pay, charge and hear balance changes.
  - [ProgressiveStages](https://modrinth.com/mod/progressivestages) 3.x (3.1.1 or later): items a player hasn't unlocked are locked for them. They are hidden in the market, greyed out on the Contract Board, and can't be traded on the exchange.

Install the mods you use on the **server and every client**, all of the same version. Clients need them for the screens. Put `variable_economy-<version>.jar` in the mods folder; the `-sources` and `-javadoc` jars are for developers.

What changed in each version is in the [changelog](CHANGELOG.md). Worlds from Dynamic-Economy, this mod's name before 0.6.0, can't be carried over.

## Using it

### Coins

| Coin | Worth | Coin | Worth |
| --- | --- | --- | --- |
| Bit | 1 bit | Crown | 4,096 bits |
| Chip | 8 bits | Sun | 32,768 bits |
| Stack | 64 bits | Divine | 262,144 bits |
| Mark | 512 bits | Omega | 2,097,152 bits |

Put 8 of a coin in a crafting grid to make one of the next, or one coin to break it back into 8. Coins only get spent by depositing them: the market, contracts, the exchange and `/pay` all use the bank account.

### The bank

Craft a **Bank** (8 Bits around a chest) and right-click it, or type `/bank` if the server allows it. A **Bank Card** (a paper, a gold ingot and a Chip, in any arrangement) opens the same screen anywhere.

- **Deposit coins** pays every plain coin in your main inventory into the account shown.
- **Click a coin** to withdraw one of it, or **Shift-click** to withdraw a stack. **Withdraw** takes a typed amount out as the fewest coins. Nothing is withdrawn if the coins wouldn't all fit, so nothing ever drops on the ground.
- The other side of the screen shows the **leaderboard**, or the shown account's **History**.

### Accounts

- **Your personal account** always exists.
- **Named accounts** can be opened if the server allows extra accounts.
- **Shared accounts** are named accounts with other members, if the server allows sharing:
  - the owner invites players, and they join by accepting;
  - a **member** deposits, spends and withdraws;
  - a **deposit-only** member can only pay in;
  - the owner can withdraw an invitation that hasn't been answered;
  - when the owner leaves, the member who joined first becomes owner.
- **Your FTB Teams party's account**, with FTB Teams, is shared by the whole party.

Your **active account** is the one you spend from and are paid into: market trades, contract payouts and deposits, exchange orders, and `/pay`. Pick it with **Use for trades** in the bank screen or `/bank account use <name>`. **Accounts…** in the bank screen opens the Accounts screen, where you open, join, leave and manage accounts.

### Paying other players

`/pay <player> <amount>` pays from your active account into theirs. If their active account is a named or party account, the money goes there, and both messages say so. A server may set a fee (paid on top, and destroyed) or a smallest payment.

The full walkthrough is in the [player guide](docs/player-guide.md).

## Commands

Player commands work on every server. Only bare `/bank` follows `access_mode`; the others work even when the bank is kept to its block.

| Command | What it does |
| --- | --- |
| `/bank` | Opens the bank screen, with the leaderboard. |
| `/bank account [list]` | Lists your accounts, which one is active, and your invitations. |
| `/bank account info <name>` | An account's owner, members, roles and balance. Its owner also sees who is invited. `personal` names your own account, `party` your FTB Teams party's. |
| `/bank account use <name>` | Makes it your active account. |
| `/bank account create <name>` | Opens a named account. A name is one word of up to 24 letters, digits, `_` and `-`. |
| `/bank account invite <name> <player> [member\|deposit_only]` | Owner only: invites an online player. The Accounts screen also invites offline players by name. |
| `/bank account join <name>`, `decline <name>` | Accepts or turns down an invitation. |
| `/bank account leave <name>` | Leaves an account. The last member deletes it instead, once it's empty. |
| `/bank account remove <name> <player>` | Owner only: removes a member, or withdraws an invitation they haven't answered. |
| `/bank account role <name> <player> <owner\|member\|deposit_only>` | Owner only: changes a member's role. `owner` hands the account over. |
| `/bank account delete <name>` | Owner only: deletes an empty account. |
| `/bank history [account] [page]` | An account's money movements, newest first. Deposit-only members can't read it. |
| `/bank top [page]` | The leaderboard. |
| `/pay <player> <amount>` | Pays another player. |

Admin commands need permission level 2:

| Command | What it does |
| --- | --- |
| `/bank admin info` | How many personal, named and party accounts hold money, and what they hold. |
| `/bank admin accounts <player>` | Every account a player may use, with their role, its owner, members and balance. |
| `/bank admin money balance <player>` or `balance account <name>` | A balance, online or offline. Returns it (capped at 2,147,483,647) for `execute store`. |
| `/bank admin money give <player> <amount>` or `give account <name> <amount>` | Pays new money in. Logged in the audit log. |
| `/bank admin money take <player> <amount>` or `take account <name> <amount>` | Takes money out, all or nothing. Logged. |
| `/bank admin history <player> [page]` or `history account <name> [page]` | Any account's history. |
| `/bank admin stats`, `stats reset` | The whole money supply: what accounts hold, coins paid in and out, and money created and destroyed by each source. |
| `/bank admin audit [page]` | The latest admin actions from the audit log, from every Variable Economy mod. |

## Configuration

Core's settings are in `config/variable_economy-server.toml`, created on first start. Changes apply when the file is saved.

| Setting | Default | What it does |
| --- | --- | --- |
| `access_mode` | `COMMAND_AND_BLOCK` | `BLOCK_ONLY` keeps the bank to the Bank block and Bank Card. |
| `ignored_components` | `["relics:data"]` | Item data that doesn't stop a copy counting as plain, for market sales, the market's tooltips and contract deliveries (not the exchange). |
| `[accounts]` `extra_enabled` | `false` | Players can open named accounts. |
| `[accounts]` `max_per_player` | `3` | Accounts one player may own, their personal one included. |
| `[accounts]` `shared_enabled` | `false` | Named accounts can have members. |
| `[accounts]` `max_members` | `8` | Members per account, owner included. |
| `[accounts]` `ftb_party_account` | `true` | With FTB Teams, each party gets an account. |
| `[pay]` `enabled`, `fee_percent`, `min_amount` | `true`, `0`, `1` | `/pay`, its fee (a percentage, rounded up and destroyed) and the smallest payment. |
| `[history]` `size` | `50` | How many recent changes each account keeps; `0` keeps none. |
| `[leaderboard]` `enabled`, `size`, `rank_by`, `exclude_operators` | `true`, `10`, `OWNER`, `false` | The leaderboard. `rank_by = ACCOUNT` lists each account instead of each player. |

Every setting, with its range, is in the [admin guide](docs/admin-guide.md#server-config).

## For pack makers

### Commands from functions

Functions and command blocks run at level 2, so they can use the admin money commands. Each command's return value works with `execute store`:

```mcfunction
execute store result score @p balance run bank admin money balance @p
bank admin money give Steve 500
```

### KubeJS

With KubeJS installed, server scripts get `VariableEconomy`. A player's money here means their active account's:

| Call | Does |
| --- | --- |
| `VariableEconomy.balance(player)` | The active account's balance. |
| `VariableEconomy.personalBalance(player)` | The personal account's balance. |
| `VariableEconomy.pay(player, amount[, 'mypack:reason'])` | Pays new money into the active account. Returns whether it was paid. |
| `VariableEconomy.charge(player, amount[, 'mypack:reason'])` | Takes money, all or nothing. Returns false, changing nothing, if it can't. |
| `VariableEconomy.format(amount)` | The amount as players read it, such as *1,024 bits*. |

`VariableEconomyEvents.balanceChanged` fires at the end of each tick for every account whose balance changed. It gives `event.account`, `event.owner`, `event.player` (the owner, if online), `event.before`, `event.after` and `event.changes`, each change with an `amount`, a `reason` and an `actor`.

```js
// server_scripts/economy.js: /buywings costs 50,000 bits and grants the stage 'wings'.
ServerEvents.commandRegistry(event => {
  const { commands: Commands } = event
  event.register(Commands.literal('buywings').executes(ctx => {
    const player = ctx.source.player
    if (!VariableEconomy.charge(player, 50000, 'mypack:wings')) {
      player.tell(Text.red('Wings cost ').append(VariableEconomy.format(50000)))
      return 0
    }
    player.stages.add('wings')
    return 1
  }))
})

VariableEconomyEvents.balanceChanged(event => {
  if (event.player && event.before < 1000000 && event.after >= 1000000) {
    event.player.tell(Text.gold('You have a million bits!'))
  }
})
```

Paying creates money. A script that pays for items should pay less than the market charges for them, or players can buy from the market and hand the items in for a profit. The full rules are in the [admin guide](docs/admin-guide.md#kubejs).

## For mod authors

Core has a server-side Java API in `variableeconomy.core.api` (events in `variableeconomy.core.api.event`). It is marked experimental until 1.0, and every change is listed in the [add-on guide's changelog](docs/api.md#changelog).

### Depending on core

The released jars are on CurseForge (core is project 1818087) and can be used through [CurseMaven](https://www.cursemaven.com/):

```groovy
repositories {
    exclusiveContent {
        forRepository { maven { url = 'https://cursemaven.com' } }
        filter { includeGroup 'curse.maven' }
    }
}

dependencies {
    compileOnly 'curse.maven:variable-economy-1818087:<file id>'
}
```

Then declare core in your `neoforge.mods.toml`:

```toml
[[dependencies.yourmod]]
    modId="variable_economy"
    type="required"        # or "optional"
    versionRange="[0.6,)"
    ordering="AFTER"
    side="BOTH"
```

For sources and javadoc, clone this repository and run `gradlew publishToMavenLocal`; it publishes `variableeconomy:core:<version>`. The [add-on guide](docs/api.md#depending-on-variable-economy) gives the file IDs, the Kotlin version, and how to keep core an optional dependency.

### The API

Everything starts at `CoreApi`:

| Call | What it gives |
| --- | --- |
| `CoreApi.accounts()` | `Accounts`: a player's personal and active account, any account's owner, members, roles and balance; creating and sharing named accounts under the server's rules. |
| `CoreApi.money()` | `Money`: `balance`, `pay` (creates money), `charge` (destroys it) and `transfer` between accounts, each all or nothing. |
| `CoreApi.itemGate()` | `ItemGate`: lock items for players with your own rules; every module respects them. |
| `CoreApi.netWorth()` | `NetWorth`: count money your mod holds for players (escrow, deposits) on the leaderboard. |
| `CoreApi.audit()` | `Audit`: write your admin commands' actions to the shared audit log. |
| `CoreApi.formatMoney`, `isCoin`, `isPlain` | Helpers: an amount as players read it, whether an item is a coin, whether a stack is a plain copy. |

`PlayerItems` takes items from and gives items to a player's main inventory, the way every module does: check first, never drop.

The rules:

- **Server thread only.** Calls that read or change accounts, money or item locks must run on the server thread, and throw otherwise. The few exceptions are listed in the guide.
- **Results, not exceptions.** Money calls return a `PaymentResult` wrapping a `BankResult` (`OK`, `NO_FUNDS`, `NO_ACCOUNT`, `TOO_LARGE`…). Only `OK` changed anything.
- **Every movement has a `Reason`.** It is an ID in your namespace, optionally with the player who caused it. It shows in the account's history and the audit log, and counts as a source in the money-supply stats.
- **Amounts are `long` bits.**
- **Don't pay more for items than the market charges for them.** Otherwise players buy from the market and sell to you for a profit. The [money rule](docs/api.md#the-money-rule) shows how.

### Events

All are posted on `NeoForge.EVENT_BUS`, after the change is complete, and are never cancellable:

| Event | Fires when |
| --- | --- |
| `BalanceChangedEvent` | An account's balance changed, for any reason. Posted once per account at the end of the tick, with every change and its reason. |
| `AccountEvent.Created`, `Deleted` | An account was created, or ended. |
| `AccountEvent.MemberAdded`, `MemberRemoved`, `RoleChanged` | Someone joined or left an account, or their role changed (ownership passing included). |
| `ItemGateChangedEvent` | Which items are locked may have changed for a player, or for everyone. |

### Example

```java
private static final ResourceLocation QUEST_REWARD = ResourceLocation.fromNamespaceAndPath("yourmod", "quest_reward");

/** On the server thread, when a player finishes a quest. */
static void rewardQuest(ServerPlayer player, long reward) {
    AccountId account = CoreApi.accounts().active(player);
    PaymentResult result = CoreApi.money().pay(player.server, account, reward, Reason.of(QUEST_REWARD));
    if (result.ok()) {
        player.sendSystemMessage(Component.literal("Quest reward: ").append(CoreApi.formatMoney(reward)));
    }
}

static void listen() {
    NeoForge.EVENT_BUS.addListener((BalanceChangedEvent event) -> {
        long change = event.getAfter() - event.getBefore();
        // event.getAccount(), event.getChanges(): each change's amount and reason
    });
}
```

The [add-on guide](docs/api.md) covers every method, result and event. It also has a complete worked example of a mod with an optional dependency on core, and the market's API for prices and trading lives in the [market's guide](https://github.com/The-Azotus/variable-economy-market/blob/main/docs/api.md).

## Guides

| You are | Read |
| --- | --- |
| A player | [Player guide](docs/player-guide.md) |
| A server admin | [Admin guide](docs/admin-guide.md) |
| A mod or modpack author | [Add-on guide](docs/api.md) |
| Reading the code | [Architecture](docs/architecture.md) |

## Building from source

Run `gradlew build` with Java 21. Gradle's toolchain finds an installed JDK or downloads one, and the Gradle wrapper is in the repository. The build compiles the mod, checks the module boundary (see [docs/architecture.md](docs/architecture.md)), and puts the jar, a sources jar and a javadoc jar of the API in `build/libs/`.

- `gradlew runGameTestServer` runs core's automated tests on a GameTest server, then checks that it started cleanly. It loads the optional mods (FTB Teams, ProgressiveStages and KubeJS). `-PwithoutMods=all`, or a comma-separated list of `ftbteams`, `progressivestages` and `kubejs`, leaves them out, with a test world of its own.
- `gradlew runClient` and `gradlew runServer` start a dev client or server.
- `gradlew publishToMavenLocal` publishes `variableeconomy:core:<version>` to your local Maven, for add-ons to build against.

Released versions are tagged in git, for example `v0.6.1`.

## Licence

MIT; see [LICENSE](LICENSE).
