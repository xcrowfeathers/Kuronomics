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

## API

5.0 introduces a public API, datapack and script support via kube-js.  
You can look at my [GitHub](https://github.com/xcrowfeathers/Kuronomics) for examples and how to use the API.

## Known incompatibilities

Currently no known incompatibilities.

## Q/A

### Q: How does this mod perform on larger scales? (5.0 and later)
**A:** This mod is written with large-scale performance as a priority. According to tests and estimates, this mod should perform very well even on large scale servers. However, 5.0 is a beta after all, so please report poor performance or lag spikes to me in the comments or GitHub!

### Q: Backport to version XY?  
**A:** No. I will however do releases for newer versions later on.

### Q: Modloader XY?  
**A:** NeoForge will stay the only supported modloader.

### Q: Do you accept requests for items and features?  
**A:** Sure! You can always submit feature requests and ideas.