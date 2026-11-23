# Add-on guide

This guide is for mod developers writing an add-on that uses Variable Economy's core, and for pack makers who connect other mods to it with commands and scripts. It assumes you know NeoForge modding, but nothing about this mod's code.

For what the bank does, read the [admin guide](admin-guide.md) first. This guide links to it rather than repeating it. The market, contracts and exchange each have their own guide for their part of the API (see [The modules](#the-modules)).

## What you can do

There are two ways to integrate with core:

- **Without Java.** Drive the bank with commands (from functions, command blocks or KubeJS), and use the KubeJS bindings for balances and payments. This needs no build setup. See [Without Java: commands](#without-java-commands).
- **With the Java API.** Read accounts and balances; pay, charge and move money; lock items for players; add the money you hold for players to the leaderboard; write admin actions to the audit log; and listen for balance changes, account changes and item-lock changes. See [The Java API](#the-java-api) and [Events](#events).

Market prices, trading for a player, price files and item classes are the market's: see the [market's add-on guide](https://github.com/The-Azotus/variable-economy-market/blob/main/docs/api.md). Contract definitions and contract events are in the [contracts' guide](https://github.com/The-Azotus/variable-economy-contracts/blob/main/docs/api.md), and exchange events in the [exchange's](https://github.com/The-Azotus/variable-economy-exchange/blob/main/docs/api.md).

The Java API runs on the server only. A client-only mod can't use it.

### The modules

Variable Economy is four mods: **core** (the bank, accounts, coins, the leaderboard, the audit log and item locks), and three optional add-ons on top of it, the **market**, **contracts** and the **exchange**. A server may have core alone or core with any of the add-ons. Each has its own API package:

| Mod | Mod ID | Artifact | Package | What's in it | Guide |
| --- | --- | --- | --- | --- | --- |
| Core | `variable_economy` | `core` | `variableeconomy.core.api`, `variableeconomy.core.api.event` | Accounts, money, item locks, net-worth contributors, the audit log and helpers. Start at `CoreApi`. | This one |
| Market | `variable_economy_market` | `market` | `variableeconomy.market.api`, `variableeconomy.market.api.event` | Market prices and trading for a player. Start at `MarketApi`. | [Market](https://github.com/The-Azotus/variable-economy-market/blob/main/docs/api.md) |
| Contracts | `variable_economy_contracts` | `contracts` | `variableeconomy.contracts.api.event` | Contract events. | [Contracts](https://github.com/The-Azotus/variable-economy-contracts/blob/main/docs/api.md) |
| Exchange | `variable_economy_exchange` | `exchange` | `variableeconomy.exchange.api.event` | Player exchange events. | [Exchange](https://github.com/The-Azotus/variable-economy-exchange/blob/main/docs/api.md) |

An add-on's package is there only when that add-on is installed, so check its mod ID before using it, even when your mod requires core (see [An optional dependency](#an-optional-dependency)).

### Stability

- The API is the packages in the table above. They're marked `@ApiStatus.Experimental` until 1.0: they may still change in a 0.x minor release (0.6 to 0.7, say). Every change to core's is listed in this file's [changelog](#changelog); each add-on's guide keeps its own.
- A few members inside them are marked `@ApiStatus.Internal` (`CoreApi.install` and its `Services` type). They're for Variable Economy itself; don't use them.
- Everything else in the jar is internal. Those packages are marked `@ApiStatus.Internal` and may change in any release, a patch release included. Don't call them, even when a class is public: public there means "used by another package of this mod", not "supported".
- Commands and config are described in the [admin guide](admin-guide.md), which is kept up to date with each release.

## Depending on Variable Economy

Core and each add-on live in their own repositories and are released separately, each with its own version. The released mod jars are on CurseForge. Use core **0.6.0** or later: 0.5.0, the first version with an API, was Dynamic-Economy, before the mod was renamed and split, and its API is gone (see the [changelog](#changelog)).

Depend on the released jars through [CurseMaven](https://www.cursemaven.com/), as `curse.maven:<name>-<project id>:<file id>` (the name is yours to choose; the IDs are CurseForge's). Core is CurseForge project **1818087**; its 0.6.1 file is 9710072, so core 0.6.1 is `curse.maven:variable-economy-1818087:9710072`. Each add-on's guide gives its own IDs. CurseMaven serves only the mod jars, without sources or javadoc.

For the sources and javadoc, or to build against an unreleased core, publish core to your local Maven from this repository: clone it, check out the tag you target (tags start with `v`, such as `v0.6.1`), and run `gradlew publishToMavenLocal`. That publishes `variableeconomy:core:<version>`, the version without the `v` (`0.6.1`), with a sources jar and a javadoc jar, so your IDE shows the javadoc and the source. The javadoc covers only core's API packages. Each add-on's repository publishes its own artifact the same way (`variableeconomy:market:<version>` and so on), and its guide says how to use it.

### Gradle

Compile against core, and put it on the dev runtime so it runs in your dev client and server. The `localRuntime` configuration below (the NeoForge MDK template has it) keeps it out of your published POM, so your users don't inherit it as a runtime dependency.

Groovy (`build.gradle`):

```groovy
repositories {
    exclusiveContent {
        forRepository { maven { url = 'https://cursemaven.com' } }
        filter { includeGroup 'curse.maven' }
    }
}

configurations {
    localRuntime
    runtimeClasspath.extendsFrom localRuntime
}

dependencies {
    compileOnly 'curse.maven:variable-economy-1818087:9710072'
    localRuntime 'curse.maven:variable-economy-1818087:9710072'
}
```

Kotlin (`build.gradle.kts`):

```kotlin
repositories {
    exclusiveContent {
        forRepository { maven("https://cursemaven.com") }
        filter { includeGroup("curse.maven") }
    }
}

val localRuntime: Configuration by configurations.creating
configurations.runtimeClasspath { extendsFrom(localRuntime) }

dependencies {
    compileOnly("curse.maven:variable-economy-1818087:9710072")
    localRuntime("curse.maven:variable-economy-1818087:9710072")
}
```

From your local Maven instead (after `gradlew publishToMavenLocal` here), the repository is `mavenLocal()` with `includeGroup 'variableeconomy'`, and core is `variableeconomy:core:0.6.3`; that one comes with its sources and javadoc.

- The `exclusiveContent` block takes only that group from that repository, and nothing else from it.
- The published POM names no dependencies. Core's own optional dependencies (FTB Teams, ProgressiveStages, KubeJS) aren't pulled in: add one to your dev runtime yourself if your add-on works with it. An add-on you build against too (the market, say) is a dependency of its own, next to core.
- To try your add-on against unreleased changes, run `gradlew publishToMavenLocal` on the branch you want instead of a tag; it publishes that branch's `mod_version` from `gradle.properties`.

### neoforge.mods.toml

Declare core in your mod's `META-INF/neoforge.mods.toml`, replacing `yourmod` with your mod ID:

```toml
[[dependencies.yourmod]]
    modId="variable_economy"
    type="required"        # or "optional"
    versionRange="[0.6,)"
    ordering="AFTER"
    side="BOTH"
```

- Variable Economy's mods must be installed on the server and on every client, so use `side="BOTH"`.
- `ordering="AFTER"` makes NeoForge construct core before your mod, so its API is ready in your mod's constructor.
- Since the API may change in a 0.x minor release, you can cap the range at the next minor version, such as `[0.6,0.7)`, and raise it once you've checked the changelog.
- An add-on you use is declared the same way, with its own mod ID (such as `variable_economy_market`), usually as `optional`.

### An optional dependency

With `type="optional"`, your mod must load without that mod. The add-ons are optional on every server, so this applies to them even when your mod requires core. Java loads a class's referenced types when the class is first used, so:

1. Check `ModList.get().isLoaded("variable_economy")` (or the add-on's mod ID, such as `variable_economy_market`) before touching anything of that mod's.
2. Keep every reference to Variable Economy's types (calls, event types, lambdas) in a class of its own that is loaded only after that check passes.
3. Register your event listeners from that class with `NeoForge.EVENT_BUS.addListener`, not with `@EventBusSubscriber`: NeoForge loads a subscriber class whether or not the mod is there.

The [worked example](#worked-example) is set up this way. Use one class per mod you touch, each behind its own check: code using `variableeconomy.market.api` checks `variable_economy_market`, and likewise `variable_economy_contracts` and `variable_economy_exchange` for their events.

## Without Java: commands

The full list is in the admin guide's [Commands](admin-guide.md#commands-bank). These are core's useful ones for scripts:

| Command | Permission | Use |
| --- | --- | --- |
| `/bank admin money balance <player>` | 2 | Returns the player's balance, capped at 2,147,483,647. |
| `/bank admin money give <player> <amount>`, `take <player> <amount>` | 2 | Gives or takes money; returns the amount moved, 0 on failure. See [Giving and taking money](admin-guide.md#giving-and-taking-money). |

Functions and command blocks run at level 2, so they can use them. Each command's return value works with `execute store`:

```mcfunction
execute store result score @p balance run bank admin money balance @p
bank admin money give Steve 500
```

KubeJS scripts can also use the mod's [KubeJS bindings](admin-guide.md#kubejs) for balances and payments, and run the commands above. The add-ons have script commands of their own, such as the market's `/market sell` and `/market buy`; see their guides.

## The Java API

Core's entry point is `variableeconomy.core.api.CoreApi`. It hands out services as static getters:

| Call | Service |
| --- | --- |
| `CoreApi.accounts()` | `Accounts`: reading accounts, and each player's active account. |
| `CoreApi.money()` | `Money`: balances, payments, charges and transfers. |
| `CoreApi.itemGate()` | `ItemGate`: which items each player has unlocked. |
| `CoreApi.netWorth()` | `NetWorth`: money you hold for accounts, for the leaderboard. |
| `CoreApi.audit()` | `Audit`: the admin audit log, for your own admin commands. |

Each service is one object for the whole game, so you may keep it in a field. Its methods take the server or player they act on. The services are ready once core has been constructed, which NeoForge does before any mod that depends on it with `ordering="AFTER"`; called earlier, the getters throw `IllegalStateException`. The market's entry point, `MarketApi`, is in the [market's guide](https://github.com/The-Azotus/variable-economy-market/blob/main/docs/api.md).

`CoreApi` also has a few helpers, all safe on any thread:

| Method | Returns |
| --- | --- |
| `Component formatMoney(long amount)` | The amount as chat shows it, such as "1,234 bits", translated on the player's side. Works even before Variable Economy is constructed. |
| `boolean isCoin(Item item)` | Whether the item is one of this mod's eight coins, or one of Create: Numismatics' coins should a pack have that mod. Coins are never market, exchange or contract items, since selling them would create money from nothing. If your add-on pays for items, refuse coins too. |
| `boolean isPlain(ItemStack stack, Item item)` | Whether the stack is a plain copy of the item: not enchanted, damaged or renamed. Data the server's `ignored_components` setting lists doesn't count. Every module takes items for money only as plain copies. |

The javadoc has the details; the sections below give the shape and the gotchas.

### Concepts

- **Bits.** Every amount and price is a whole number of bits, the unit of the mod's bank and coins, as a `long`.
- **Accounts.** Money lives in accounts, named by an `AccountId`. Every call that moves money names the account. Most of the time you want a player's **active account**, `CoreApi.accounts().active(player)`: the one they spend from and are paid into.
- **Reasons.** Every money movement names a `Reason`: an ID in your namespace, such as `yourmod:quest_reward`, and optionally the player who caused it. It shows in the audit log and in [balance events](#events).
- **The server thread.** Every method that reads or changes accounts, money or item locks must run on the server thread, and throws `IllegalStateException` otherwise. The bank isn't thread-safe. From a packet handler, use `IPayloadContext.enqueueWork`; from another thread, `server.execute`. The methods that may run on any thread say so below and in the javadoc: the service getters, `CoreApi`'s helpers, `Accounts.personal`, `ItemGate.addRule`, `removeRule` and `canLockAnything`, and `NetWorth.register` and `unregister`.
- **Every check before any change.** A call either does everything or changes nothing. Only `OK` changed anything; every other result left the accounts and the player's inventory as they were.
- **Results, not exceptions.** Money calls return a `PaymentResult` with a [`BankResult`](#results); account calls return a `BankResult` (or, for `create`, an `AccountResult`). Each result type has `ok()`. Exceptions are only for programming errors: the wrong thread, a null argument, or (for `PlayerItems`) a quantity below 1.
- **Amounts and balances are `long`.** One call can move any amount a balance can hold; `TOO_LARGE` means only that a balance or total would overflow.

### Accounts

Every player has one **personal account**. Its ID holds the player's UUID, so `CoreApi.accounts().personal(uuid)` gives it on any thread, online or not. The account itself exists once the server has seen the player: when they first join, or when a `ServerPlayer` for them is first handed to core. Before that, money calls for it answer `NO_ACCOUNT`.

The other accounts:

- **Named accounts** (kind `NAMED`), which players open when the server allows extra accounts (`accounts.extra_enabled`). Each has a name unique among named accounts, one owner, and, when the server allows shared accounts (`accounts.shared_enabled`), members (`MEMBER`) and deposit-only members (`DEPOSIT_ONLY`), kept in the order they joined. When the owner leaves, the longest-standing member becomes owner. An empty one can be deleted.
- **Party accounts** (kind `PARTY`), one per FTB Teams party when FTB Teams is installed and `accounts.ftb_party_account` is on. Its members follow the party: the party's owner is `OWNER`, everyone else `MEMBER`. It ends when the party does (or when the server turns party accounts off), and its balance goes to the party's owner. A party account's ID is its own, not the party's.

A player's active account is the one they picked (in the bank screen, `/bank account use`, or `setActive`) while they may still spend from it, otherwise their personal one.

| Method | Thread | Does |
| --- | --- | --- |
| `AccountId personal(UUID player)` | any | The player's personal account's ID. |
| `Optional<String> name(MinecraftServer server, UUID player)` | server | The player's name, online or not: the online player's, the server's name cache, then the name core remembered at their last login. Empty only for a player the server has never seen. Since 0.6.3. |
| `AccountId active(ServerPlayer player)` | server | The account the player spends from and is paid into. Always one they may spend from. |
| `BankResult setActive(ServerPlayer player, AccountId id)` | server | Makes another account the player's active one. Remembered across restarts. |
| `Optional<AccountInfo> get(MinecraftServer server, AccountId id)` | server | The account as it is now: kind, name, owner, members and balance. Empty if there's no such account, or it has ended. |
| `List<AccountInfo> accessibleBy(MinecraftServer server, UUID player)` | server | Every account the player may use, personal first. |
| `Optional<AccountRole> role(MinecraftServer server, AccountId id, UUID player)` | server | What the player may do with the account, or empty if they aren't a member. |

- An `AccountInfo` is a snapshot. It doesn't follow later changes: read it again, or listen for [events](#events).
- `AccountRole` has `canDeposit()` (every role), `canSpend()` (owner and members) and `canManage()` (owner only). A third role, `DEPOSIT_ONLY`, belongs to shared accounts.
- An `AccountId` never changes and is never reused, even after its account ends, so you may save it. Its `toString()` is the UUID, which `UUID.fromString` reads back.
- `create`, `delete`, `setMember` and `removeMember` change named accounts, with the server's authority but under its rules: `create` answers `DISABLED` while extra accounts are off, `LIMIT_REACHED` at `accounts.max_per_player`, `INVALID_NAME` or `NAME_TAKEN`; adding a member answers `DISABLED` while shared accounts are off and `LIMIT_REACHED` at `accounts.max_members`. `create` returns an `AccountResult`: the `BankResult` and, on `OK`, the new account's ID. `delete` needs an empty account: `NOT_EMPTY` while it holds money, money is owed to it, or a net-worth contributor holds money for it. Personal accounts answer `PERSONAL_ACCOUNT`, party accounts `NOT_ALLOWED`. Making a member `OWNER` hands the account over; the owner can't be given another role directly (`NOT_ALLOWED`). Players accept invitations in the game; the API adds members directly, so ask them first.

### Money

| Method | Does |
| --- | --- |
| `OptionalLong balance(MinecraftServer server, AccountId account)` | The account's balance. Empty if there's no such account, or the bank can't be reached. |
| `PaymentResult pay(MinecraftServer server, AccountId account, long amount, Reason reason)` | Pays new money into the account. |
| `PaymentResult charge(MinecraftServer server, AccountId account, long amount, Reason reason)` | Takes money from the account, all or nothing, and destroys it. |
| `PaymentResult transfer(MinecraftServer server, AccountId from, AccountId to, long amount, Reason reason)` | Moves money from one account to another, all or nothing. Nothing is created or destroyed. |
| `boolean available(MinecraftServer server)` | Whether the bank can be reached. True today, since the mod's own bank always can. |

All server thread only. Paying a player's active account, and charging it:

```java
private static final ResourceLocation QUEST_REWARD = ResourceLocation.fromNamespaceAndPath("yourmod", "quest_reward");
private static final ResourceLocation TELEPORT_FEE = ResourceLocation.fromNamespaceAndPath("yourmod", "teleport_fee");

static void reward(ServerPlayer player) {
    AccountId account = CoreApi.accounts().active(player);
    PaymentResult paid = CoreApi.money().pay(player.server, account, 250, Reason.of(QUEST_REWARD));
    if (paid.ok()) {
        player.sendSystemMessage(Component.literal("Quest reward: ").append(CoreApi.formatMoney(250)));
    }
}

static boolean chargeTeleport(ServerPlayer player, long fee) {
    AccountId account = CoreApi.accounts().active(player);
    PaymentResult charged = CoreApi.money().charge(player.server, account, fee, Reason.by(TELEPORT_FEE, player.getUUID()));
    if (charged.result() == BankResult.NO_FUNDS) {
        player.sendSystemMessage(Component.literal("You can't afford the teleport"));
    }
    return charged.ok();
}
```

- `Reason.of(id)` is for a movement no player caused, such as a scheduled reward; `Reason.by(id, player)` names the player who caused it, such as a purchase they made. Keep your set of reason IDs small and fixed.
- `amount` must be at least 1: zero or less is `INVALID_AMOUNT`. `TOO_LARGE` comes back when a payment would push a balance past what a `long` holds.
- `charge` and `transfer` return `NO_FUNDS` when the account can't cover the amount.
- An account that doesn't exist, or has ended, answers `NO_ACCOUNT`, and nothing moves. To pay a player who may be offline, use `personal(uuid)`: that works for any player the server has seen. When you pay money back to an account that may have ended (a refund, a sale's takings), pay the player's personal account instead on `NO_ACCOUNT`.
- **These calls act with the server's authority.** They don't check whether a player may spend from the account. When acting for a player on any account but their active one, check `role(...)` and `canSpend()` first.
- Every change to a balance posts a `BalanceChangedEvent` at the end of the tick, and an online player's open screen of any Variable Economy mod shows the new balance. None of these calls sends the player a message: tell them yourself.
- Every `pay` counts as money created and every `charge` as destroyed in core's `/bank admin stats`, under the reason's ID; transfers count as neither. They don't count in an add-on's own stats, such as the market's `/market admin stats`, which counts the market's own trades.
- Every change writes a line to the account's history (`/bank history`), with the reason and the player who caused it.

`PaymentResult` holds `result`, `amount` (the amount asked for) and `owed` (true when a payment is owed rather than paid; always false for a charge or a transfer). `ok()` is true when the money moved, or is owed.

#### The audit log

Each successful `pay`, `charge` or `transfer` your add-on makes writes one line to the server's [audit log](admin-guide.md#audit-log): action `api pay`, `api charge` or `api transfer`, the account (a player by name and UUID, or a team), the amount, and `ok` or `owed: bank unreachable`. The actor is `api:<reason>`, or `<player> for <reason>` when the reason names a player.

The log is size-capped and shared with admin actions, so pay many small rewards in one call rather than one per event. Variable Economy's own routine movements (trades, contract and exchange payments, coin deposits and withdrawals) use reasons in its own namespaces (`variable_economy`, and each add-on's own, such as `variable_economy_market`) and write no line, so they don't push admin actions out of the log. Their reason paths aren't part of the API.

#### The money rule

`pay` **creates** money and `charge` **destroys** it. The market itself has no balance: sales create money and purchases destroy it, and its markup, fees and price impact make sure that buying N items and selling them straight back never makes a profit.

So an add-on that gives money for items must do it one of two ways:

- **Sell them through the market**, with `MarketApi.trading().sell(...)`. The market's price impact and fees apply.
- **Take them and call `pay`, but pay less than `MarketApi.prices().lowestBuyCost(server, item, quantity)`** whenever that has a value. That is the least buying those units from the market could ever cost, so buying them to hand in can't profit. An empty result means the market sets no bound: the item isn't listed, or the market doesn't sell it.

Both calls are the market's API, so check that the market is installed first (see [An optional dependency](#an-optional-dependency)); the [market's guide](https://github.com/The-Azotus/variable-economy-market/blob/main/docs/api.md) describes them. Without the market there's no market price to profit from, but a pack may add it later.

Never pay the market's sell price, or any price of your own, for items the market sells cheaper. A shop block, a trade-in quest or a "sell everything" button that did would let players buy from the market and turn the items in for more, creating money without end. Refuse coins (`CoreApi.isCoin`) and take only plain copies (`CoreApi.isPlain`).

Use `pay` freely for rewards that cost the player no items: a finished quest, a daily login bonus, a boss kill. Use `charge` for fees and services: a teleport, a claim, a repair.

#### Owed payments

The mod's own bank is always reachable, so today a `pay` is always paid at once. The API still defines what happens when the bank can't be reached, so add-ons needn't change should that become possible. When `pay` can't reach the bank:

- The money is owed instead: the result is `OK`, and `owed()` is true.
- Owed money is saved with the world (`variable_economy_owed_payments`), so a restart doesn't lose it.
- It's paid on the server tick as soon as the bank is back, tried again at most every 10 seconds after a failure.
- If the account ends before then, the personal account of whoever owned it when it ended is paid.
- Meanwhile it counts on the leaderboard.

`charge` and `transfer` are never owed: they're refused with `BANK_UNAVAILABLE`.

### Item locks

Add-ons can lock items for players, as ProgressiveStages does with its item locks (see [ProgressiveStages](admin-guide.md#progressivestages-hiding-locked-items)). Every module respects the locks: no module lets a player trade, claim or post an item locked for them. Today the market hides a locked item from that player, contracts for it are shown to them greyed out and can't be claimed (one they already hold stays theirs), and the exchange hides it and refuses orders, purchases and fills for it. Prices and stock stay shared: a locked item still moves in price when others trade it. Admin commands and the server console see every item.

| Method | Thread | Does |
| --- | --- | --- |
| `void addRule(ResourceLocation id, GateRule rule)` | any | Adds a rule, replacing any rule with the same ID. |
| `boolean removeRule(ResourceLocation id)` | any | Removes it; returns whether there was one. |
| `void refresh(ServerPlayer player)` | server | Call when a rule's answers changed for this player. |
| `void refreshAll(MinecraftServer server)` | server | Call when a rule's answers may have changed for anyone. |
| `boolean isLocked(ServerPlayer player, Item item)` | server | Whether any rule, ProgressiveStages included, locks the item for the player. |
| `boolean canLockAnything()` | any | Whether any rule exists, or ProgressiveStages is installed. When false, every item is unlocked for everyone. |
| `boolean hasStage(ServerPlayer player, String stage)` | server | Whether the player has a ProgressiveStages stage. True when ProgressiveStages isn't installed, or its checks failed and were turned off. |

- A `GateRule` is a functional interface, `boolean locks(ServerPlayer player, Item item)`: true for an item the player must not trade, claim or post. An item is locked when any rule locks it.
- **Keep the rule cheap and free of side effects.** It runs on the server thread for every listed item each time a module sends a player its list, and on every trade. Check the cheap condition (is this one of my items?) first.
- A rule that throws is removed and logged, and the items it locked are unlocked again.
- Rules aren't saved. Add them while your mod loads (the mod constructor is fine) or when the server starts.
- After `addRule`, `removeRule`, `refresh` or `refreshAll`, an `ItemGateChangedEvent` is posted at the end of the tick, once per player however many calls you made, and each module sends the player what they may now see.

### Net worth

The leaderboard ranks players (or, if the server says so, accounts) by their balances plus money add-ons hold for their accounts: escrow for open orders, deposits, payments owed. If your add-on holds money that belongs to players but isn't in their accounts, register a contributor so it counts:

```java
CoreApi.netWorth().register(ResourceLocation.fromNamespaceAndPath("yourmod", "bounties"),
        server -> BountyData.get(server).heldByAccount()); // a Map<AccountId, Long>
```

- `register` replaces any contributor with the same ID; `unregister` removes it. Both work on any thread, also while mods load.
- The contributor is called on the server thread each time the leaderboard is ranked, so it must be cheap and change nothing. One that throws is logged and counts as nothing.
- Count only money outside every account. Money still in an account is already counted.
- Key each amount by the account it will be paid into; if that account has ended, by the account that will now get it (usually a player's personal account). Amounts of zero or less, and accounts that no longer exist, are ignored.
- Ranked by owner (the default), an amount counts for whoever owns the account it's keyed by; ranked by account, for that account's line.
- An account can't be deleted while a contributor holds money for it.

### Audit log for your admin commands

Money calls write their own audit lines. For other actions an operator or the console takes with your add-on's admin commands, such as cancelling a player's bounty, write a line yourself:

```java
CoreApi.audit().record(context.getSource(), "bounty cancel", "#" + id, "ok", null, null);
```

`record(source, action, target, result, before, after)` names who ran the command, what was done (a few words), what it was done to, how it ended (`ok`, or `refused: ` and why), and optionally the target before and after as JSON. Server thread only. Writing never fails your command: a problem with the file is only logged. Admins read the log with `/bank admin audit`. `countSince(server, instant)` counts the lines logged since a moment (at most 1,000).

### Player inventories

`PlayerItems` (a class of static methods in `variableeconomy.core.api`) takes items from a player and gives items to them the way every module does: only the main inventory counts, and nothing is ever dropped.

| Method | Does |
| --- | --- |
| `long count(Player player, Predicate<ItemStack> matches)` | How many matching items the main inventory holds. |
| `void remove(Player player, Predicate<ItemStack> matches, int quantity)` | Removes exactly that many matching items, from the first slot on. |
| `long room(Player player, ItemStack like)` | How many copies of `like` (item and data; its count is ignored) fit. |
| `void insert(Player player, ItemStack like, int quantity)` | Puts that many copies in, topping up matching stacks first. |

Check first, then change: `count` before `remove`, `room` before `insert`. Both changes check again and throw `IllegalArgumentException`, changing nothing, if you skipped the check. Server thread only. For items you pay for, match plain copies: `stack -> CoreApi.isPlain(stack, item)`.

### Results

`BankResult` is core's result for money and account calls. Only `OK` changed anything.

| Value | From | Means |
| --- | --- | --- |
| `OK` | all | Done. For `pay`, possibly owed: check `owed()`. |
| `INVALID_AMOUNT` | `pay`, `charge`, `transfer` | An amount of zero or less. |
| `TOO_LARGE` | `pay`, `charge`, `transfer` | A balance that would overflow. |
| `NO_FUNDS` | `charge`, `transfer` | The account can't cover it. |
| `NO_ACCOUNT` | money calls, `setActive` | There's no such account, or it has ended. |
| `NOT_ALLOWED` | `setActive`, `transfer`, account management | The player can't spend from that account; a transfer names the same account twice; deleting a party account or changing its members; the last member leaving (delete it instead); giving the owner another role. |
| `BANK_UNAVAILABLE` | `charge`, `transfer` | The bank can't be reached. The mod's own bank always can, so this isn't returned today. |
| `DISABLED` | `create`, `setMember` | The server doesn't allow extra accounts (`create`) or shared accounts (adding a member). |
| `LIMIT_REACHED` | `create`, `setMember` | The owner already owns `accounts.max_per_player` accounts, or the account has `accounts.max_members` members. |
| `NOT_EMPTY` | `delete` | The account holds money, money is owed to it, or a contributor holds money for it. |
| `PERSONAL_ACCOUNT` | `delete`, `setMember`, `removeMember` | A personal account can't be deleted or shared. |
| `NOT_A_MEMBER` | `removeMember` | The player isn't a member. |
| `INVALID_NAME`, `NAME_TAKEN` | `create` | The name isn't one word of 1 to 24 letters, digits, `_` and `-`; or another named account has it, ignoring case. |

`PaymentResult` (see [Money](#money)) wraps a `BankResult` with the amount asked for and whether it was owed; `AccountResult` (from `create`) wraps one with the new account's ID. The market's `MarketResult` for trades is in the [market's guide](https://github.com/The-Azotus/variable-economy-market/blob/main/docs/api.md).

Handle the values you care about and treat the rest as "didn't happen": a later version may add values.

## Events

Variable Economy posts its events on `NeoForge.EVENT_BUS` (the game bus). Amounts are in bits. Core's are in `variableeconomy.core.api.event`; the add-ons' events (market trades and catalog reloads, contracts, exchange trades) are in their own guides.

| Event | Fires | Fields (getters) |
| --- | --- | --- |
| `BalanceChangedEvent` | An account's balance changed, for any reason: trades, payouts, coin deposits and withdrawals, transfers, admin commands, add-ons. At the end of the current or next tick, once per account, listing every change since its last event. | `getServer`, `getAccount`, `getBefore`, `getAfter`, `getChanges` (a list of `Change(amount, reason)`, in order; positive adds, negative takes) |
| `AccountEvent.Created` | An account was created: a personal account the first time the server sees its player, a named account when it's opened, a party account when core first sees its party. Once per account, ever. | common fields, plus `getKind`, `getOwner`, `getName` |
| `AccountEvent.Deleted` | An account ended: a named account deleted, or a party account whose party ended. Any balance was moved first. | common fields, plus `getBalanceMoved`, `getBalanceTo` (`Optional<AccountId>`; empty when there was none) |
| `AccountEvent.MemberAdded` | A player joined an account. | common fields, plus `getPlayer`, `getRole` |
| `AccountEvent.MemberRemoved` | A player left an account. | common fields, plus `getPlayer`, `getRole` (the role they had) |
| `AccountEvent.RoleChanged` | A member's role changed, ownership passing included. When an owner leaves, their `MemberRemoved` comes first, then `RoleChanged` for the new owner. | common fields, plus `getPlayer`, `getOldRole`, `getNewRole` |
| `ItemGateChangedEvent` | Which items are locked may have changed: a player's ProgressiveStages stages, or a rule added, removed or refreshed. At the end of the tick, at most once per player. | `getServer`, `getPlayer` (`Optional<ServerPlayer>`; empty means everyone) |

The common account fields are `getServer` and `getAccount`. Listen for `AccountEvent` itself to hear all five.

Rules for all of Variable Economy's events, core's and the add-ons':

- **Posted after the change is complete.** Money and items have moved and the saved data is updated. When one action changes several things, its events are collected and posted together after the last change, never in the middle.
- **Balance events wait for the end of the tick.** So an action with several steps (a market purchase takes the money, then gives the items) has finished before you hear of it, and an add-on's own event for it, such as the market's trade event, comes before the account's `BalanceChangedEvent`. Work queued between ticks, such as a packet's, is posted at the end of the next tick. Changes made while the server stops are posted before it saves. The account may have ended by the time its event is posted.
- **Never cancellable.** To stop a trade before it happens, lock the item with an [item lock rule](#item-locks), or decide in your own code whether to call the API.
- **Only for changes that happened**, and always on the server thread, so a listener may call the API.
- **A listener that throws is logged and ignored**, so it can't undo or interrupt Variable Economy's work. Listeners after it on the same event may then not run.
- A listener that moves money or trades through the API causes further events. Guard against looping.

To subscribe, add a listener:

```java
NeoForge.EVENT_BUS.addListener((BalanceChangedEvent event) -> { /* ... */ });
```

or, with a required dependency, a subscriber class on the game bus:

```java
@EventBusSubscriber(modid = "yourmod")
public final class EconomyListeners {
    @SubscribeEvent
    public static void onAccountCreated(AccountEvent.Created event) {
        // ...
    }
}
```

## Worked example

A quest mod with an optional dependency on Variable Economy's core. It pays quest rewards and locks nether items until the player has been to the Nether.

The mod class checks for core and touches nothing of it:

```java
package com.example.questcoins;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

@Mod(QuestCoins.MOD_ID)
public final class QuestCoins {
    public static final String MOD_ID = "questcoins";
    /** Check this before calling anything in EconomyHooks. */
    public static final boolean ECONOMY_LOADED = ModList.get().isLoaded("variable_economy");

    public QuestCoins(IEventBus modBus) {
        if (ECONOMY_LOADED) {
            EconomyHooks.register();
        }
    }
}
```

Every reference to core's API is in one class, loaded only when core is there. The rest of the quest code calls it only behind the same check, for example `if (QuestCoins.ECONOMY_LOADED) EconomyHooks.rewardQuest(player, 100);`.

```java
package com.example.questcoins;

import com.mojang.logging.LogUtils;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.api.PaymentResult;
import variableeconomy.core.api.Reason;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import org.slf4j.Logger;

import java.util.Set;

/** Everything that touches Variable Economy's core. Loaded only when it is installed. */
final class EconomyHooks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation QUEST_REWARD = ResourceLocation.fromNamespaceAndPath(QuestCoins.MOD_ID, "quest_reward");
    private static final ResourceLocation NETHER_LOCK = ResourceLocation.fromNamespaceAndPath(QuestCoins.MOD_ID, "nether_lock");
    private static final ResourceLocation ENTER_NETHER = ResourceLocation.withDefaultNamespace("story/enter_the_nether");
    private static final Set<Item> NETHER_ITEMS = Set.of(Items.BLAZE_ROD, Items.QUARTZ, Items.NETHERITE_SCRAP);

    private EconomyHooks() {
    }

    static void register() {
        // Safe while mods load: rules may be added from any thread.
        CoreApi.itemGate().addRule(NETHER_LOCK, EconomyHooks::locksNetherItem);
        NeoForge.EVENT_BUS.addListener(EconomyHooks::onAdvancement);
    }

    /** Runs very often, so the cheap check comes first. */
    private static boolean locksNetherItem(ServerPlayer player, Item item) {
        if (!NETHER_ITEMS.contains(item)) {
            return false;
        }
        AdvancementHolder nether = player.server.getAdvancements().get(ENTER_NETHER);
        return nether != null && !player.getAdvancements().getOrStartProgress(nether).isDone();
    }

    /** The rule's answer changed for this player: each module sends them what they may now see. */
    private static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (event.getAdvancement().id().equals(ENTER_NETHER) && event.getEntity() instanceof ServerPlayer player) {
            CoreApi.itemGate().refresh(player);
        }
    }

    /** Called by the quest code, on the server thread, when a player finishes a quest. */
    static void rewardQuest(ServerPlayer player, long reward) {
        // A reward costs the player no items, so paying it is fine (see "The money rule").
        AccountId account = CoreApi.accounts().active(player);
        PaymentResult result = CoreApi.money().pay(player.server, account, reward, Reason.of(QUEST_REWARD));
        if (!result.ok()) {
            LOGGER.warn("Quest reward {} for {} refused: {}", reward, player.getGameProfile().getName(), result.result());
            return;
        }
        Component money = CoreApi.formatMoney(reward);
        player.sendSystemMessage(result.owed()
                ? Component.literal("Your reward of ").append(money).append(" will be paid when the bank is back")
                : Component.literal("Quest reward: ").append(money));
    }
}
```

To use the market as well (show an item's price, sell turned-in items for a player, count items sold for a quest), add a second class for everything that touches `variableeconomy.market.api`, behind its own check of `variable_economy_market`, as the [market's guide](https://github.com/The-Azotus/variable-economy-market/blob/main/docs/api.md) describes.

## Changelog

- **0.6.3 (unreleased):** new `Accounts.name(server, player)`, a player's name from everything core knows, including the names it remembers for players vanilla's name cache has forgotten. An add-on that calls it requires core 0.6.3 or later in its `neoforge.mods.toml`.

- **0.6.2 (2026-11-21):** nothing in the API changed.

- **0.6.1 (2026-11-17):** core and each add-on are separate repositories and releases; nothing in the API changed. `gradlew publishToMavenLocal` in this repository publishes `variableeconomy:core:<version>`, with sources and javadoc jars; its POM names no dependencies. The released mods are on CurseForge. The JitPack coordinates 0.6.0 names below (`com.github.The-Azotus.variable-economy:<artifact>:<tag>`) never worked: its build of `v0.6.0` never succeeded, and nothing is published on JitPack.

- **0.6.0 (2026-11-14):** breaks every add-on. The mod is renamed, split into four mods, and its API is replaced.

  The split into four mods:
  - core is `variable_economy`; the market, contracts and exchange are separate optional mods, `variable_economy_market`, `variable_economy_contracts` and `variable_economy_exchange`. Check an add-on's mod ID before using its package.
  - one artifact each, `com.github.The-Azotus.variable-economy:<artifact>:<tag>` with the artifact `core`, `market`, `contracts` or `exchange`.
  - each add-on's blocks, items, menus, packets and money reasons are in its own namespace (for example `variable_economy_market:market_sale` for a sale's `Reason`); the market's catalog data, item classes and `config/variable_economy/` folder keep the `variable_economy` namespace.
  - each mod has its own config file and command root: core's admin commands moved to `/bank admin` (`money`, `audit`, `info`, joined by the new `accounts`, `history` and `stats`), contracts' to `/contracts`, the exchange's to `/exchange`.

  The rename from Dynamic-Economy to Variable Economy:
  - the mod id is `variable_economy` (was `dynamic_economy`), for `[[dependencies]]` in `neoforge.mods.toml` and `ModList.isLoaded`;
  - the coordinates were `com.github.The-Azotus:dynamic-economy:<tag>` (now split, above);
  - data and config live under the new id: `data/variable_economy/` for bare class names, `config/variable_economy/`, and the saved data for owed payments, now `variable_economy_owed_payments` (was `dynamic_economy_api_payments`; payments owed by a 0.5 world aren't carried over).

  The API: the old package `variableeconomy.api` (`dynamiceconomy.api` in 0.5), its `event` package and its facade `VariableEconomyApi` (`DynamicEconomyApi` in 0.5) are removed. They're replaced by `variableeconomy.core.api` (accounts, money, item locks, net-worth contributors, the audit log) and `variableeconomy.market.api` (prices and trading), each with an `event` package, as the mod splits into core, market, contracts and exchange. The main renames:
  - `VariableEconomyApi.pay(player, amount, reason)` → `CoreApi.money().pay(server, CoreApi.accounts().active(player), amount, Reason.of(reason))`, and likewise `charge`. The `(server, uuid, ...)` forms → the same calls with `CoreApi.accounts().personal(uuid)`.
  - `VariableEconomyApi.balance(player)` → `CoreApi.money().balance(server, CoreApi.accounts().active(player))`; `balance(server, uuid)` → the same with `personal(uuid)`.
  - Paying, charging or reading the balance of a player the server has never seen now answers `NO_ACCOUNT` (or an empty balance), where the old API created an empty account.
  - `VariableEconomyApi.quote` and `listedItems` → `MarketApi.prices()`; `sell` and `buy` → `MarketApi.trading()`.
  - Visibility rules → item lock rules on `CoreApi.itemGate()`: they now *lock* items rather than hide them, and each module decides how a locked item looks. `addVisibilityRule(id, BiPredicate)` → `addRule(id, GateRule)`; `removeVisibilityRule` → `removeRule`; `refreshVisibility(player)` → `refresh(player)`, and `refreshVisibility(null)` → `refreshAll(server)`; `isHidden` → `isLocked`.
  - `VariableEconomyApi.bankAvailable(server)` → `CoreApi.money().available(server)`.
  - `VariableEconomyApi.formatMoney(server, amount)` → `CoreApi.formatMoney(amount)`, which works on any thread.
  - `VariableEconomyApi.isCoin` → `CoreApi.isCoin`.
  - The single `MarketResult` is split: core's `BankResult` for money and accounts, and the market's own `MarketResult` for trades. `INVALID_AMOUNT` is now only in `BankResult`; `PRICE_CHANGED` is gone, since the API always trades at the current price; `MarketResult` gains `NOT_ALLOWED` and `NO_ACCOUNT`.
  - `PaymentResult` moves to `variableeconomy.core.api` and holds a `BankResult`. `TradeResult` gains `account`.
  - The events move: `MarketTradeEvent` and `CatalogReloadedEvent` to `variableeconomy.market.api.event`, `ContractEvent` to `variableeconomy.contracts.api.event`, `ExchangeTradeEvent` to `variableeconomy.exchange.api.event`. `MarketTradeEvent` gains `getAccount()`.
  - An owed payment to an account that has ended now goes to the personal account of whoever owned it.
  - The audit line for a payment names the player as actor when the `Reason` names one.

  - Amounts are `long` throughout: one call moves any amount, where 0.5 refused more than 2,147,483,647 bits at once.
  - Contracts and the exchange are reworked, and their events with them:
    - `ContractEvent` gains `getDefinition()`. `Abandoned` and `Expired` report `getPaid()`, `getDepositReturned()` and `getDepositLost()`, since part of a deposit can come back; `Expired.isDepositReturned()` is gone. `Expired` now fires when a held contract's own time runs out (or contracts are turned off), not when its board is replaced.
    - `ExchangeTradeEvent.getTax()` is now paid by the buyer on top of `getValue()`, which the seller gets whole; `getBuyerPaid()` is new.
    - `MarketQuote` gains `itemClass()`. The internal `MarketPrices.contractTerms` and `ContractTerms` are gone, with the market's `contract_*` catalog fields: contracts come from contract definitions (see the [contracts' guide](https://github.com/The-Azotus/variable-economy-contracts/blob/main/docs/api.md)).
    - A contract's deposit is a net-worth contributor, so an account holding one can't be deleted.
  - Accounts are built: named accounts, shared with members and deposit-only members, and FTB Teams party accounts. `create`, `delete`, `setMember` and `removeMember` work under the server's rules (they answered `DISABLED` in earlier 0.6 builds), and `BankResult` gains `NAME_TAKEN`. `delete` also refuses (`NOT_EMPTY`) while a net-worth contributor holds money for the account. A party account's ID is no longer the party's ID. The `/bank team` teams are gone.

  New:
  - Accounts (`CoreApi.accounts()`): personal, named and party accounts by `AccountId`, `AccountInfo` snapshots, roles, each player's active account, and creating, deleting and sharing accounts.
  - KubeJS bindings, for pack scripts: `VariableEconomy` and `VariableEconomyEvents.balanceChanged` (see the [admin guide](admin-guide.md#kubejs)).
  - `Money.transfer`, which moves money between accounts without creating or destroying any, and `Reason`, which can name the player who caused a movement.
  - `MarketPrices.sellTotal`, `buyTotal`, `normalSellTotal` and `lowestBuyCost`, and `MarketTrading.sell` and `buy` with a named account.
  - `BalanceChangedEvent`, `AccountEvent` (created, deleted, member added, removed, role changed) and `ItemGateChangedEvent`.
  - `ItemGate.canLockAnything` and `hasStage`.
  - Net-worth contributors (`CoreApi.netWorth()`), so the leaderboard counts money your add-on holds.
  - `PlayerItems`, for taking and giving items the way every module does.
  - `Audit`, for your own admin commands' audit lines.
  - `CoreApi.isPlain`.

  Also in 0.6.0: `BankType` and `DynamicEconomyApi.bankType` removed: Create: Numismatics support is gone, and every world uses the mod's own bank, in bits. `Money.available` is always true. Balances are never capped for display (with Numismatics' bank they read at most 1,073,741,823).
- **0.5.0:** first version of the API.
