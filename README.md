# Amazon Music Playlist Scraper

## Quick Reference: Agentic Change Rules
**All agents and developers MUST follow these rules for every change. The system will automatically log, validate, and update context and documentation.**

- Plan all changes with explicit, priority-tagged TODOs (PRIORITY: HIGH, MEDIUM, LOW).
- Validate and update Javadocs and comments after every change.
- **Log progress in the README after every action, including code changes, refactors, and documentation updates.**
- **After any code edit action (including minor changes), immediately log the change in the README under the Agentic Change Iteration Summary. No code edit is complete until this log is updated.**
- **Validate and update all related documentation and comments after each change.**
- **After every code change, review all affected files for outdated documentation and comments.**
- **Compare the current implementation against the README log summary and outstanding TODOs.**
- **Update Javadocs, inline comments, and README sections to accurately reflect the latest code and workflow.**
- **Log the validation and any documentation updates in the README under the Agentic Change Iteration Summary.**
- **If any discrepancies or outdated documentation are found, resolve them immediately and log the fix.**
- Update README, inline comments, and TODOs whenever code changes affect workflow, extensibility, or logging.
- Log all major actions, decisions, and error cases in code and documentation.
- Never skip these steps—context and documentation must always be up to date.

## Overview
This project scrapes playlists and songs from Amazon Music, validates and enriches metadata, and exports results to CSV and PostgreSQL. The workflow is robust, modular, and extensible for future metadata sources and validation logic.

## Workflow
1. **Extraction**: Metadata for each song is extracted from the Amazon Music site using multiple selectors per field.
2. **Cross-Checking**: The `MetadataCrossChecker` utility normalizes and cross-checks values from all selectors, prioritizing reliable sources and assigning a confidence score.
3. **Validation & Enrichment**: After extraction, external validation (e.g., via `MusicBrainzClient`) may be performed to further validate and enrich metadata. This adjusts confidence scores and sets the `validated` flag in Song objects.
4. **Provenance & Confidence**: Provenance for each field is tracked in the `sourceDetails` map, recording which selectors produced which values. The `confidenceScore` reflects the reliability of the extracted and validated metadata.
5. **Export/Import**: All Song fields, including provenance and validation status, are exported to CSV and imported into PostgreSQL. The schema supports future extensibility for per-field validation and enrichment.

## Key Classes & Fields
- **Song**: Immutable record with all metadata fields, plus `trackAsin`, `validated`, `confidenceScore`, and `sourceDetails` for provenance.
- **Playlist**: Immutable record containing playlist metadata and a list of Song objects.
- **MetadataCrossChecker**: Utility for selector-based cross-checking, normalization, and confidence scoring.
- **MusicBrainzClient**: Utility for validating/enriching metadata via the MusicBrainz API.
- **CsvService & PostgresService**: Export/import all Song fields, including provenance and validation status.
- **ScraperService**: Integrates all workflow steps, tracks TODOs, and logs actions for agentic traceability.

## Extensibility
- Per-field validation status can be added to Song for granular validation tracking.
- Additional metadata sources (e.g., other music APIs) can be integrated via new client utilities.
- The workflow supports future enrichment of genre, release date, and other fields.
- TODO (PRIORITY: MEDIUM): Refactor fieldNames/results arrays to use a central config, enum, or registry for extensibility. When adding new metadata fields, update this registry and all consumers (CSV, DB, validation) to ensure consistency.
- TODO (PRIORITY: LOW): Validate provenance structure after enrichment/validation for future sources.
- TODO (PRIORITY: LOW): Add more robust handling for interrupted manual login and consider consolidating manual login prompts.

## Logging & Debugging
- Selector discrepancies and provenance are logged for traceability.
- Validation results and ambiguous extractions are logged and can be saved as debug artifacts.
- All major actions, decisions, and error cases are logged in code and documentation for agentic systems.

## Contribution & Maintenance
- All core classes are documented with Javadocs describing their role in the workflow.
- The architecture is modular and supports dependency injection for testing and extension.
- See class-level Javadocs for details on each component's responsibilities and extensibility points.
- All TODOs are tagged by priority and updated as work progresses. Resolved TODOs are marked and removed as appropriate.

## Robust Authentication Detection

### Improvements (2025)
- The authentication check now combines DOM-based detection (profile/account elements, sign-in button) with session cookie validation (e.g., `at-main`, `sess-at-main`, `x-main`).
- This ensures login state is correctly recognized even when cookies are loaded from a previous session and the UI is ambiguous.
- Logging is added for all authentication states, including ambiguous cases.
- See `ScraperService.isSignedIn(Page page)` for details.

