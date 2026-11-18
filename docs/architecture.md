# Architecture

A map of the code for contributors. For what the mod does, read the [admin guide](admin-guide.md) and the [player guide](player-guide.md) first: the code follows them closely, and their terms (account, active account, owed payment, item lock, net worth) are the ones used here. If you're writing a separate mod that uses this one, read the [add-on guide](api.md) instead.

Variable Economy is four mods (core, market, contracts and exchange), each in its own repository with its own jar, mod id, `mods.toml`, config file, lang file, network channel and command root. This repository is core (`variable_economy`). The add-ons build on it and talk to it only through its API package, `variableeconomy.core.api`; core knows nothing of them.

## Building and testing

Java 21 and NeoForge 21.1 for Minecraft 1.21.1. Gradle's toolchain finds a Java 21 JDK or downloads one, and the Gradle wrapper is in the repository. The build is one convention plugin, `buildSrc/src/main/groovy/variable-economy.mod.gradle`, the same file in each Variable Economy repository; `gradle.properties` says which mod this is (`module`, `mod_id`, `mod_name`, `mod_version`) and holds the shared versions, and `build.gradle` names the optional mods core compiles against and runs with (FTB Teams, ProgressiveStages and KubeJS). `src/main/templates/META-INF/neoforge.mods.toml` is filled in from `gradle.properties`.

