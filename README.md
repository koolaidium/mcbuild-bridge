# AI Builder Bridge (Fabric, Minecraft 26.3)

Tiny mod that lets `mcbuild.py` send builds into your running game instantly.
Needs: Fabric Loader 0.19.5+, Fabric API 0.160.5+26.3, Java 25 (what MC 26.x uses).

## Get the .jar (easiest: GitHub builds it for you, no Java setup)
1. Create an empty repo on github.com (e.g. `mcbuild-bridge`).
2. In this folder:
   git init && git add . && git commit -m "init"
   git branch -M main
   git remote add origin https://github.com/YOUR_NAME/mcbuild-bridge.git
   git push -u origin main
3. On GitHub: Actions tab -> latest "build" run -> download the `mcbuild-bridge-jar` artifact -> unzip.

## Or build on Pop!_OS
   curl -s "https://get.sdkman.io" | bash        # then reopen the terminal
   sdk list java                                  # pick a Java 25 build, e.g. sdk install java 25-tem
   sdk install gradle 9.6.0
   gradle build
   -> build/libs/mcbuild-bridge-1.0.0.jar  (NOT the -sources jar)

## Install
Put `mcbuild-bridge-1.0.0.jar` in your CurseForge instance's `mods` folder
(CurseForge app -> your profile -> three dots -> Open Folder -> mods).
Make sure Fabric API is installed too. Launch the game and open a world.

## Use
Run `python3 mcbuild.py`. It shows "Game: connected" when the mod is running.
Describe a build, and it appears just ahead of you. Type `undo` to remove it.

## How it works / safety
- On world start the mod opens a server on 127.0.0.1 (port 25585+) and writes
  `config/mcbuild_bridge.json` (port + random secret token) in your instance.
- Requests need that token, so websites in your browser can't use it.
- Only fill / setblock / marker summon+kill commands of the exact shape mcbuild.py
  generates are accepted. Anything else (op, stop, ...) is rejected.
- If a block name is wrong the game rejects that one command; check logs/latest.log.

## If the build fails to compile
I wrote this against the 26.x Mojang-named API without being able to compile it.
Paste the Gradle error and it's usually a one-line rename.
