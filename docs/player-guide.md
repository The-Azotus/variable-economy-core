# Player guide

Variable Economy adds a bank to the game. Everything is paid through your bank account, so you don't need coins in your inventory: you pay coins in at a Bank, take them out again when you want them, and pay other players straight from your account. Your server may also have the add-ons: a market run by the server, a contract board and a player exchange, which all pay and charge your bank account too.

The mod has its own [coins](#coins), and money is counted in **bits**.

## The four blocks

Each part of Variable Economy has its own block. Right-click one to open its screen. Servers choose which parts to install, so yours may not have all four: the bank is always there, and the market, contract board and exchange are add-ons.

| Block | What it opens | Command | Guide |
| --- | --- | --- | --- |
| **Bank** | Your accounts, coins in and out, your history and the leaderboard. See [The Bank](#the-bank). | `/bank` | This one |
| **Market Terminal** | The server's market: buy and sell items. | `/market` | [Market player guide](https://github.com/The-Azotus/variable-economy-market/blob/main/docs/player-guide.md) |
| **Contract Board** | Delivery contracts. | `/contracts` | [Contracts player guide](https://github.com/The-Azotus/variable-economy-contracts/blob/main/docs/player-guide.md) |
| **Exchange** | Trading with other players. | `/exchange` | [Exchange player guide](https://github.com/The-Azotus/variable-economy-exchange/blob/main/docs/player-guide.md) |

Anyone can craft them (see [Crafting the Bank](#crafting-the-bank) for the Bank). If you walk more than about 8 blocks away from the block you opened, its screen closes.

The commands open the same screens from anywhere, if your server allows it. If you see a message such as *"The bank is only available at a Bank or with a Bank Card on this server"*, find the block instead. A [Bank Card](#the-bank-card) always opens the bank.

## The Bank

The Bank moves money between coins and your bank account. The market, contracts and exchange never pay or take coins: they only use the account.

The bank screen has two parts: the leaderboard (or your history) on one side and your bank on the other. On a narrow window the bank goes below the leaderboard.

**Your bank** shows one of your accounts: its name, your role in it, its balance, what the coins you carry are worth, and every coin with how many you carry. If you have more than one account, the **<** and **>** arrows show the others; coins go in and out of the account shown. **Use for trades** makes the shown account your active one (see [Accounts](#accounts)); it says **In use for trades** when it already is. **Accounts…** opens the Accounts screen.

- **Click a coin** to withdraw one of exactly that coin; **Shift-click** for a stack of it, or as many as your balance covers. No amount to type.
- **Withdraw** takes an amount out as the fewest coins. Type the amount, and the line below shows the coins you'll get, such as *"As 1 × Crown, 3 × Mark, 7 × Bit"*, or why it can't happen. If the coins wouldn't all fit in your inventory, nothing is withdrawn; nothing is ever dropped on the ground. The same room rule applies to clicking a coin.
- **Deposit coins** pays every coin in your inventory into the bank. Only your main inventory counts (not armour or the offhand), and renamed coins are left alone. The button shows what your coins are worth.

**The leaderboard** lists the server's richest players (plus money held in their open exchange buy orders and contract deposits, and exchange, contract or add-on money still owed to them), with your own line highlighted and your rank above the list (*You: #4 of 12*). Usually each player's line adds up every account they own; some servers list each account on its own line instead. It updates each time you open the bank, and `/bank top` prints it in chat. If the server has turned it off, it says so.

**History** (the button above the list) shows the shown account's money movements, newest first: how long ago, why (a market sale, a contract payout, coins deposited, a payment…), who, and how much. `/bank history` prints your active account's in chat, and `/bank history <account>` another's. Deposit-only members can't read an account's history.

### The Bank Card

Right-click a Bank Card to open the bank screen anywhere, leaderboard included, without a Bank block. It stays open as long as you carry a card. You can't trade with it: the market, contracts and exchange have their own blocks. Recipe: a paper, a gold ingot and a Chip, in any arrangement.

### Accounts

You always have a **personal account**. Depending on the server you may have others:

- **Named accounts** you open yourself, for savings or a group, if the server allows extra accounts.
- **Shared accounts**: named accounts with other members, if the server allows sharing. The owner invites players; you join by accepting. A **member** deposits, spends and withdraws; a **deposit-only** member can only pay in. When the owner leaves, the member who joined first becomes owner.
- **Your FTB Teams party's account**, on servers with FTB Teams: every party member uses it, and its owner is the party's owner. If the party is deleted, its money goes to the party's owner.

**Your active account** pays for what you buy, receives what you sell and what you're paid (contracts, the exchange, `/pay`), and holds your contract deposits and exchange orders. Pick it with **Use for trades** in the bank screen or `/bank account use <name>`. If you can no longer spend from it (you left, were made deposit-only, or it was deleted), your personal account is active again.

**The Accounts screen** (**Accounts…** in the bank screen) lists your accounts; click one to show it. You can open a new one, accept or decline invitations, and leave an account. An account's owner also invites players (by name, as a member or deposit-only), changes a member's role, makes someone else owner (click twice), removes members, and deletes the account (click twice; only an empty one, with no open exchange buy orders and no contract deposits held for it). The same, by command:

| Command | What it does |
| --- | --- |
| `/bank account` | Lists your accounts, which one is active, and your invitations. |
| `/bank account info <name>` | An account's owner, members, roles and balance. `personal` names your own account and `party` your FTB Teams party's. |
| `/bank account use <name>` | Makes it your active account. |
| `/bank account create <name>` | Opens a named account. Names are one word: letters, digits, `_` and `-`. |
| `/bank account invite <name> <player> [member\|deposit_only]` | Owner only: invites an online player. |
| `/bank account join <name>`, `decline <name>` | Accepts or turns down an invitation. |
| `/bank account leave <name>` | Leaves an account. The last member can't leave; they delete it instead, once it's empty. |
| `/bank account remove <name> <player>` | Owner only: removes a member. |
| `/bank account role <name> <player> <owner\|member\|deposit_only>` | Owner only: changes a member's role; `owner` hands the account over. |
| `/bank account delete <name>` | Owner only: deletes an empty account. |

These commands, `/bank history`, `/bank top` and `/pay` work everywhere, even on servers that keep the screens to their blocks.

### Paying other players

`/pay <player> <amount>` pays another player from your active account into theirs, online or not. Some servers charge a fee on top (destroyed, not given to anyone) or set a smallest payment; the message says what was taken. If you both use the same shared account, nothing moves.

## Coins

The mod's coins:

| Coin | Worth | Coin | Worth |
| --- | --- | --- | --- |
| Bit | 1 bit | Crown | 4,096 bits |
| Chip | 8 bits | Sun | 32,768 bits |
| Stack | 64 bits | Divine | 262,144 bits |
| Mark | 512 bits | Omega | 2,097,152 bits |

Each is worth 8 of the one before. Put 8 of a coin in a crafting grid to make one of the next, or one coin to break it back into 8.

On servers that also have Create: Numismatics, its coins aren't this mod's money: the bank doesn't take them, and the market doesn't buy them. Its Crown and Sun are worth less than this mod's coins of the same name.

## Messages

| Message | Meaning |
| --- | --- |
| *Deposited X* / *Withdrew X* | Done. |
| *You carry no coins to deposit* | No plain coins of this mod in your main inventory. |
| *Not enough money in the bank* | The account you're using can't cover the withdrawal. |
| *Not enough room in your inventory for the coins* | Make space, or withdraw less. |
| *Your role in this account doesn't allow that* | Deposit-only members can pay in, but not withdraw. |
| *The bank is unavailable right now* | The server's bank can't be reached. Tell an admin. |
| *The bank is only available at a Bank or with a Bank Card on this server* | The server keeps the bank to its block. Find a Bank, or use a Bank Card. The add-ons have messages like it, naming their own block. |
| *The leaderboard is turned off on this server* | The server doesn't show it. |
| *Payments between players are turned off on this server* | `/pay` is off here. |
| *The smallest payment is X* | The server sets a smallest amount for `/pay`. |
| *They use the same account as you: nothing would move* | You both use the same shared account. |

## Crafting the Bank

The Bank is 8 Bits around a chest:

```
Bit  Bit    Bit
Bit  Chest  Bit
Bit  Bit    Bit
```

The add-ons' blocks are made the same way, 8 Bits around another item; their guides say which. The Bank Card is a paper, a gold ingot and a Chip, in any arrangement.

Your server may use different recipes; check a recipe viewer if these don't work. The Bank faces you when you place it. Break it by hand to pick it up again; it breaks faster with a pickaxe.
