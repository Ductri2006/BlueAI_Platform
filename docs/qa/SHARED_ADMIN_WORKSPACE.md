# Shared admin workspace — acceptance checklist

## Contract

This deployment serves one Bluecom enterprise workspace. Documents uploaded by
admins and bots created by admins are managed jointly by active admins. The
original creator remains recorded; no rows or files are reassigned or copied.
Private user-owned documents, legacy private bots, conversations and memories
remain isolated. Owner-only repository methods used by machine integrations
must not be broadened to implement administrative sharing.

Saving a draft does not publish or activate it. Publishing retains immutable
releases. Applying a bot changes the single global **Hỏi BlueAI** selection;
other bots, their configurations, releases and public widgets remain available.
Two admins saving the same revision must receive one success and one 409,
not silently overwrite each other. Concurrent activations must leave exactly
one global active selection.

## Automated coverage

`SharedAdminWorkspaceIntegrationTest` exercises the HTTP controllers, database
queries and transactions. Retrieval/completion responses are synthetic fixtures;
they do not verify the quality of a real model or the approval of real products.
`SharedAdminWorkspaceMySqlIntegrationTest` repeats the same cases, including
concurrency, on a named disposable MySQL database. Its environment gate and URL
guard reject a production database. A skipped gate is not a passed test.

## Manual checks with two existing test admins

Use separate browser profiles/sessions for admin A and admin B. Never share
passwords or reuse the same login to simulate two users. Use approved QA data.
Do not publish private files or delete production documents for these tests.

| ID | Action | Expected |
|---|---|---|
| SA-01 | A uploads a harmless QA document; B reloads the document list | Both see the same document ID/status, without another upload. |
| SA-02 | B uploads another QA document; A searches its literal filename | Search/count/pagination include documents from both admins. |
| SA-03 | Filter READY/PENDING and use filenames containing `%`/`_` | Status and literal-character filters remain correct. |
| SA-04 | In isolated QA, attempt to open/delete another regular user's private document | Not found; it does not appear in the shared list. |
| SA-05 | Sign in as a regular user or without authentication | Admin document/studio management remains forbidden/unauthenticated. |
| SA-06 | A creates a bot; B reloads BlueAI Custom | Both can select and open the same bot. No forced duplicate bot. |
| SA-07 | B edits A's bot and saves | A reloads and sees the new draft; other bots remain unchanged. |
| SA-08 | A and B open the same revision, then both save | Second stale save returns a revision conflict; reload before retrying. |
| SA-09 | In isolated QA, save simultaneously from A/B | One 200 and one 409; full intents/entities/dialog remain intact. |
| SA-10 | B publishes a bot originally created by A | Release is created on that same bot, without changing its creator/key. |
| SA-11 | Apply another published bot | Only the main chat selection changes. All bots/releases remain listed. |
| SA-12 | In isolated QA, activate two published bots simultaneously | Exactly one global selection; neither bot is deleted. |
| SA-13 | Open an already published widget after switching the main bot | Widget remains on its own published bot/release. |
| SA-14 | Select a READY shared document from another admin for a QA widget | Save/publish and retrieval work for that explicit allowlist. |
| SA-15 | Attempt to select another user's private, pending or deleted document | Reject it; do not expose its content in widget answers. |
| SA-16 | As B, ask a KB question grounded in A's shared document | Answer includes that document's citation when retrieval finds it. |
| SA-17 | A attempts to read B's private conversation | Not found; shared KB does not mean shared chat history. |

## Operational boundaries

- Reload the web page after deployment to refresh the account's cached list.
- A shared READY document is not automatically approved for public release.
  Widget document selection remains explicit; CSV labels are not row-level ACLs.
- Removing an admin or changing roles may affect role-based workspace membership;
  do not use this patch as a multi-organization/tenant boundary.
- Do not remove existing KB/index volumes, run database resets, or upload the same
  production files again to work around this visibility bug.
