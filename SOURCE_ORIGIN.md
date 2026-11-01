# Source provenance and refresh status

Refreshed on 2 November 2026 from the committed school repository without modifying its working tree.

| Item | Recorded value |
|---|---|
| Source | [School CampusFix repository](https://github.sydney.edu.au/2026S2-Object-Oriented-App-Frameworks/ELEC5619-Lab03-Group-03) |
| Source branch | `main` |
| Source commit | `53e0134e1d416d513db6b89954b0c84e872b2d9f` |
| Original reachable commits | 96 |
| Retained source-history commits | 92 |
| Tian-authored retained commits | 71, including merges |
| Retained source files | 136 |
| Filtered source tip | `8a2ce79b4b7b24f3c40eb3d27eefae33a457046d` |

The final two upstream commits add private reports/presentations only. Their removal explains the filtered tip's earlier date. The publication merge preserves the old portfolio and connects the filtered source lineage without force-rewriting the public branch.

## Scope

Retained: Java source/resources/tests, Gradle build and wrapper files, HTML/CSS/JavaScript, frontend tests, setup scripts, placeholder configuration and technical guides. The source README is retained at `docs/upstream/README.md`; its relative paths describe the original repository root. Use the current root README for this checkout.

Excluded from the imported lineage: databases, user records, credentials, binary reports/slides, build/cache directories and unverified decorative media. The Gradle wrapper is the explicitly retained third-party binary and contains its licence.

Names, timestamps, commit order and relevant messages remain. Private author emails and personal contact values are anonymised; synthetic fixture domains are unchanged. Filtering changes hashes and removes empty commits. Counts above refer to this import, not the combined portfolio branch.

## Verification

On 2 November 2026: 32 backend tests across 17 test classes and 39 frontend tests passed, with no failures/errors/skips. JavaScript syntax checks passed; upstream Markdown hard-break spaces were preserved. Retained history was checked for common API-token/private-key formats and non-test private email addresses. This is a precaution, not a formal security certification or a real-email/maps/production acceptance test.

## Future updates

This is a manual reviewed snapshot, not an automatically synchronising fork. The source URL and commit provide traceability. Future refreshes must fetch the school repository, select committed source, repeat history/privacy checks, test the filtered export and merge without force-pushing. Do not mirror private refs, copy raw `.git` directories or implicitly import uncommitted work.
