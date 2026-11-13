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
- **Guided → spontaneous, per challenge.** Every challenge climbs its own five-step ladder: type it with a shuffled
  word bank (*Guidé*), type the key part (*Compléter*), write it all from an English instruction (*Écrire*), from a
  French instruction (*En français*), and finally just answer a French question about what happened, with no hint of
  the answer (*Spontané*: "Qu'est-ce que tu viens de faire ?"). A clean answer moves that challenge up a step, a miss
  moves it down. "Plus facile" steps down for this one try (still typed).
- **Hints in small steps.** *Indice* gives a little more each press: the grammar rule, the English instruction (on
  the French steps), the first words, then the word bank. Each step costs a quarter of the XP, so asking for a little
  help no longer costs as much as asking for a lot.
- **Rework your own sentence.** After a correct answer the game often pushes further, straight away: negate what you
  just said, turn it into a question, swap the noun for a pronoun, or move it to another tense. Your sentence stays
  on screen while you rework it.
- **Adapts to you.** Every challenge is Débutant, Intermédiaire or Avancé. Harder ones unlock per topic as its
  mastery grows (and across the board once your overall level rises), and the picker favours challenges right at
  the edge of what you can do. Your level shows in the notebook.
- **Villagers talk back.** Right-clicking a villager with a job starts a short typed exchange before you can
  trade: they greet you (vous for librarians, clerics, smiths and cartographers; tu for the rest), then you make a
  request suited to their job. Each villager has their own name and voice pitch. Get both lines right and the
  trade window opens (and stays open to you for 5 minutes). Get it wrong and they don't understand you, so try
  again.
  Say it your way: answers are built from a phrasebook of greetings, request forms (je voudrais, j'aimerais,
  pourriez-vous me donner, est-ce que je pourrais avoir…), questions and politeness, so each line accepts thousands
  of correct phrasings. They must be grammatical and in the right register: tutoyer a librarian and they tell you
  to use vous (being formal with a farmer is fine).
- **Rarely the same question twice.** Challenges are templates filled in with what actually happened: the mob you
  killed (with its real gender: *une araignée*, *je l'ai tuée*), the ore you mined (*du fer*, *de l'or*, *des
  diamants*), the biome you walked into, the item a villager sells, and how many you've had today ("J'ai tué cinq
  zombies aujourd'hui", digits accepted too). A challenge you've just done rests for 20 minutes or more (longer the
  better you know it, 4 minutes after a miss), the last 8 never come straight back, and grammar topics are mixed up.
  When everything for a moment is resting, that moment simply doesn't ask you anything.
- **Revisits weak spots.** Spaced repetition per challenge (Leitner boxes); missed challenges and weak topics
  come back more often. Mistakes are listed in the notebook.
- **Helpful marking.** Word-by-word feedback: wrong words underlined in red, missing words shown in green,
  accent-only slips and one-letter typos in longer words accepted with a warning (never in a word's ending, where the
  grammar lives). Accent buttons (é è ê à ç …) under the text box.
- **Knows the classic mistakes.** "J'ai allé" gets "aller takes être: je suis allé", not just red words; so do
  "je m'ai", "si j'aurais", "je vais fabrique", "je ne vois pas rien", "j'ai trois" (needs en), adjective
  agreement, subjunctive triggers and more. Each mistake is tagged with a habit (auxiliary, agreement, pronoun
  placement…), and challenges that practise your shakiest habits come back more often. The notebook shows why each
  recent mistake was wrong.
- **Says it your way.** Event challenges accept synonyms (tué, vaincu, battu…), different word orders and an
  interjection first ("Ouf, je suis revenu vivant !"); when you use a different phrasing the game shows you its own
  as another way to say it.
- **Curated content.** 117 challenges plus 42 templates over 91 mobs, ores, biomes and trade items (well over 700
  filled-in challenges), across 16 topics (passé composé avec avoir/être, futur proche, négation,
  pronoms, imparfait, conditionnel, il faut/devoir, questions, prépositions de lieu, accords, verbes
  pronominaux, connecteurs, articles partitifs, pronoms relatifs, subjonctif). No AI generation during play.
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
  "say": "Say that you killed a zombie.", "say_fr": "Dis que tu as tué un zombie.",
  "cue": "Qu'est-ce que tu viens de faire ?", "hint": "avoir + participe passé",
  "answers": ["J'[ai tué] {un|le} zombie.", "J'ai vaincu {un|le} zombie."],
  "extra": ["suis", "tuer"], "next": ["kill_zombie_pron"],
  "traps": [ { "match": "Je suis tué {un|le} zombie", "why": "tuer takes avoir: j'ai tué.", "skill": "aux_avoir" } ] } ] }
```
Templates use `{$…}` slots filled from the pack's `"lexicon"`: each entry has `on` (the triggers it's for), the
French noun `n` with its gender `g` (and `pl` if irregular), the English `en`, and `tags` a template can require with
`"needs"`. From that the mod works out `{$un}`, `{$le}`, `{$ce}`, `{$du}`, `{$de}`, `{$les}`, `{$il}`, `{$e}` (agreement
ending), wrong-gender twins for traps (`{$x_un}`, `{$x_du}`…) and `{$count}` / `{$count_n}` (today's tally; set
`"min_count"`). Any field in the entry (e.g. `"du": "des diamants"`) overrides the worked-out form.

`say_fr` (a French instruction) and `cue` (a French question that doesn't give the answer away) unlock the top two
steps of the ladder. `next` lists follow-up challenges that rework the sentence. `traps` are known wrong answers
(same markup, compared without accents) with an explanation and a habit tag; a pack's top-level `"traps"` list is
shared and may match anywhere in a sentence, and `{*}` there stands for any one word.
`{a|b}` lists accepted alternatives (the first one is shown), `[...]` marks the part blanked in *Compléter* mode,
`{@name}` inserts a phrasebook entry (see `"phrases"` in content.json; `name.tu` / `name.vous` lists only apply in
that register),
`extra` are distractor tiles. Triggers: `kill:<mob>`, `mine:<block>` (deepslate variants merged, all logs are
`mine:log`), `biome:<biome>`, `dim:the_nether|the_end|overworld`, `sleep`, `death`, `lowhp`, `dawn`; `*` is a
wildcard. Villager dialogue uses `greet:vous`, `greet:tu`, `trade:<job>` (or `trade:any`) plus an `"npc"` line.
Add `"level": 1|2|3` for difficulty. Challenges with `"on": []` only appear in review.

## Code layout
- `core/` — plain Java, no Minecraft code: `Content` (library and picker), `Grader` (word diff), `Profile` (progress)
- `Parle` — server: gameplay triggers and XP payout
- `client/` — `ParleClient` (queue, keys, auto-open), `ChallengeScreen`, `NotebookScreen`, `UI`

Progress is saved in `config/parle/profile.json`.
