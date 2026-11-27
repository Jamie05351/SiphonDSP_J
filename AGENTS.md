# Notes for agents and code reviewers

## Target device: landscape head unit only

SiphonDSP_J targets one device: a **1280×480 mdpi landscape car head unit** (`isHeadUnitDisplay()`).
It is **landscape only**.

- Phone, portrait, tablet and other screen sizes are **not supported**. The leftover phone / compact
  code paths (for example `PhoneCrossoverPage`, the `!isHeadUnitDisplay()` branches) are unmaintained
  and may be incomplete; that is intentional.
- **Code reviewers (human or bot): do not raise phone, portrait or narrow-width layout findings.**
  They are won't-fix. Review layouts against the 1280×480 head unit only.
- Values are set with steppers, not by dragging on graphs: the head unit is used in a car.
