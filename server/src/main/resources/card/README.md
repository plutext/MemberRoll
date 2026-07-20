# Membership card assets (CR-017)

Bundled classpath resources for the `Cards` renderer:

- `DejaVuSans.ttf`, `DejaVuSans-Bold.ttf` — the card face fonts. A headless
  server's font set is unreliable, so the renderer loads these with
  `Font.createFont` rather than trusting `new Font("SansSerif")`. Bundling is
  permitted by the Bitstream Vera / DejaVu licence (`DejaVuFonts-LICENSE.txt`,
  ships alongside).
- `logo.png` — **optional, not present by default.** The renderer draws the
  card text-only when this resource is absent, and places the logo in the
  top-left slot when it is present. This is a repo asset, not an upload (the
  fork-distribution model: a fork drops in its own file). A ~square PNG is
  best; the renderer scales it to fit the header band.
