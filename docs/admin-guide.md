# Admin guide

This guide is for server admins running Variable Economy's core, the bank every Variable Economy mod builds on. It covers:

- which of the four mods to install
- how players reach the bank
- the bank, coins and accounts: extra and shared accounts, FTB Teams party accounts, `/pay`, each account's history, the leaderboard and money-supply stats
- item locks with ProgressiveStages
- giving and taking money, the audit log and the optional KubeJS bindings
- core's config options and every `/bank` command

For what players see, read the [player guide](player-guide.md). For mods that build on the bank, and for connecting other mods to it with commands or Java, read the [add-on guide](api.md). The market, contracts and exchange each have their own admin guide (linked below).

## The four mods

Variable Economy is four mods. Core is required; the other three are add-ons, and any combination of them works on top of core. Install each one you use on the server and on every client.

| Mod | Jar | Mod id | Adds | Needs | Admin guide |
| --- | --- | --- | --- | --- | --- |
| Core | `variable_economy-<version>.jar` | `variable_economy` | The bank, coins, Bank block and Bank Card, accounts, `/pay`, account history, the leaderboard, money-supply stats, the audit log, `/bank` | NeoForge | This one |
| Market | `variable_economy_market-<version>.jar` | `variable_economy_market` | The market, the Market Terminal, catalogs and prices, price tooltips, `/market` | Core | [Market](https://github.com/The-Azotus/variable-economy-market/blob/main/docs/admin-guide.md) |
| Contracts | `variable_economy_contracts-<version>.jar` | `variable_economy_contracts` | The Contract Board, `/contracts` | Core; with the market, contracts paying on market value | [Contracts](https://github.com/The-Azotus/variable-economy-contracts/blob/main/docs/admin-guide.md) |
| Exchange | `variable_economy_exchange-<version>.jar` | `variable_economy_exchange` | The player exchange, the Exchange block, `/exchange` | Core | [Exchange](https://github.com/The-Azotus/variable-economy-exchange/blob/main/docs/admin-guide.md) |

Each add-on needs core of the same minor version (all four 0.6.x, for example), so update them together. Each mod has its own config file in `config/` and its own commands; a mod that isn't installed simply isn't there.

**Removing an add-on** from a world keeps what it holds in its own saved file: contract deposits, buy orders' money, sell orders' and listings' items, and collection boxes. While it's gone that money doesn't count on the leaderboard or toward an account's holdings, so the account it belongs to can be deleted. Put the add-on back and everything returns; anything paid out to an account that has since been deleted goes to the personal account of the player it belonged to. To end an add-on for good, turn it off first so it settles everything (its admin guide says how), let players empty their collection boxes, then remove it.

## Banks and coins

Every payment, both ways, goes through the player's bank account; the market, contracts and exchange never pay out or take coins. Coins only move in and out of the bank in the bank screen, opened at a **Bank** block, with a Bank Card or with `/bank`.

**The bank.** Money is counted in bits and saved with the world (`data/variable_economy_bank.dat`). Balances have no practical limit. `/bank admin info` shows how many personal, named and party accounts hold money, and how much.

**The coins.** The bank screen moves the mod's own coins. Each is worth 8 of the one before: Bit 1, Chip 8, Stack 64, Mark 512, Crown 4,096, Sun 32,768, Divine 262,144, Omega 2,097,152 bits.

**Coins can never be traded for money**: the mod's own, since selling one would create money from nothing, and Create: Numismatics' coins, should your pack have that mod, since selling them would turn its money into bits. No add-on lists, buys or takes them: the market refuses a catalog entry for one, the exchange won't trade them and contracts never ask for them. The bank doesn't take Numismatics' coins either.

**Recipes.** The Bank is 8 Bits around a chest (see [The blocks](#the-blocks)). The Bank Card, which opens the bank screen from anywhere, takes a paper, a gold ingot and a Chip. Replace them with a datapack or KubeJS like any recipe.

### Accounts

Every player has a **personal account**, which always exists and can't be shared or deleted. With the settings under `[accounts]` (see [Server config](#server-config)) players can have more:

- **Extra accounts** (`extra_enabled`, off by default): a player opens named accounts with `/bank account create <name>` or the bank screen's **Accounts…** button, up to `max_per_player` accounts they own (3, their personal one included). Names are one word of letters, digits, `_` and `-`, unique on the server ignoring case. An account can be deleted once it's empty: no balance, nothing owed to it, and nothing an add-on counts as held for it, such as an open exchange buy order or a contract's deposit. Players name accounts in commands by name; `personal` is their own and `party` their FTB Teams party's. A party account shows its party's name without FTB Teams' `#` and ID.
- **Shared accounts** (`shared_enabled`, off by default; needs extra accounts, since only named accounts are shared): an account's owner invites players, who accept with `/bank account join <name>` or in the Accounts screen. Each member is a **member** (deposits, spends and withdraws) or **deposit-only** (can only pay in, and can't read the history). The owner manages members and can hand the account over. When the owner leaves, the member who joined first becomes owner, whatever their role, even past `max_per_player`. Members per account, owner included: `max_members` (8). Invitations last until the server stops.
- **FTB Teams parties** (`ftb_party_account`, on by default; needs FTB Teams): each party gets an account its members share, following the party's members and owner. When a party is deleted its balance goes to its owner. Turning the setting off, or removing FTB Teams, ends every party account at the next start, paying each balance to the party's owner (logged); turning it back on gives parties fresh accounts.

Turning `extra_enabled` or `shared_enabled` off stops new accounts or new members; existing ones keep working, so no money is stranded.

**The active account.** Each player picks one account they may spend from as their active account (in the bank screen, or `/bank account use <name>`). It pays for what they buy, receives what they sell, holds contract deposits and exchange orders, and receives `/pay` payments. When they can no longer spend from it (they left, were made deposit-only, or it was deleted), their personal account is active again. The bank screen can show any of a player's accounts; coins go in and out of the one shown.

### Paying other players

`/pay <player> <amount>` moves money from the sender's active account into the recipient's, online or not, for any player the server has seen. Settings under `[pay]`: `enabled` (on), `fee_percent` (0) and `min_amount` (1). A fee is worked out on the amount, rounded up to a whole bit, paid by the sender on top and destroyed: a money sink. The payment and its fee are one all-or-nothing step. `/pay` works on `BLOCK_ONLY` servers too.

### Account history

Every balance change, whatever made it (trades, contract and exchange payments, coins in and out, `/pay` and its fee, admin commands, add-on and script payments, an account ending), writes a line to its account's history: when, the amount, the reason, who caused it, and the balance after. Each account keeps its newest `history.size` lines (50; 0 keeps none). Owners and members read it in the bank screen's **History** list or with `/bank history [account] [page]`; deposit-only members can't. Admins read any account's with `/bank admin history <player>` (a personal account) or `/bank admin history account <name>` (a named account, or else a party account, by name). The history is saved with the world (`data/variable_economy_history.dat`). It's the record of every money movement; the [audit log](#audit-log) keeps admin actions.

### Worlds from Dynamic-Economy

Up to 0.5 this mod was called Dynamic-Economy, with the mod id `dynamic_economy`. Since 0.6.0 it is four mods, and every id is in the `variable_economy` namespace or an add-on's own (`variable_economy_market`, `variable_economy_contracts`, `variable_economy_exchange`): items, blocks, recipes, item classes, saved data and config files. Worlds from Dynamic-Economy can't be carried over: their market, bank accounts, coins and terminals don't load. A datapack, KubeJS script or config that names the mod's ids needs them changed to the new ones.

## Letting players in

Each mod has its own block, opening its own screen, and a command that opens the same screen from anywhere:

| Block | Screen | Command |
| --- | --- | --- |
| **Bank** | The bank (the player's accounts, balance, coins in and out) beside the [leaderboard](#the-leaderboard) or the shown account's history; **Accounts…** opens the Accounts screen. | `/bank` |
| **Market Terminal** | The market (add-on). | `/market` |
| **Contract Board** | The contract board (add-on). | `/contracts` |
| **Exchange** | The player exchange (add-on). | `/exchange` |

A screen opened from a block closes if the player walks more than about 8 blocks away or the block is broken. The **Bank Card** also opens the bank screen, anywhere, and it stays open while the player carries a card. It can't trade.

Choose whether players may use the commands with `access_mode`. Each mod has its own, in its own config file, so you can, say, keep `/bank` and make the market block-only. Core's is in `config/variable_economy-server.toml` and covers the bank; each add-on's admin guide describes its own (`variable_economy_market-server.toml`, `variable_economy_contracts-server.toml` and `variable_economy_exchange-server.toml`, all in `config/`).

| `access_mode` | Players | Operators and console |
| --- | --- | --- |
| `COMMAND_AND_BLOCK` (default) | The Bank block, the Bank Card and every player command | Everything |
| `BLOCK_ONLY` | The Bank block and the Bank Card only. The bare `/bank` tells them which block to find. `/bank account`, `/bank history`, `/bank top` and `/pay` still work. | Everything |

"Operators" here means permission level 2. The setting takes effect as soon as you save the file, with no restart. A player who opened the bank screen by command is closed out if you switch to `BLOCK_ONLY` while they have it open; a screen opened with a Bank Card stays open.

### The blocks

The Bank is 8 Bits around a chest; each add-on's block is 8 Bits around another item (its admin guide gives the recipe):

```
Bit  Bit    Bit
Bit  Chest  Bit
Bit  Bit    Bit
```

| Block | ID | Item in the middle | Recipe |
| --- | --- | --- | --- |
| Bank | `variable_economy:bank` | Chest | `variable_economy:bank_from_bits` |

| Item | ID | Recipe |
| --- | --- | --- |
| Bank Card | `variable_economy:bank_card` | `variable_economy:bank_card` (shapeless: paper, gold ingot, Chip) |

To change or remove a recipe, override it in a datapack: the recipe `variable_economy:bank_from_bits` is the file `data/variable_economy/recipe/bank_from_bits.json`. To hand the block out yourself, use `/give`, such as `/give @p variable_economy:bank`. The Bank faces whoever placed it. It breaks by hand and always drops itself, faster with a pickaxe.

### ProgressiveStages: hiding locked items

With [ProgressiveStages](https://modrinth.com/mod/progressivestages) 3.1.1 or a later 3.x installed, an item locked for a player is locked for them in every add-on: no add-on lets a player trade, claim or post an item they haven't unlocked. Nothing needs setting up: core uses the item locks you already defined in ProgressiveStages, and each add-on asks core.

- **What a locked item looks like** is up to each add-on: the market hides it from that player, the Contract Board shows its contracts greyed out with the reason, and the exchange hides it and refuses orders for it. Each add-on's admin guide has the details.
- **Prices are shared.** Everyone trades at the same price and from the same stock. A locked item still moves in price when others trade it.
- **Updates live.** When a player gains or loses a stage, what they see updates on the next tick, even with a screen open. Team stages update every member.
- **Locked means a missing stage.** Creative mode, ProgressiveStages' per-category enforcement switches and structure-only locks don't change what a player may trade.
- **Console and admins.** Admin commands and the server console see every item.

The log says `ProgressiveStages found: items a player has not unlocked are locked for them` at startup. If a future ProgressiveStages version changes the lock API, the log shows an error and items stop being locked instead of anything breaking. Without ProgressiveStages, nothing changes.

Other mods can lock items the same way through the [add-on API](api.md#item-locks). An item is locked when ProgressiveStages or any add-on locks it.

## Audit log

Every admin money command, every admin change made through an add-on's admin commands (such as the market's catalog edits, `/contracts admin refresh` and `/exchange admin cancel`; each add-on's admin guide lists its own), and all money that add-ons pay or charge, is logged to `<world>/variable_economy/audit.log`, one JSON line each. The log is core's, so every mod writes to the same file. Each line records when, who (player name and UUID, or `Server` for the console), the action, its target, the result, and the old and new values where that makes sense. Core logs:

- `money give` and `money take` ([Giving and taking money](#giving-and-taking-money)), with the balance before and after
- `stats reset` ([Where the money goes](#where-the-money-goes))
- `api pay`, `api charge` and `api transfer`: money an add-on (or a KubeJS script) paid a player, took from them or moved between accounts through the [add-on API](api.md#money). The actor is `api:<reason>`, the add-on's own name for the payment, such as `api:questmod:quest_reward`; the line holds the amount, and a payment owed because the bank couldn't be reached says `owed: bank unreachable`.

Previews and read-only commands (`info`, `stats`) aren't logged. Routine money movements (trades, payouts, coins, `/pay`) aren't either: they're in each account's [history](#account-history).

`/bank admin audit` shows the newest ten in chat; `/bank admin audit 2` shows the next ten. The file is on the server's disk, out of players' reach. When it reaches 1 MB it's renamed `audit.log.1` (older ones become `.2`, `.3`, `.4`), and at most five files are kept.

## Where the money goes

Money enters and leaves the economy only through core's bank. Every balance change is one of three kinds:

- A **pay** creates new money: a market sale, a contract payout, an admin `give`, an add-on's or a script's reward.
- A **charge** destroys money: a market purchase, a contract deposit, the exchange's escrow, a fee, an admin `take`. Nobody receives it.
- A **transfer** moves money between accounts, creating and destroying none: `/pay`, for one. Only its fee, if you set one, leaves the economy.

Core itself creates or destroys bank money only by admin command (`/bank admin money give` and `take`, see [Giving and taking money](#giving-and-taking-money)), the `/pay` fee, and coins: coins paid in at the bank become bank money, and coins taken out leave it, so the coins in players' hands are money too. Add-ons and KubeJS scripts can pay players (new money) and charge them (money removed) through the [add-on API](api.md#money); every such payment is in the [audit log](#audit-log). Each add-on's admin guide describes where its own money comes from and goes, and how to tune it.

`/bank admin stats` shows the whole money supply: what accounts hold (personal, named and party), money owed and money add-ons hold, the coins paid in and taken out (the difference is roughly the coins in players' hands), then every source of money created and destroyed since counting began, grouped by mod, with its net. Money created means a `pay` (a market sale, a contract payout, an admin `give`, an add-on reward); destroyed means a `charge` (a purchase, a deposit, the exchange's escrow, a fee, an admin `take`). Transfers between accounts count as neither. `/bank admin stats reset` starts counting again (logged).

## Giving and taking money

These commands read a player's balance and give or take money directly, outside any add-on. Giving creates money and taking destroys it, so they need operator level 2 and every change is logged.

| Command | What it does | Returns (for `execute store`) |
| --- | --- | --- |
| `/bank admin money balance <player>` | Shows the player's balance. | The balance, capped at 2,147,483,647. |
| `/bank admin money give <player> <amount>` | Pays `amount` into the player's account. | The amount given, capped at 2,147,483,647. |
| `/bank admin money take <player> <amount>` | Takes `amount` from the player's account, all or nothing: if they have less, nothing is taken. | The amount taken, capped the same way. |

- **Personal accounts, or a named one.** With `<player>` they act on the player's personal account. `account <name>` in place of the player (`/bank admin money give account Savings 500`) acts on a named account, or else a party account, by name.
- **Online or offline.** `<player>` is a name or a selector. A selector must match exactly one player; offline players work by name. Names aren't checked against players who have joined, so a misspelled name gets an unused account of its own (the audit log shows it; `take` with the same name undoes it).
- **Amounts** are whole bits, at least 1.
- **Failure returns 0** for `give` and `take`, so `execute store` can tell success from failure. `balance` also returns 0 for an empty account, so it can't.
- `give` and `take` write a line to the [audit log](#audit-log) with the balance before and after, and a player with one of the mod's screens open sees the new balance at once.
- They count in `/bank admin stats` as money created and destroyed, but not in an add-on's own stats, such as the market's, which count that add-on's trades only.

These are core's commands, so they work with or without the add-ons. For example, `execute store result score @p balance run bank admin money balance @p` puts the nearest player's balance in a scoreboard.

## KubeJS

With [KubeJS](https://www.curseforge.com/minecraft/mc-mods/kubejs) 2101.7 installed, server scripts can read balances, pay and charge players, and hear every balance change, for money-gated unlocks, quest rewards and the like. Core works the same without it.

`VariableEconomy`, in server scripts. Amounts are bits, and a player's money is their active account's:

| Call | Does |
| --- | --- |
| `VariableEconomy.balance(player)` | The active account's balance. |
| `VariableEconomy.personalBalance(player)` | The personal account's balance. |
| `VariableEconomy.pay(player, amount)`, `pay(player, amount, 'mypack:reason')` | Pays new money into the active account. Returns whether it was paid. |
| `VariableEconomy.charge(player, amount)`, `charge(player, amount, 'mypack:reason')` | Takes money from the active account, all or nothing. Returns false, changing nothing, if it can't. |
| `VariableEconomy.format(amount)` | The amount as text players read, such as *1,024 bits*. |

The reason, an ID in your pack's namespace, names the change in the account's [history](#account-history) and the [audit log](#audit-log), and counts as its source in `/bank admin stats`; without one it's `kubejs:script`. Scripts' payments are logged like any add-on's.

`VariableEconomyEvents.balanceChanged`, fired at the end of each tick for every account whose balance changed that tick: `event.account`, `event.owner` (a UUID), `event.player` (the owner, if online), `event.before`, `event.after` and `event.changes` (each with `amount`, signed, `reason`, its ID as text, and `actor`, the UUID of the player who caused it or null).

```js
// server_scripts/economy.js
// A money-gated unlock: /buywings costs 50,000 bits and gives the player the stage 'wings'.
ServerEvents.commandRegistry(event => {
  const { commands: Commands } = event
  event.register(Commands.literal('buywings').executes(ctx => {
    const player = ctx.source.player
    if (!VariableEconomy.charge(player, 50000, 'mypack:wings')) {
      player.tell(Text.red('Wings cost ').append(VariableEconomy.format(50000)))
      return 0
    }
    player.stages.add('wings')
    player.tell(Text.green('Wings unlocked'))
    return 1
  }))
})

// Congratulate a player whose balance passes a million.
VariableEconomyEvents.balanceChanged(event => {
  if (event.player && event.before < 1000000 && event.after >= 1000000) {
    event.player.tell(Text.gold('You have a million bits!'))
  }
})
```

Paying creates money. A script that pays players for items should pay less than the market charges for them, as the [money rule](api.md#the-money-rule) asks, or players can buy from the market and hand the items in for a profit.

## Server config

Each mod has its own server config file in `config/`, created on first start. Changes apply when you save the file. A key such as `pay.fee_percent` is `fee_percent` in the file's `[pay]` section. Core's is `variable_economy-server.toml`; each add-on's admin guide describes its own.

### Core: `variable_economy-server.toml`

| Option | Default | Range | What it does |
| --- | --- | --- | --- |
| `access_mode` | `COMMAND_AND_BLOCK` | `COMMAND_AND_BLOCK`, `BLOCK_ONLY` | How players reach the bank: by command and block, or by the Bank block and Bank Card only; see [Letting players in](#letting-players-in). |
| `ignored_components` | `["relics:data"]` | data component IDs | Item data that doesn't stop a copy counting as plain, for market sales, the sell-price tooltips and contract deliveries. Some mods add data to every copy a player holds: Relics (and Reliquified Artifacts) adds `relics:data`, a relic's level and rolled stats, so without this no relic could be sold. The data is lost with the sold item; bought items always come fresh and never join a stack carrying such data. It doesn't apply to the exchange. |
| `accounts.extra_enabled` | false | true, false | Players can open extra, named [accounts](#accounts). Off stops new ones; existing ones keep working. |
| `accounts.max_per_player` | 3 | 1 – 100 | How many accounts one player may own, their personal one included. |
| `accounts.shared_enabled` | false | true, false | Named accounts can have members and deposit-only members. Off stops new members; existing ones stay. |
| `accounts.max_members` | 8 | 2 – 100 | Members one account may have, its owner included. |
| `accounts.ftb_party_account` | true | true, false | With FTB Teams, each party gets an account. Off ends every party account at the next start, its balance going to the party's owner. |
| `pay.enabled` | true | true, false | Allow [`/pay`](#paying-other-players). |
| `pay.fee_percent` | 0 | 0 – 100 | Fee on each payment, a percentage of the amount, rounded up; paid on top by the sender and destroyed. |
| `pay.min_amount` | 1 | 1 or more | The smallest amount one payment may send. |
| `history.size` | 50 | 0 – 1000 | How many of its newest balance changes each account keeps for its [history](#account-history). 0 keeps none. |
| `leaderboard.enabled` | true | true, false | Show the leaderboard in the bank screen and `/bank top`. When off, the screen says it's turned off and no balances are sent to anyone. |
| `leaderboard.size` | 10 | 1 – 100 | How many lines the leaderboard lists. |
| `leaderboard.rank_by` | `OWNER` | `OWNER`, `ACCOUNT` | `OWNER`: one line per player, adding up every account they own. `ACCOUNT`: one line per account. |
| `leaderboard.exclude_operators` | false | true, false | Leave operators, and accounts they own, off the leaderboard, for servers where admins hold test money. |

### The leaderboard

The leaderboard sits in the bank screen, and `/bank top` prints it. An account's worth is its balance plus the money held for it elsewhere: what its open exchange buy orders hold (tax included), the deposits of contracts it paid for, and contract payouts and [add-on](api.md#owed-payments) payments still owed to it (paid when the bank was unreachable). With `rank_by = OWNER` (the default) each player has one line adding up every account they own, personal, named and party; with `ACCOUNT` each account has its own line, a personal one under its player's name. Players the server has never seen, and empty lines, are left out. Offline players are listed under the name they last joined with. It's worked out when a player opens the bank screen, not continuously.

## Commands: `/bank`

Each mod's commands sit under its own root (`/bank`, `/market`, `/contracts`, `/exchange`), and exist only when that mod is installed. Core's:

| Command | Permission | What it does |
| --- | --- | --- |
| `/bank` | everyone* | Opens the bank screen, with the leaderboard. |
| `/bank account [list]`, `info`, `create`, `delete`, `use`, `invite`, `join`, `decline`, `leave`, `remove`, `role` | everyone | A player's [accounts](#accounts); see the [player guide](player-guide.md#accounts). Not limited by `access_mode`. |
| `/bank history [account] [page]` | everyone | An account's [history](#account-history), newest first. Not limited by `access_mode`. |
| `/bank top [page]` | everyone | The leaderboard. Not limited by `access_mode`. |
| `/pay <player> <amount>` | everyone | Pays another player; see [Paying other players](#paying-other-players). Not limited by `access_mode`. |
| `/bank admin info` | 2 | How many personal, named and party accounts hold money, and what they hold. |
| `/bank admin accounts <player>` | 2 | Every account a player may use, with their role, its owner, members and balance. |
| `/bank admin money balance <player>` or `balance account <name>` | 2 | A balance, online or offline; see [Giving and taking money](#giving-and-taking-money). |
| `/bank admin money give`, `take` (`<player> <amount>` or `account <name> <amount>`) | 2 | Gives or takes money (taking is all or nothing). Logged in the audit log. |
| `/bank admin history <player> [page]` or `history account <name> [page]` | 2 | Any account's history. |
| `/bank admin stats`, `stats reset` | 2 | The [money supply](#where-the-money-goes); reset starts counting again. |
| `/bank admin audit [page]` | 2 | The latest admin actions from the [audit log](#audit-log), from every mod. |

\* Operators only when core's `access_mode` is `BLOCK_ONLY`: players are told to use the Bank block or a Bank Card instead (see [Letting players in](#letting-players-in)).

## Troubleshooting

| You see | Meaning |
| --- | --- |
| `FTB Teams is installed but its team API does not match …` | A different FTB Teams version; parties get no accounts (existing party accounts end at the next start, their balances going to the party owners). Built against 2101.1.11. |
| `FTB Teams party … no longer has an account …` | `accounts.ftb_party_account` is off, or FTB Teams was removed: the party's account ended and its balance went to its owner. |
| `ProgressiveStages is installed but its item lock API does not match …` | A different ProgressiveStages version; items aren't locked for anyone. Built against 3.1.1. |
| A player sees "The bank is only available at a Bank or with a Bank Card on this server" (or the same for the market, contracts or exchange) | That mod's `access_mode` is `BLOCK_ONLY`. |
| A listed item shows 0 under Have in the market and can't be sold, though the player holds one | Another mod adds data to every copy (as Relics does). Find the data's ID (F3+H shows component counts; `/data get entity @s Inventory` shows the IDs) and add it to `ignored_components`. |
