# Parle! learn French whilst you play Minecraft

A Fabric mod for **Minecraft 1.21.1** that turns moments in your survival world into short, hand-written
French sentence challenges. You kill a zombie, find diamonds, enter the Nether or wake up at dawn, and a
challenge opens: you type the sentence in French, and you can't carry on until you have.

## How it teaches
- **Production, not vocab drills.** Every challenge asks you to *say* something about what just happened
  ("Say that you killed the creeper before it exploded"), and you always **type** the French yourself.
- **Not optional.** When a gameplay moment triggers a challenge it opens straight away (as soon as no other
  menu is open), Esc does nothing, and there is no skip button. Get it right, or press *Solution* and type the
  correct sentence out yourself before you can play on. In singleplayer the game pauses while you answer.
- **Guided → spontaneous.** Each grammar topic has a mastery score. Low mastery: type the sentence with a
  shuffled word bank in view (*Guidé*). Medium: type the key part of the sentence (*Compléter*). High: type the
  whole sentence from scratch (*Écrire*). "Plus facile" steps down a level (still typed).
- **Revisits weak spots.** Spaced repetition per challenge (Leitner boxes); missed challenges and weak topics
  come back more often. Mistakes are listed in the notebook.
- **Helpful marking.** Word-by-word feedback: wrong words underlined in red, missing words shown in green,
  accent-only slips accepted with a warning. Accent buttons (é è ê à ç …) under the text box.
- **Curated content.** 74 challenges across 13 topics (passé composé avec avoir/être, futur proche, négation,
  pronoms, imparfait, conditionnel, il faut/devoir, questions, prépositions de lieu, accords, verbes
  pronominaux, connecteurs). No AI generation during play.
- Solving a challenge gives vanilla XP (3 / 6 / 10 by level, halved after a hint or a wrong try, none if you
  needed the solution).

## Controls
| Key | Action |
| --- | --- |
| **J** | Extra practice: a review challenge (this one you may close) |
| **N** | Notebook: topic mastery, mistakes to revisit, how often challenges come (every 30 s to 5 min) |
| Enter | Check / continue |

Both can be rebound in Options → Controls → *Parle! (French)*.

## Build and install
Requires Java 21.
```
./gradlew build          # Windows: gradlew.bat build
```
The mod is `build/libs/parle-1.0.0.jar`. Put it in `.minecraft/mods/` together with
[Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.1 and the Fabric Loader. `./gradlew runClient` starts
a dev game. Pushing to GitHub also builds the jar automatically (Actions → latest run → *parle-mod* artifact).

The mod goes on the client; for multiplayer the server needs it too (it detects the gameplay moments and pays
the XP). Singleplayer just works.

## Adding your own challenges
Drop `.json` files into `config/parle/packs/` using the same format as
`src/main/resources/assets/parle/content.json`:
```json
{ "challenges": [ {
  "id": "my_first", "on": ["kill:zombie"], "topic": "pc_avoir",
  "say": "Say that you killed a zombie.", "hint": "avoir + participe passé",
  "answers": ["J'[ai tué] {un|le} zombie.", "J'ai vaincu {un|le} zombie."],
  "extra": ["suis", "tuer"] } ] }
```
`{a|b}` lists accepted alternatives (the first one is shown), `[...]` marks the part blanked in *Compléter* mode,
`extra` are distractor tiles. Triggers: `kill:<mob>`, `mine:<block>` (deepslate variants merged, all logs are
`mine:log`), `biome:<biome>`, `dim:the_nether|the_end|overworld`, `sleep`, `death`, `lowhp`, `dawn`; `*` is a
wildcard. Challenges with `"on": []` only appear in review.

## Code layout
- `core/` — plain Java, no Minecraft code: `Content` (library and picker), `Grader` (word diff), `Profile` (progress)
- `Parle` — server: gameplay triggers and XP payout
- `client/` — `ParleClient` (queue, keys, auto-open), `ChallengeScreen`, `NotebookScreen`, `UI`

Progress is saved in `config/parle/profile.json`.