## Navigation Robustness

### Improvements (2025)
- Navigation to the library playlists page now constructs the absolute URL for `/my/library` using the current page's base URL, ensuring correct navigation even if `/my/library` is a relative href.
- The workflow waits for the playlists section, clicks the "Playlists" pill/tab, and clicks the "Show all" button if present.
- All steps are logged for traceability and debugging.
- See `ScraperService.goToLibraryPlaylists(Page page)` for details.

---

## Agentic Change Iteration Summary (2025)
- All major workflow TODOs are resolved except for medium/low-priority extensibility and provenance validation items.
- Javadocs and inline comments are up to date and match the current implementation.
- **[2025-09-04] Fixed: All Song class instantiations now provide all required fields, including provenance and validation status. MusicBrainzClient.java was updated to match the Song record definition.**
- **[2025-09-04] Progress Log:**
    - Refactored ScraperService to use MetadataField registry for all metadata extraction, provenance, and validation logic. Hardcoded selector arrays removed; processSongCandidate and related methods now use MetadataField for extensibility.
- **[2025-09-04] Log: Refactored CsvService and PostgresService to use central MetadataField registries (CSV_FIELDS and DB_FIELDS) for header/schema generation and field mapping. This enables future extensibility and ensures all consumers use a single source of truth for exported/imported fields.**
- **[2025-09-04] Log: Added provenance validation method to MetadataCrossChecker. This method checks that all sourceDetails entries are non-null maps and logs inconsistencies, supporting future extensibility and robust provenance tracking.**
- **[2025-09-04] Outstanding TODOs and next iteration plan:**
    - [MEDIUM] Refactor MetadataCrossChecker to accept MetadataField for field type inference and normalization. Next: Update CsvService and PostgresService to use MetadataField for headers/schema.
    - [LOW] Validate provenance structure after enrichment/validation for future sources. Next: Add a provenance validation method in ScraperService and MetadataCrossChecker to ensure all sourceDetails values are non-null maps and log inconsistencies.
    - [LOW/MEDIUM] Add more robust handling for interrupted manual login and consolidate manual login prompts. Next: Refactor AuthService to handle browser closure, network errors, and user abort during manual login. Consider merging displayBrowserForManualLogin and waitForUserToContinue into a single workflow, and optionally add a callback/event system.
    - [LOW] Genre and release date enrichment not yet implemented. Next: Add extraction logic for genre and releaseDate in ScraperService and update Song construction accordingly.
    - [MEDIUM] Replace safeWait with Playwright's recommended waits in navigation logic. Next: Refactor ScraperService.goToLibraryPlaylists to use page.waitForSelector and page.waitForLoadState instead of page.waitForTimeout.
- Logging and documentation practices are aligned with agentic change rules.
- The codebase is ready for further iteration or extension.
- All changes, resolutions, and outstanding TODOs are logged here for traceability.

**[2025-09-04] Log: README update enforced: After any code edit action (including minor changes), immediately log the change in the README under the Agentic Change Iteration Summary. No code edit is complete until this log is updated. This rule is now mandatory for all future changes.**

- **Implementation Steps:**
    - For any new feature, refactor, or fix, first write explicit implementation steps as TODOs in the README before making code changes.
    - Each TODO should be tagged with PRIORITY and describe the planned change, affected files/classes, and expected outcome.
    - After each code edit, immediately log the change in the README under the Agentic Change Iteration Summary.
    - Validate and update all related documentation and comments after each change.
    - [LOW/MEDIUM] Robust manual login handling in AuthService:
        - [TODO] Merge displayBrowserForManualLogin and waitForUserToContinue into a single workflow.
        - [TODO] Add error handling for browser closure and network issues during manual login.
        - [TODO] Add user abort detection and logging.
        - [TODO] Optionally add a callback/event system for extensibility.
        - [TODO] Update documentation and log changes in the README after implementation.
    - [HIGH] Documentation and comment validation after every change:
        - [TODO] After every code change, review all affected files for outdated documentation and comments.
        - [TODO] Compare the current implementation against the README log summary and outstanding TODOs.
        - [TODO] Update Javadocs, inline comments, and README sections to accurately reflect the latest code and workflow.
        - [TODO] Log the validation and any documentation updates in the README under the Agentic Change Iteration Summary.
        - [TODO] If any discrepancies or outdated documentation are found, resolve them immediately and log the fix.

