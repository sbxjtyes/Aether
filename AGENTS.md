# Aether Agent Notes

## UI Design

- Aether's UI language avoids visible borders on controls, buttons, cards, and settings surfaces.
- Prefer soft fills, spacing, typography, and state color changes over stroked outlines.
- Do not introduce bordered Material controls unless the user explicitly asks for a border.

## Android Development

- Keep signing keys and release credentials outside the repository.
- Always build and install the Release APK on the connected device; do not use the Debug variant.
- Install an updated APK to the connected ADB device after code changes before handing off, unless the user explicitly asks otherwise.
- Do not perform post-install manual testing unless the user explicitly asks; the user will handle verification on-device.


## Notes

- rg is not available on this machine
- Always exclude build and temporary folders when searching for files.
