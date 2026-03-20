# Group 5 — Open TODOs

---

## TODO-001 — Calibrate NDMI scaling coefficients against field measurements

**File:** `GridInitialiserService.java`
**Constants:** `NDMI_DRY_ANCHOR`, `MOISTURE_MIN`, `MOISTURE_SLOPE`, `MOISTURE_MAX`

**Current values** are conservative estimates for East African savannah, not
field-calibrated. They were adopted from Group 2's note as a working starting
point:

| Constant | Current value |
|---|---|
| `NDMI_DRY_ANCHOR` | `−0.1` |
| `MOISTURE_MIN` | `0.03` |
| `MOISTURE_SLOPE` | `0.35` |
| `MOISTURE_MAX` | `0.40` |

**Required action:**
When field moisture measurements for the Aberdare deployment area become
available, update these four constants to match. No other class needs to change.
After updating, record the new values and their source in `deviation-discourse.md`.

**Risk of leaving unaddressed:**
Incorrect moisture fractions will produce incorrect ROS values from
`RothermelRosCalculator`. Fire will spread too fast in wet conditions or not
spread at all in dry ones. This is a fire physics accuracy issue, not a
correctness issue — the simulation will run without errors either way.

**Owner:** Group 5 (constants live in `GridInitialiserService`)
**Dependency:** Field measurement data — not yet available at time of writing.