- `gradlew build` compiles, runs `checkModuleBoundaries` (see [The module boundary](#the-module-boundary)), and builds the jar, a sources jar and a javadoc jar in `build/libs/`. The javadoc covers only `variableeconomy.core.api` (and `.event`), with doclint on, so a broken link or bad HTML in their javadoc fails the build.
- `gradlew runGameTestServer` runs core's GameTests (61 today) on a GameTest server in `run-gametest/`, then `checkGameTestLog`, which fails if the server loaded other mods of ours than core, or logged an error, or a warning from our code, before its tests began (its report is `build/reports/gameTestLog.txt`). The run loads the optional mods; `-PwithoutMods=all` (or a comma-separated list of `create`, `ftbteams`, `progressivestages`, `kubejs`) leaves them out, in a test world of its own (`run-gametest-without-<mods>/`).
- `gradlew runClient` and `gradlew runServer` start a dev client or server, in `run/`.
- `gradlew publishToMavenLocal` publishes `variableeconomy:core:<mod_version>`, with the sources and javadoc jars, for the add-ons (and other add-on authors) to build against. The POM names no dependencies.

The GameTests live in `src/gametest`, a source set of their own, and never ship in the released jar. A dev run loads the mod from both source sets, so `@GameTestHolder` finds the tests. The run's `config/` starts from `src/gametest/config`: a ProgressiveStages stage locking the dragon egg, which test players never have, so that mod doesn't write its own example stages (whose locks would touch the tests) and the tests can check a real lock. For a Gradle composite build that runs the mods together, the mod and its tests are also offered as one jar under the capability `variableeconomy:core-gametest` (never published).

The test world persists between runs, so tests undo what they change (accounts, config values, item-gate rules) and compare money relatively.

**Writing a test.** Put it in `variableeconomy.core.gametest`. Each class is marked `@GameTestHolder(CoreModule.MOD_ID)` and `@PrefixGameTestTemplate(false)` and imports `CoreTestSupport.*`; each test is a static `@GameTest(template = EMPTY)` method that ends with `helper.succeed()`. From the support class:

- `new CoreFixture(helper)` gives a fresh player with an empty inventory and an empty bank account.
- `recordEvents(body)` records the events posted while `body` runs; `withAccounts(body)` turns extra and shared accounts on while it runs.
- `assertScreenFollowsTheBlock`, `assertRecipeAndFacing`, `assertSolidAndDropsItself` and `assertOwnNamespace` check a module's block and lang file.
- A test that writes files makes its own folder with `Files.createTempDirectory` and removes it with `deleteTree` in a `finally`.

## Packages

All code is under `variableeconomy.core`.

### The shape of every module

Each Variable Economy mod has the same shape, core included:

- `CoreModule`: the mod's `@Mod` class, with `MOD_ID` and `LOGGER`. Its constructor registers the module's config, then its own blocks, items, menu, packets, commands, server events and tick work. `CoreClient` is its client-only `@Mod` class: the screen.
- `CoreConfig`: the mod's server config file, `variable_economy-server.toml`. Its `install` fills `CoreSettings`, which the rest of the module reads. The settings class also decides whether a player may use the module's commands under its `access_mode`.
- One block and one screen: `block` (the block and its registration), `menu` (the menu the screen runs in), `network` (`BankPayloads` with every packet, `BankNetwork` registering them, `BankSync` with what each player is sent and when, and the request handlers) and `client` (the screen, the client's copy of the module's state, and the module's own copy of small helpers such as `PriceFormat`). No screen code is shared between modules.
- `service` for the rules, `data` for what's saved, `command` for its commands, and its own event poster and result enum.

### Core's packages

| Package | What's in it |
| --- | --- |
| `api`, `api.event` | Core's API: `CoreApi` (accounts, money, item gate, net worth, audit, `isCoin`, `isPlain`, `formatMoney`), the `Accounts`, `Money`, `ItemGate`, `NetWorth` and `Audit` interfaces, `PlayerItems`, and their records, enums and events. See [The add-on API](#the-add-on-api). |
| `service` | `CoreServices` holds one of each service and installs them into `CoreApi`. `MoneyService` (the `Money` API: every balance change in the game, which it writes to the history and counts in the money-supply stats), `AccountsService` (the `Accounts` API: personal, named and party accounts, roles, invitations and each player's active account), `ItemGateService` (the `ItemGate` API: ProgressiveStages' locks plus add-ons' rules), `NetWorthService` (net-worth contributors), `BankService` (coins in and out of an account, by the player's role), `Leaderboard` (by owner or by account). `BalanceEvents` collects balance changes and posts one `BalanceChangedEvent` per account at the end of the tick; `CoreEvents` posts core's events. |
| `bank` | Where money lives. `Bank` is the interface, `WorldBank.of(server)` gives the world's bank, `OwnBank` (one balance per account ID, saved with the world). `Party` (an FTB Teams party, as the bridge reports it), `CoinMath` and `Denomination`. Only `MoneyService` changes balances, and `AccountsService.closeAccount` when an account ends. |
| `data` | `AccountRegistry` (every account there has been: kind, name, owner, members and roles in joining order, the party behind a party account, ended or not, and each player's chosen active account), `OwedPayments` (payments made while the bank couldn't be reached, paid by `MoneyService.payOwed` each tick), `TransactionHistory` (each account's newest balance changes), `MoneySupplyStats` (money created and destroyed per reason) and `AuditLog` (a file outside the save). |
| `coin` | The coin items, the Bank Card (it opens the bank screen) and coin recipes. `Coins.isCoinId` also knows Create: Numismatics' coin IDs. |
| `block`, `menu`, `network`, `client` | The Bank block (`BankBlock`), `BankMenu`, `BankPayloads`/`BankNetwork`/`BankSync` (the shown account, history and leaderboard requests) and `AccountRequests` (the Accounts screen's changes, checked as `/bank account` checks them), `BankScreen` (the bank beside the leaderboard or history) and `AccountsScreen`, which stands in for it over the same open menu. |
| `command` | `/bank` (opens the bank screen), `/bank account` (`AccountCommands`), `/bank history` (`HistoryCommands`), `/bank top` (`TopCommand`), `/pay` (`PayCommand`); `/bank admin info` and `accounts` (`AdminBankCommands`), `money` (`AdminMoneyCommands`, through `Money`), `history`, `stats` (`AdminStatsCommands`) and `audit` (`AdminAuditCommands`). |
| `compat` | `FtbTeamsBridge`, `StagesBridge` and `KubeJsBridge` (whose nested `Present` is the KubeJS plugin `kubejs.plugins.txt` names). |

Core registers its commands under `/bank` (with its admin commands under `/bank admin`), plus `/pay`, from its own `RegisterCommandsEvent` listener. Its ids are all in the `variable_economy` namespace: blocks, items, menus, packets, recipes, loot tables, money reasons and lang keys. Each add-on uses its own namespace, and a lang key two modules both show (such as `balance`) is in each one's file under its own namespace.

A refused bank action returns `BankService.Result`; account changes return the API's `BankResult`. Each value needs a line in the lang file, or the screen shows the raw key: `screen.variable_economy.bank.result.<value in lower case>` for the bank screen, `command.variable_economy.account.result.<value in lower case>` for account changes.

Every package except `api` and `api.event` is marked `@ApiStatus.Internal` in its `package-info.java`: add-ons mustn't use it, and it may change in any release. A new package gets a `package-info.java` with the same annotation.

## The module boundary

Modules talk to each other only through API packages, since each is a separate mod that may be installed without the others:

- Core uses only core. It never refers to an add-on; what it knows of them comes through its own API (net-worth contributors, item-gate rules, payments with a `Reason`).
- An add-on uses its own package and `variableeconomy.core.api`, never core's internals. Its `api` packages use only its own `api` and `core.api`.

Gradle's dependencies enforce most of this: core doesn't depend on any add-on. The task `checkModuleBoundaries` (in `variable-economy.mod.gradle`) checks the rest of this repository's side: it scans every source file for references to `variableeconomy.…` and fails the build on any that isn't allowed. Here, main code may use only `variableeconomy.core`, the `api` packages only `variableeconomy.core.api`, and the GameTests (`variableeconomy.core.gametest`) anything in core. `gradlew check`, and so `gradlew build`, runs it. Each add-on's repository runs the same check on its own side.

When an add-on needs something from core that isn't in `core.api`, add it to the API (with javadoc and a changelog line, see [Changing the API](#changing-the-api)) rather than letting the add-on reach into core's internals.

## Rules every change follows

These keep money safe, in core and in every combination of installed add-ons. Each service class repeats the ones it relies on at its top.

- **Server thread only.** Every transaction, payout and bank call runs on the server main thread; the bank isn't thread-safe. The packet handlers in `BankNetwork` run on the main thread (the registrar's default; work from any other thread is handed over with `IPayloadContext.enqueueWork` or `server.execute`), and every service checks: `CoreServices.requireServerThread` for core's API and a `requireServerThread` in `BankService`, which throw `IllegalStateException` from another thread.
- **Check everything, then change.** A transaction makes every check before its first change, and after the first change no step may fail. A coin withdrawal (`BankService`) checks the role, the amount and the room for the coins, then charges the account (the charge is itself the funds check, and changes nothing unless it succeeds), and only then inserts the coins. A deposit counts the coins and checks the balance can take them before it removes them and pays. `/pay` moves the payment and its fee in one all-or-nothing step (`MoneyService.transferWithFee`). A new check goes before the first change, above the `Nothing has changed yet` comment where there is one. The API keeps the same order: a call that answers anything but `OK` changed nothing.
- **Never drop items on the ground.** Every module takes and gives items through `core.api.PlayerItems`: only the hotbar and the 27 storage slots count, and `room` is checked before `insert`, so output never lands on the ground. `remove` and `insert` check again and throw, changing nothing, if a caller skipped the check.
- **Totals in `long`.** Balances and every amount are `long`, worked out with exact arithmetic; a total or balance that would overflow is refused as `TOO_LARGE` before anything changes (`MoneyService` for balances, and each add-on for its own totals).
- **No profit from a round trip, across any combination of installed modules.** Buying N then selling N back must never pay, and nothing that pays out for items may break that. Every add-on that pays out for items checks against the market's prices when the market is installed, and the API's [money rule](api.md#the-money-rule) asks the same of other mods. The only exception is an admin's choice, such as turning off the contracts add-on's cap on payouts, which then warns at every start and reload.
- **Money only through `Money`.** Every balance change goes through core's `Money` API (`CoreApi.money()`, implemented by `MoneyService`), which alone calls the `Bank` interface (`WorldBank.of(server)`): `pay` creates money, `charge` destroys it and is all-or-nothing, `transfer` moves it. Coin deposits and withdrawals (`BankService`), `/pay` (`MoneyService.transferWithFee`, the payment and its fee in one step), `/bank admin money`, KubeJS scripts and every add-on's payments all go through it. Each call names a `Reason`. Payments are never coins handed out: coins only move in and out of the bank through deposit and withdraw. Nothing calls `OwnBank` directly except core's account code (`AccountsService.closeAccount` passing an ended account's balance on, and reading balances by account). Every change goes through `BalanceEvents.changed`, which writes the history line.
- **Owed, never lost.** `Money.pay` to an account the bank can't reach right now is kept in `OwedPayments` and paid by `MoneyService.payOwed` from the server tick; money owed to an account that has ended goes to its last owner's personal account. The own bank is always reachable, but a `Bank` may in principle not be, so `BANK_UNAVAILABLE` and the owed-payment code stay.
- **Coins are never tradable.** Selling a coin would create money from nothing, so no module lists, buys, takes or asks for one, checked through `CoreApi.isCoin`. Create: Numismatics' coins are refused too, by ID (`Coins.isCoinId`), in case a pack still has that mod; the bank doesn't take them either.
- **Respect the item gate.** No module lets a player trade, claim or post an item they haven't unlocked: each asks `CoreApi.itemGate().isLocked` (backed by `ItemGateService`, with ProgressiveStages through `StagesBridge`).
- **Optional mods stay behind a bridge.** Only `compat.FtbTeamsBridge` imports FTB Teams, only `compat.StagesBridge` imports ProgressiveStages, and only `compat.KubeJsBridge` imports KubeJS. Each touches its mod only from a nested `Present` class, so core loads without them, and a missing mod means its feature is simply absent, with no errors or log spam.
- **Events after the change.** An event is posted only once its whole change is complete, through the module's poster (`CoreEvents` here). A change with several steps collects its events and posts them at the end. Balance changes are collected by `BalanceEvents` and posted once per account at the end of the tick. Events are never cancellable. See [The add-on API](#the-add-on-api).
- **The API keeps every rule above.** It only calls the services, checks the server thread, and changes nothing on a refusal.

## The add-on API

`variableeconomy.core.api` (with `.event`) is what other mods, and the add-ons, compile against. [api.md](api.md) is its guide for add-on authors.

The API is interfaces and plain types; the services implement them. `CoreServices.install` puts core's services into `CoreApi` while the mod is constructed. The screens and commands use the same services, so the API adds only:

- a server-thread check on every method that reads or changes money, accounts or locks (`IllegalStateException` otherwise);
- for `Money`: the amount checks (`INVALID_AMOUNT`, `TOO_LARGE`), an audit line for money moved for a reason outside Variable Economy's own namespaces (`MoneyService.ownReason`), and owing a payment the bank can't take yet. Balances reach open screens through `BalanceChangedEvent`.

The API has its own types, so internal ones can change without breaking add-ons. `ItemGateService` holds add-ons' rules next to ProgressiveStages' locks; rules may be added from any thread, so they're in a concurrent map, and a rule that throws is removed.

**Events.** `CoreEvents` puts core's events on `NeoForge.EVENT_BUS`, logging and ignoring a listener that throws. They're posted where the change happens, not in the API, so actions from the screen and commands post them too:

| Event | Posted in |
| --- | --- |
| `BalanceChangedEvent` | `BalanceEvents.flush`, at the end of the server tick (and when the server stops), once per account that changed |
| `AccountEvent.Created`, `Deleted`, `MemberAdded`, `MemberRemoved`, `RoleChanged` | `AccountsService`, after the account or its members changed (party accounts: after FTB Teams reports the change) |
| `ItemGateChangedEvent` | `ItemGateService.flush`, at the end of the server tick, once per player or once for everyone |

The rule: **an event is posted only after its whole change is complete**, as the last step. A listener may call back into the mod, so it must never see half a transaction. When one action makes several changes, collect the events and post them with `CoreEvents.postAll` after the last change. A new event follows the same pattern, is never cancellable, and is posted only for actions that succeeded.

### Changing the API

The `api` packages are `@ApiStatus.Experimental` until 1.0; they may change in a 0.x minor release, but never silently. For any change to a public type or method in them, or to when an event fires:

1. Update the javadoc. `gradlew javadoc` must pass (doclint is on).
2. Update [api.md](api.md): the section that describes it, and a line in its changelog under the version that ships it.
3. Raise `mod_version`: a minor version (0.6 to 0.7) for a change that can break add-ons, at least a patch version otherwise. Each add-on's `mods.toml` requires core from the version it was built against up to the next minor version. Releases are tagged `v<version>`.

Keep internal types out of the API's signatures, and keep every check before the first change, as the services do.

## Design notes

- **One bank, in bits.** Every world uses the mod's own bank (`OwnBank`), and every price and amount is a whole number of bits. Create: Numismatics support was removed in 0.6.0; its coin IDs stay blocked so a pack that still has it can't turn its coins into bits.
- **All payments through an account.** Market trades, contract payouts and deposits, and exchange orders all pay and charge a bank account, never physical coins. The account is the player's active account unless an add-on names another.
- **Core works alone.** Installed without any add-on it is a complete bank mod, and each add-on works with core alone. Core learns about the add-ons only through its API: they register net-worth contributors for the money they hold, add item-gate rules, and pay and charge with reasons in their own namespaces.
- **No difficulty presets.** Admins tune the economy through the add-ons' settings and data, not through presets.
- **Pre-1.0.** Breaking API changes are allowed before 1.0, but each is versioned and listed in the [changelog](api.md#changelog). Worlds from Dynamic-Economy, the mod's name before 0.6.0, aren't carried over.
