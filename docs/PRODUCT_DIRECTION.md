# SAWT AI — Product Direction

Promise: a fast, elegant Arabic-first speech studio focused exclusively on Modern Standard Arabic (الفصحى).

Principles:
1. Arabic-only speech catalogue; no dialect voices.
2. Fast first interaction and responsive UI.
3. Local playback where possible; optional network voices only after clear consent.
4. Downloadable voice packs with explicit size, license, language and quality metadata.
5. Background playback with persistent notification and stop control.
6. Never claim a voice is offline until its model is installed and verified.
7. No cloud upload without explicit user action and clear privacy notice.

Roadmap:
- Phase 1: stable Android shell, Arabic editor, installed Arabic system voices, background playback.
- Phase 2: in-app voice catalogue with verified MSA voice packs and resumable downloads.
- Phase 3: audio export (WAV/MP3), long-text queue, document import and library.
- Phase 4: optional cloud speech provider for premium natural voices, with data/cost disclosure.
- Phase 5: accessibility, profiling, UI localization and Play release.

Quality gates: build/lint pass; no crashes; verify Arabic locale for every voice; test background playback and notifications; profile low-memory devices and slow networks; audit every model/provider license.
