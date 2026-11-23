# Note for Phase 4: how fast humans take turns

Background for choosing the default end-of-utterance settings in P4-R1. Written 2026-11-22 at the owner's request. This is not a decision; the decision belongs to P4-R1.

## Human reference points
- **Gap between turns, person to person: about 200 ms on average,** with the same pattern in 10 languages (Stivers et al., PNAS 2009, as cited by Levinson & Torreira 2015 and Holler et al. 2015; the exact figure was not checked in the original paper).
- **200 ms is shorter than the time needed to plan even one word.** Listeners therefore *predict* the end of the other person's turn and start planning their reply while the other is still talking (Levinson & Torreira 2015).
- **Telephone delay (ITU-T G.114).** Under about 150 ms one way, most users notice nothing. 400 ms one way is the ceiling for network planning, and highly interactive conversation suffers below that.

## What it means for Wednesday
- **A silence threshold adds itself to latency.** With a 600 ms end-of-speech silence, the reply cannot start sooner than 600 ms after the user stops, before STT, LLM and TTS even begin. Push-to-talk (Phase 3) costs 0 ms here, because releasing the button is the end signal.
- **Trade-off for P4-R1:**
  - a short threshold cuts people off when they pause to think;
  - a long one makes every reply slower.

  The spec already asks for "silence threshold plus final transcript". A useful refinement is an adaptive threshold: short when the transcript looks complete (sentence-final punctuation from the STT, a closed question), longer when it does not (it ends in "y…", "pero", "que").
- **The spec's p50 target of 1.2 s to first audio is already about 6 times the human gap.** Every millisecond spent waiting for silence comes out of that budget.

## Sources
- Stivers et al. 2009, "Universals and cultural variation in turn-taking in conversation": https://pubmed.ncbi.nlm.nih.gov/19553212/
- Levinson & Torreira 2015, Frontiers in Psychology: https://www.frontiersin.org/articles/10.3389/fpsyg.2015.00731/full
- Holler et al. 2015 (editorial): https://eprints.whiterose.ac.uk/116186/1/Holler_et_al._2015_.pdf
- ITU-T G.114 (2003): https://www.itu.int/dms_pubrec/itu-t/rec/g/T-REC-G.114-200305-I%21%21SUM-HTM-E.htm
