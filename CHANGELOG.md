# Changelog

What changed in each release of core, for players and server admins. Mod and add-on authors: the API's changes are in the [add-on guide's changelog](docs/api.md#changelog). The market, contracts and exchange keep their own changelogs in their repositories. Releases before 0.6.0 were Dynamic-Economy, a single mod, and aren't listed here.

## 0.6.1 (2026-11-17)

Variable Economy now lives in four repositories, one per mod; nothing changes in game. The mod's jar no longer contains its test code.

## 0.6.0 (2026-11-14)

The mod was called Dynamic-Economy until this release. It is now **Variable Economy**, and it is four mods instead of one.

**Before you update:** worlds from Dynamic-Economy can't be carried over. The mod's id changed, so its market, bank balances, coins and terminals don't load in a 0.6.0 world. Start a new world. Create: Numismatics is no longer supported: every world uses the mod's own coins and bank, counted in bits.

What changed in the market, contracts and exchange in this release is in each add-on's changelog: [market](https://github.com/The-Azotus/variable-economy-market/blob/main/CHANGELOG.md), [contracts](https://github.com/The-Azotus/variable-economy-contracts/blob/main/CHANGELOG.md), [exchange](https://github.com/The-Azotus/variable-economy-exchange/blob/main/CHANGELOG.md).

### Four mods

- **Core** (`variable_economy`) is required: the coins, the bank, accounts, `/pay` and the leaderboard. Installed alone, it is a complete bank mod.
- **Market**, **Contracts** and **Exchange** are optional add-ons. Install any of them, in any combination, on top of core.
- Install the same mods on the server and on every client, all of the same version. A client missing an add-on the server has (or the other way round) can't join.
- Each mod has its own block and screen in place of the one tabbed Market Terminal: the **Bank** (coins in and out, and the leaderboard), the **Market Terminal**, the **Contract Board** and the **Exchange**.
- Each mod has its own config file (`config/variable_economy-server.toml`, `config/variable_economy_market-server.toml` and so on), its own `access_mode` and its own commands: `/bank`, `/market`, `/contracts` and `/exchange`. Admin commands moved with them: the bank's to `/bank admin`, contracts' to `/contracts admin`, the exchange's to `/exchange admin`.

### Bank and accounts

- **Accounts.** Every player has a personal account. If the server turns it on, players open named accounts and share them with members or deposit-only members. With FTB Teams, each party gets a shared account. Each player picks an **active account**, which their trades use. Manage accounts in the bank screen's new **Accounts** screen, or with `/bank account`.
- **`/pay <player> <amount>`** moves money to another player, with an optional fee the server sets.
- **Account history**: every change to an account's balance, in the bank screen or with `/bank history`.
- **The leaderboard** ranks by owner (all their accounts together) or by account, and counts money held in contracts and the exchange. `/bank top` prints it.
- **Money-supply stats** for admins: `/bank admin stats`.
- **KubeJS** server scripts can read balances, pay, charge and hear balance changes.
- Amounts go far beyond 2 billion bits.
