# Project notes for Claude

## Target devices: landscape only

SiphonDSP_J runs on a **1280×480 mdpi landscape car head unit** (`isHeadUnitDisplay()`, the main
target) and on **phones in landscape**. It is **landscape only**.

- **Portrait is not supported.** Portrait layouts and portrait-only code paths are unmaintained.
- Phones in landscape (roughly 640–960dp wide, 360–480dp tall) use the non-head-unit layouts
  (`Phone*Page`, the `!isHeadUnitDisplay()` branches); every control must stay reachable there.
- **Code reviewers (human or bot): do not raise portrait findings.** They are won't-fix. Review
  layouts against the 1280×480 head unit and phones in landscape.
- Values are set with steppers, not by dragging on graphs: the head unit is used in a car.

## Pull requests: don't watch them

- **Never stay subscribed to a PR.** Opening a PR from a cloud session subscribes the session
  automatically, but a few seconds late, so an unsubscribe sent right after creating the PR can
  land first and be undone. Wait for the `subscription.created` event, then unsubscribe.
- Don't enable bot autofix. Review findings are checked when the user says "check bugs"; fix real
  bugs without asking for approval.
