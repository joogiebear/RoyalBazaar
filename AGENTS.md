# Royal plugins: development standards

Everything above "This repo" is the same in every Royal plugin repo. Read it before changing code. Rules that only apply here, if any, are at the end.

## Git identity

- Author and committer on every commit: `joogiebear <joogiebear@protonmail.com>`.
- No co-author trailers, AI attribution, "generated with" footers, session links or assistant signatures in commits, PR titles or PR descriptions.
- Set up a fresh clone with:

  ```sh
  git config --local user.name joogiebear
  git config --local user.email joogiebear@protonmail.com
  git config --local user.useConfigOnly true
  ```

- Before pushing, check `git log --format='%an <%ae> | %cn <%ce>' origin/<base>..HEAD` and read the full message of every outgoing commit.
- Do not rewrite published history to make old commits conform.
- Commit messages: `<type>: <short summary>` (`fix: cache island lookups per tick`), blank line, a body only when the reason isn't obvious from the diff. Types are listed below.

## Pull requests

- Title: `<type>: <summary>` or `<type>(<scope>): <summary>`. A CI check fails the PR otherwise. Types: `fix`, `feat`, `build`, `chore` (whitespace, formatting, typos, comments), `docs`, `test`, `refactor`, `ci`, `localize`, `bump`, `revert`. Comment cleanup PRs are `chore`.
- Description: `**Type:**`, then `## What changed`, `## Why`, `## How it was checked` (the layout of `.github/pull_request_template.md`). Plain sentences, no checklists of things that weren't done.
- One kind of change per PR. Comment cleanup, performance work, bug fixes and features go in separate PRs.
- Must build and pass tests: `mvn test -B` (Maven) or `./gradlew build` (Gradle).
- Anything that changes runtime behaviour is checked on a real Paper server (Spawnloft test server) before merge. Say in the PR whether that happened.

## Comments

Write comments the way an experienced developer does when someone else will maintain the code: rarely, and only where the code can't speak for itself.

**Keep a comment when it:**
- explains *why* something is done in a way that looks wrong or roundabout (`// next tick: the click hasn't been answered yet, so a resync now gets overwritten`)
- warns about a trap (`// not thread-safe, call from the main thread`)
- shows how to use something: valid options, an example value, a formula (`// mode: "shared" or "per-profile"`)
- documents a public API method other plugins call (Javadoc)

**Remove or rewrite:**
- **Section banners** of any kind: `// ── protection ───`, `// =====`, `// ---- helpers ----`. If a class needs signposts, it probably needs splitting.
- **Comments that restate the code:** `// save the player`, `// loop through islands`.
- **Bug-history stories:** `// which is how a server ended up with six tracks...`. That belongs in the commit message. Keep only the rule that remains: `// keep the list exact; a stale entry lets a later file reuse the exemption`.
- **Essays.** A comment longer than about 3 lines should usually become 1–2 lines or be deleted. Long explanations go in the commit message or the README.
- **Javadoc on private and package-private helpers.** Delete it, or turn it into a one-line `//` comment if it carries a real "why".
- **Javadoc that repeats the signature:** `@param player the player`, `@return the island`.
- **Em-dashes (—) in comments and user-facing strings.** Use a comma, colon, full stop or parentheses instead. Plain ASCII hyphens are fine.
- **Over-formal, narrated tone.** Say it plainly: `// chunk may be unloaded here` rather than `// It is important to note that the chunk may, at this point, no longer be loaded.`

When a comment is removed, check whether the code itself needs a better name to stay clear. Renaming a variable or extracting a small method beats a comment.

Config files (`config.yml` etc.) are different: server owners read them, so short comments explaining each option and its valid values are expected. Apply the same plain tone there, no banners made of `#####`, no essays.

## Other things to avoid

- Emoji or check-mark symbols in console logs, chat messages or the README.
- Marketing words in READMEs and messages: comprehensive, robust, seamless, powerful, enhanced, leverage, blazing-fast.
- Catch blocks that log and swallow every exception "just in case". Catch what can actually be thrown and handle it, or let it propagate.
- Defensive null checks on values that can't be null.
- Log spam: INFO for things a server owner cares about, FINE/debug for the rest.

## Review rubric

Used for code reviews and the review agents. Rank findings by real impact on a live server.

**Performance (main thread first)**
- Database, file or network I/O on the main thread (including in event handlers, commands, menu clicks, `onDisable` aside).
- Heavy work in hot event handlers: `PlayerMoveEvent`, `BlockPhysicsEvent`, `EntityMoveEvent`, `InventoryClickEvent`, `ChunkLoadEvent`, `BlockFromToEvent`. Early-return on cheap checks first.
- Repeating tasks that scan all players, all chunks or all islands every tick when they could run less often or be event-driven.
- Loading chunks unintentionally (`getBlock` / `getChunk` on unloaded areas).
- Missing caches for values recomputed constantly; caches that never evict.
- Expensive calls in loops: config lookups, `Bukkit.getPlayer` by name, regex compilation, `ItemStack#clone`, ItemMeta reads, PlaceholderAPI resolution.
- Blocking `.join()`/`.get()` on futures from the main thread.

**Correctness and safety**
- Bukkit API used off the main thread.
- Shared state accessed from async tasks without synchronisation or a concurrent type.
- Money and item duplication paths: anything that gives an item or currency before the removal/withdrawal is confirmed, or can be triggered twice by double-clicking, closing a menu or disconnecting.
- Data loss on crash or reload: unsaved state, writes that aren't atomic.
- SQL built by string concatenation with user input.
- Listeners or tasks that aren't cleaned up on disable/reload.

**Code quality**
- Dead code, unused config options, duplicated logic that should be shared.
- Very large classes or methods that mix several jobs.
- Deprecated Paper/Bukkit API with a direct replacement.
- Outdated or unused dependencies, shaded libraries not relocated.

**Cross-plugin**
- Logic that several Royal plugins each implement separately (sign input, menus, economy hooks, profile resolution, storage) and could share.
- Inconsistent behaviour between plugins doing the same thing.

## Finding format (review agents)

For each finding:
- **Severity:** high (server-impacting or exploit), medium, low
- **Where:** `path/File.java:line`
- **What:** one or two sentences
- **Fix:** the concrete change
- **Confidence:** confirmed by reading the code path, or suspected

Do not report style nitpicks as performance issues. Do not report something as a bug without tracing the code path that triggers it.

## This repo

No repo-specific rules.
