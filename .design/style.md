# LensLock design lock

Camera chrome on a full bleed preview. Plain words, no abbreviations, no text
over the status bar.

## Tokens

| Role | Value |
|---|---|
| Screen background | `#0B0B0B` |
| Glass panel | `#CC141414` fill, `#26FFFFFF` hairline |
| Raised glass (panel, toast) | `#E61C1C1C` fill, `#26FFFFFF` hairline |
| Primary text | `#F2F2F2` |
| Secondary text | `#B3FFFFFF` |
| Active segment text | `#111111` on `#F2F2F2` |
| Error text | `#FF9A8F` |
| Focus ring | `#FFC24B` |

Radii: pills 19 to 24, panels 20, thumbnail circle. One radius language, no
squares in a pill layout.

Spacing: 4 / 8 / 12 / 16 / 24. Outer margins 16, gaps inside a group 8 to 12.

Type: system sans. 14sp labels and controls, 13sp values and status, 16sp never
used smaller than 13. Nothing below 13sp.

Touch targets: 46dp minimum for segmented controls, 44dp for zoom pills, 50dp
thumbnail, 78dp shutter. Nothing tappable under 44dp.

Zoom row: field of view, never crop factor. Normal shows 1x 2x 3x 4x, Macro shows
0.5x 1x 2x. The white indicator is drawn at the interpolated zoom position, so it
slides between markers instead of snapping, and the labels fade from muted to dark
as the indicator covers them. Pinching reports the live value in the status pill
on every frame, and the pill keeps its slot so showing it never shifts layout.

## Rules

- No abbreviations. "Focus", "Brightness", "Normal", "Macro", never AF, MF, EV,
  UW, AE.
- No text line at the top of the screen. The lens control is the top element and
  it sits below the status bar inset.
- No eyebrows, no captions, no helper lines. Every visible string either operates
  a control or reports what just happened.
- One accent at a time: white for active state, amber only for the focus ring,
  red only for errors.
- Motion: 90 to 180ms, decelerate in, used for press and panel reveal only.

## Measured contrast

| Pair | Ratio |
|---|---|
| Primary text on glass, dark scene | 16.5:1 |
| Primary text on glass, blown out preview | 8.8:1 |
| Secondary 13sp on glass, blown out preview | 5.9:1 |
| Active segment text on white pill | 16.9:1 |
| Error text on glass | 9.0:1 |

All above WCAG AA for their size.
