# ADR 0004: LLM-as-a-Judge Evaluation System

**Date:** 2026-11-25  
**Status:** Proposed  
**Deciders:** Owner

## Context

Wednesday needs systematic evaluation of response quality against system prompt rules. Manual review doesn't scale and is inconsistent. We need:

1. **Automated evaluation** of responses against 9 specific criteria
2. **Consistency** across evaluations (same input → same score)
3. **Scalability** to evaluate batches of responses
4. **Actionable feedback** on what failed and why
5. **Tracking** of quality over time (regression detection)

## Decision

Implement **LLM-as-a-Judge** evaluation system using Gemini as the judge:

### Architecture

```
evaluation/
├── EvaluationCriteria.java      # 9 criteria from system prompt
├── EvaluationResult.java        # Scoring model
├── ResponseJudge.java           # Judge interface
├── GeminiJudge.java            # Gemini implementation
├── TestCases.java              # 12 predefined test cases
├── EvaluationReport.java       # Report generation
└── WednesdayEvaluationSuite.java # JUnit executable tests
```

### Evaluation Criteria (from system prompt)

1. **BREVITY**: 1-3 sentences
2. **NO_FORMATTING**: No markdown, lists, tables
3. **NO_LISTS**: Natural phrasing instead of enumerations
4. **SPOKEN_NUMBERS**: "veinte" not "20"
5. **NO_EMOJIS_ABBR**: Only pronounceable text
6. **CONVERSATIONAL**: Natural, no filler phrases
7. **NO_TRAILING_QUESTIONS**: Only ask when info missing
8. **LANGUAGE_MATCHING**: Spanish default, English when user uses it
9. **FACTUAL_ACCURACY**: Correct information

### Scoring System

- **Per-criterion**: 0.0 to 1.0
- **Pass threshold**: 0.70
- **Overall score**: Average of all criteria
- **Suite metrics**: % passing all, avg per criterion

### Usage Pattern

```bash
# Quick evaluation
./eval single

# Full suite (12 test cases × 2 = 24 evaluations)
./eval all

# Generate reports
mvn test -Dtest=WednesdayEvaluationSuite
# → target/evaluation-reports/evaluation-report-{timestamp}.txt
# → target/evaluation-reports/evaluation-report-{timestamp}.csv
```

### Programmatic API

```java
ResponseJudge judge = new GeminiJudge(apiKey);

EvaluationResult result = judge.evaluate(
    "¿Qué hora es?",
    "Son las tres y veinte de la tarde."
);

// result.overallScore() → 0.92
// result.passesAll() → true
// result.scores().get(BREVITY).reasoning() → "..."
```

## Alternatives Considered

### 1. Rule-based evaluation (regex patterns)
- **Pros**: Fast, deterministic, no API calls
- **Cons**: Brittle, misses nuance, hard to maintain
- **Verdict**: Too rigid for natural language

### 2. Human evaluation
- **Pros**: Most accurate, catches subtle issues
- **Cons**: Doesn't scale, inconsistent, slow
- **Verdict**: Still needed for edge cases, but not primary method

### 3. Fine-tuned classifier
- **Pros**: Fast inference, low cost at scale
- **Cons**: Needs training data, maintenance overhead
- **Verdict**: Over-engineered for current needs

### 4. Claude/GPT-4 as judge
- **Pros**: Better reasoning, more consistent
- **Cons**: Higher cost, requires separate API key
- **Verdict**: May revisit if Gemini proves unreliable

## Consequences

### Positive

1. **Consistency**: Same input → reproducible scores (±0.05)
2. **Scalability**: Can evaluate 100s of responses unattended
3. **Actionable**: Specific reasoning per criterion
4. **Regression detection**: Track quality over time
5. **Developer velocity**: Fast feedback on prompt changes
6. **Documentation**: Test cases serve as examples

### Negative

1. **API dependency**: Requires Gemini API key & quota
2. **Cost**: ~$0.001 per evaluation (Gemini Flash Lite pricing)
3. **Latency**: ~2-3s per evaluation (batch needs rate limiting)
4. **Variability**: LLM can vary ±0.05 between runs
5. **Cold start**: First evaluation in suite is slower

### Mitigations

- **Cost**: Use Flash Lite model (~10x cheaper than Flash)
- **Rate limits**: 1s delay between evaluations
- **Variability**: Average multiple runs for critical decisions
- **Fallback**: Keep rule-based checks for hard failures (e.g., emojis)

## Implementation Notes

### Test Cases (12 scenarios)

Each test case has:
- Good response (should pass)
- Bad response (should fail on specific criteria)
- Primary criteria to validate

Coverage:
- All 9 criteria tested
- Edge cases (currency, percentages, language switching)
- Multi-criterion violations

### Reports

**Console output:**
```
╔══════════════════════════════════════════════════════════╗
║  WEDNESDAY EVALUATION SUITE                              ║
╚══════════════════════════════════════════════════════════╝

TC001: Debe responder brevemente (1-3 frases)
  Score: 0.92 | Passed: 9/9

...

Average score: 0.88
Passed all: 11/12 (91.7%)
```

**CSV export:**
- Importable to spreadsheets
- Per-criterion scores
- Trend analysis over time

### Integration Points

1. **CI/CD**: Run on PRs that change system prompt
2. **Pre-release**: Evaluate sample of real conversations
3. **Development**: Quick validation after prompt tweaks
4. **Monitoring**: Periodic evaluation of production responses

## Future Extensions

### Phase 7+ (if needed)

1. **Context-aware evaluation**
   - Multi-turn language switching validation
   - Conversation coherence scoring

2. **Latency correlation**
   - Does lower latency hurt quality?
   - Sweet spot detection

3. **A/B testing**
   - Compare prompt variants
   - Statistical significance testing

4. **Fine-tuned judge**
   - Train classifier on labeled data
   - Faster/cheaper at scale

5. **Real-time monitoring**
   - Sample % of production responses
   - Alert on quality drops

## References

- LLM-as-a-Judge paper: https://arxiv.org/abs/2306.05685
- System prompt: `src/main/resources/prompts/system.txt`
- Full documentation: `docs/EVALUATION.md`

## Approval

**Status**: Awaiting owner approval

**Questions for owner:**
1. Acceptable to use Gemini quota for evaluations? (~12 requests per full suite run)
2. Threshold: is 0.70 per-criterion acceptable, or raise to 0.80?
3. Should we run this in CI automatically, or manual only?

**Next steps if approved:**
1. Run initial baseline evaluation
2. Establish score benchmarks
3. Document in PROGRESS.md
4. Add to development workflow
