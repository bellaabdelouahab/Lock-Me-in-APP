# Screen filter: how the v3.1 F2 rule was measured

Spec: Self-Discovery vault, `_system/Specs/Lock Me In v3.1 Screen Check Precision and Feedback.md`, part F2 (precision without
training). The owner's complaint: a man talking, a bald head in close-up, a woman speaking, or a Dragon Ball fight showed the
warning and sometimes closed the app. The owner's rule for the change: catching real content must not get worse at all.

## Method

- **Images** (public, never trained on), about 980 in all:
  - normal: COCO photos of men talking, women talking, bald or close-up faces, people indoors, everyday scenes and women in
    normal clothes; Reddit "neutral"; Safebooru all-ages drawings: Dragon Ball and DBZ fights, bald anime characters, anime
    characters talking, clean anime (fights, magic, swords, manga pages), ordinary anime outfits; NSFW-dataset "drawings".
  - risky: NSFW-dataset porn, hentai and sexy; revealing photos; anime fanservice (60 images each).
- **Screens**: each image placed as a phone would show it, a feed picture on a 1080 x 2400 screen and a full-screen video
  (2400 x 1080), four frames each (slightly different crop and zoom, like consecutive video frames).
- **Models**: the three models shipped in the app, on the same tiles the app reads (squares along the long side, 40% overlap).
- **Today's rule** replayed: one frame over any threshold shows the warning; a second hit closes.
- **New rule**: a frame is a hit by the per-tile rule in `FilterRule` (core); nothing shows until 3 of the last 4 frames are hits
  (or one frame with porn + hentai at 0.95 or more); then the warning, the 5-second check and the 30-second close as before.
- **Tuning**: random and local search over the thresholds, keeping only rules that catch at least as many risky screens as today
  in every risky set, then the fewest false alarms. Tuned on half the images, checked on the other half, then set on all.
- The new rule in Kotlin is checked against the measured rule on 400 real tiles (`FilterRuleParityTest`).

## Results (1,964 screens: 1,364 normal, 600 risky)

| | warning shown today | new | closed today | new |
|---|---|---|---|---|
| all normal screens | 19.4% | **8.3%** | 12.5% | **8.3%** |
| all risky screens | | | 73.2% | **73.2%** |

| normal set | warnings today | new |
|---|---|---|
| Dragon Ball / DBZ | 30.6% | 10.0% |
| clean anime | 19.2% | 7.7% |
| anime characters talking | 14.2% | 3.3% |
| bald anime characters | 37.5% | 21.2% |
| ordinary anime outfits | 43.3% | 21.1% |
| men talking | 10.0% | 5.0% |
| women talking | 21.7% | 5.8% |
| bald / close-up faces | 5.3% | 2.6% |
| people indoors | 17.5% | 6.2% |
| everyday | 5.0% | 1.2% |

Risky sets, closed today -> new: porn 86.7 -> 86.7, hentai 44.2 -> 44.2, sexy 76.7 -> 76.7, revealing 65.0 -> 65.0,
fanservice 93.3 -> 93.3. On the half never used for tuning: no risky screen missed that today's rule catches.

## What it does not fix

- The "porn" score of the first model still misreads some faces and bare skin on photos the same way frame after frame; agreement
  cannot remove a mistake that repeats. Raising that threshold further loses real porn, so it stays.
- Bald anime characters and ordinary anime outfits still show the warning on about 1 screen in 5.
- F3 to F5 are built (docs/FEATURES.md 3.13). A judge trained on the public frames alone does not pass its own ship test (cutoff
  0.999, no false close removed): the public scores cannot tell these false alarms from real hits, as the learned head showed
  above. It needs his labelled false alarms, about 150 of them.
