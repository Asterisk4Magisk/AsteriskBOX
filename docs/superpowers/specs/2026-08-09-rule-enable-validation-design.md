# DNS and Route Rule Enable Validation Design

## Goal

Make top-level DNS and route rule enable operations safe and visually seamless:

- validate the candidate sing-box configuration before committing an enable;
- show the switch and card as enabled immediately, without a loading indicator or disabled styling;
- roll the optimistic state back and notify the user only when validation or commit fails;
- keep disable operations immediate.

## Scope

This change covers the top-level rule switches on the DNS management page and the routing management page.

It does not change:

- logical child-rule switches inside the route rule editor, because those switches modify only the editor draft and the parent rule is validated when saved;
- the home page service switch or its progress indicator;
- rule editing, deletion, or reordering behavior beyond removing the DNS page's global disabled visual state during enable validation.

## Current Behavior

DNS rule enables build and validate a candidate configuration before committing it. While validation runs, `savingRule` disables the full DNS rule grid. Material card and switch disabled colors are therefore rendered briefly, causing the observed flash when validation completes almost immediately.

Route rule enables commit the `enabled` flag immediately. Route rule saves are validated, but disabled rules are filtered out of generated sing-box configuration. A disabled route rule can therefore be enabled later without ever being included in `Libbox.checkConfig`.

## Interaction Design

Enabling a top-level DNS or route rule uses an optimistic presentation:

1. The target switch immediately displays the enabled state.
2. The target card immediately uses its enabled opacity.
3. Candidate configuration validation runs in the background.
4. No progress indicator, disabled color, status text, or page-wide interaction change is shown.
5. Repeated input on the target switch is ignored while its enable is pending, without visually disabling the switch.
6. On success, the candidate state is committed and the existing optimistic presentation remains unchanged.
7. On validation failure or a compare-and-set conflict, the pending presentation is cleared, the switch returns to disabled, and the existing page notification mechanism reports the failure.

Disabling a rule remains immediate and does not run validation. Removing a rule from generated configuration cannot introduce a configuration error from that rule.

Only one rule enable is allowed to be pending per management page. Other page interactions remain visually available. If another state update invalidates the candidate snapshot before commit, the compare-and-set check rejects the stale candidate and follows the failure rollback path.

## State and Data Flow

Each management page owns a nullable pending rule ID. A rule's displayed enabled state is:

```text
rule.enabled || pendingEnableRuleId == rule.id
```

The persisted `AppState` is not changed optimistically. The page builds a candidate state from the current `AppState`, validates that candidate off the main thread, and commits it only if the current state is still the exact base snapshot. This preserves the existing transactional behavior and avoids persisting a configuration that failed validation.

The rule card receives the displayed enabled state separately from the persisted rule model so both the switch and card opacity follow the optimistic presentation. While the rule ID is pending, the switch retains enabled colors but does not invoke another state-change callback.

## Shared Validation Behavior

DNS and route pages should use the same validate-then-commit semantics:

- input: base `AppState`, candidate `AppState`, suspend validation callback, compare-and-set commit callback;
- output: whether the candidate was committed;
- validation exceptions propagate to the page so its existing failure logging and notification path remains authoritative;
- a failed compare-and-set returns `false` without overwriting newer application state.

The implementation may extract the existing DNS helper to a neutral shared location if that produces a clearer dependency boundary. It must not make routing code depend on the DNS feature package.

## Error Handling

Validation errors and stale-state commit conflicts have the same user-visible result: clear the optimistic presentation, restore the persisted disabled state, and show the page-specific enable-failure message.

Coroutine cancellation is rethrown and must not be converted into a user-visible validation failure. Pending presentation state is always cleared in `finally`.

## Testing

Automated tests must cover:

- validation runs before an enable candidate is committed;
- a successful validation commits the candidate;
- a validation exception does not commit the candidate;
- a stale base-state conflict does not overwrite newer state;
- disabling remains an immediate state update;
- displayed enabled state is true for the pending target rule and unchanged for other rules;
- clearing a failed pending enable returns the target to its persisted disabled presentation.

The focused unit tests and the existing application unit-test suite must pass. A debug Kotlin compilation or Android debug build must also pass to catch Compose call-site and resource errors.

## Acceptance Criteria

- Enabling a valid DNS rule produces one normal switch-on transition with no card/list disabled flash.
- Enabling a valid route rule produces the same visual behavior and commits only after sing-box configuration validation succeeds.
- Enabling an invalid DNS or route rule returns the switch and card to disabled presentation and shows an error.
- Disabling either rule type remains immediate.
- The home page service switch is unchanged.
