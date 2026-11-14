# Howdy!

This mod adds a broad economy system to Minecraft. It includes items like bank notes, wallets, ID cards and blocks like shops, vending machines, ATMs and even whole markets!

I moved the documentation to a separate wiki site. If you want to look up how to use this mod or see general information about the content, please visit the [docs](https://kurodocs.gitbook.io/kuros-mod-docs).

## Future Updates!

My plan is to keep this mod updated if there are enough people that request features or maintenance. Please note that I will not provide updates for old versions (1.20.1 for now).

Enjoy!

## 5.0 Beta Rework

For about one year I've worked on a complete rewrite/rework of this mod. The reason was primarily that I have used MCreator + custom code snippets before as a development environment when I first created this mod, since I was quite inexperienced in modding.  
However, I moved out of the MCreator environment and thus reworked the entire codebase. Furthermore, with the fresh restart, I started implementing entirely new features that I kept delaying before.  

Since this is a release with **tons of new features** which I couldn't test entirely, I declare this as a beta version. While all features should work as intended, there might be bugs or mistakes I did not notice.

**Please report any bugs you encounter!**

## WorldGuard Integration for Real Estates and Workzones

### Important change for users of the Real Estate feature BEFORE 5.0  
I have **previously** implemented the mod YAWP (Yet Another World Protector) as an optional dependency for my mod to make the whole real estate feature possible. However, my mod now uses my [WorldGuard port for NeoForge](https://www.curseforge.com/minecraft/mc-mods/worldguard-neoforge), so you will have to redefine
your areas **IF** you used versions prior 5.0.  

[My NeoForge port](https://www.curseforge.com/minecraft/mc-mods/worldguard-neoforge) of EngineHub's WorldGuard should work entirely like the original and thus provides a safe, established and clean solution for region management.  

With that said, I recommend installing WorldEdit and this WorldGuard port along my economy mod!

## API, Datapacks & KubeJS

5.0 introduces a public API, datapack and script support via kube-js. Examples and documentation down below.

For now, Kuronomy supports Java mod addons, datapack job tasks, and KubeJS scripting.

### Java API

For developers connecting another mod to Kuronomy.

**Possibilities are:** query balances, transfer or reserve money, manage authorized company ranks/salaries and contracts, read job/property information, listen for economy events, and register custom job task types.

Compile against the Kuronomy JAR and add `kuronomics` as a mod dependency. Access the API through `de.kuro.kuronomics.api.KuronomyApi`.

```java
var economy = KuronomyApi.economy(server);
boolean affordable = economy.canAfford(
    EconomyApi.player(player.getUUID()), 500L
);
```

This checks whether the player can afford **$5**. API calls run on the server thread, money amounts use **integer cents**, NOT whole values!  
[Example addon](examples/framework/FrameworkAddon.java)

### Datapacks

Add tasks to existing **task-based jobs** without writing Java. Supported actions include mining, farming, crafting, smelting, fishing, deliveries, and animal care.

Place task files inside a datapack at:

```text
data/<namespace>/kuronomics/job_tasks/<name>.json
```

Example mining bonus:

```json
{
  "type": "kuronomics:break_block",
  "job": "miner",
  "config": { "target": "minecraft:coal_ore" },
  "reward": 1.50,
  "max_gain_per_payday": 15,
  "natural_only": true
}
```

Awards **$1.50 per natural coal ore**, capped at **$15 per payday period**. Datapack money values use whole**dollars**, unlike java addons.

Jobs are still configured in `economy_jobs.toml`. Datapack rewards add to matching tasks. Entirely new action types need a Java addon.

[Example pack](examples/job-task-datapack)

### KubeJS — Optional

Use **`Kuronomy`** to query balances, jobs and company permissions, and perform payments/ money reservations. Use **`KuronomyEvents`** to react to paydays, transactions, job changes, task completion, contracts, and property changes.

```javascript
KuronomyEvents.playerPayday(event => {
  console.info('Payday finished for ' + event.data.player())
})
```

KubeJS payment amounts use **cent strings**: `"500"` means **$5**, like java addons.

Payments need existing funds and normal permissions. Queue followUp money actions from Kuronomy event handlers with `Kuronomy.defer(...)`.

[Example script](examples/kubejs/server_scripts/kuronomy_example.js)