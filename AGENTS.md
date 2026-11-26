# Floatdesk engineering notes

Read [XR regression requirements](docs/xr-regression-requirements.md) before changing XR placement, rendering, input, streaming, or menus. It records the latest accepted requirements and separates them from open hardware-dependent issues.

Read the [architect handoff](docs/architect-handoff.md) for subsystem ownership, native lifecycle constraints, and verification priorities.

Check the working tree and recent history before changing behavior. Keep concurrent agents' changes unless there is a concrete reason to revise them. When fixing a regression, identify the behavior that was lost, add a focused check for the invariant when possible, and update the notes in the same change. For visual or tracking issues, distinguish code and log checks from a headset observation. A successful build or a decoded frame count does not establish correct composition or input.
