# MHXX hook points (JPN, title 0004000000197100, update v4.8.0)

Static analysis of the update's `.code` (BLZ-decompressed, 0x100000 .. 0xC71000: text to 0xBF2438).
Method: the same engine as MH4U (MT Framework), so MH4U's recipe applies. Every class registers a
DTI in a static initializer that calls one constructor (here 0x84c5f4) with its name, parent, size.
`getDTI` is vtable slot 5 and `render` slot 12 (docs/research/screen-filters.md).

## Verified (in hooks_mhxx.txt)
Instance sizes equal MH4U's, every vtable is stored at `object+0` by constructors, and uFilter's own
render (0x873f84) is a bare `bx lr`.

| class | vtable | render |
|---|---|---|
| uBloomFilter | 0xcc7c3c | 0x2d1ff4 |
| uMotionBlurFilter | 0xce7ad0 | 0x59ea20 |
| uDOFFilter | 0xcc0a2c | 0x1e1ebc |
| uBokehFilter | 0xcc7c8c | 0x2d3294 |
| uRadialBlurFilter | 0xce80ec | 0x5a65b8 |
| uMultiBlurFilter | 0xce3b78 | 0x2d851c |
| uHazeFilter | 0xcc2730 | 0x257ad0 |
| uColorFilter / uFadeFilter | 0xcc7cdc / 0xcc2634 | 0x2d4688 (shared) |
| uCrossFadeFilter | 0xce3a2c | 0x54d850 |
| uImagePlaneFilter | 0xce7a80 | 0x59d130 |
| uImagePlaneFilterRange | 0xcf3da4 | 0x59d12c (a nop that falls into 0x59d130) |
| uOutlineFilter | 0xccde50 | 0x46ad7c |
| uTVNoiseFilter | 0xcdb41c | 0x4a0f80 |

Differences from MH4U: no uMhRadialBlurFilter / uMhHazeFilter classes; uColorCorrectFilter derives from
MtObject here and its slot 12 is empty, so it is not a normal filter (not hooked).

## Found, not wired (needs run-time checks; MH4U's host code uses MH4U offsets)
- `nDraw::DisplayBuffer::init` = **0x87a5ac**: called 3 times from the function at 0xa16f04 (the
  initGraphics loop) with r1=240, r2=400 (two top eyes) and r1=240, r2=320 (bottom). Same ABI as MH4U.
- `sCamera::Viewport::updateRegion` = **0x984af4**: reads mMode (+0x15) and mDisplay (+0x16), the scene
  target at sLtRender+0x6ba0 (MH4U: +0x6c28), jump table on mode 0xa, immediates 400 (0x984b5c),
  80 (0x984b68), 240 (0x984b6c). Called from setupDraw (0x9858e8), which holds the two rectangle
  immediates {0,80,240,400} (0x985978) and {0,0,240,400} (0x985c58; MH4U's `top_rows` tunable at 0xaf0c84).
  Region fields are at +0x28/+0x2c/+0x30/+0x34 (x0,y0,x1,y1), 4 bytes later than MH4U.
- Camera classes: uCamera (0x90), uFreeCamera (0xc0), uMotionCamera (0x130), uQuestCamera, uVillageCamera,
  uFestaCamera, uSubSceneCamera, uMotionEffectCamera. uCamera keeps far/near/aspect/fov at +0x30/+0x34/
  +0x38/+0x3c (same as MH4U).

## Frame-rate cap (verified by reading the code)
`sMain` (size 0x2e8; MH4U 0x288) constructor 0x88e8e8 sets +0x2c = 60.0 (rate) and +0x30 = 3000.0 (cap,
the same "no cap" default the MH3U cheats show), and stores `this` in the singleton at 0xd4bd4c. The
frame limiter is sMain's virtual method in slot 7, **0x88e120** (`mov r6,r0` first): at 0x88e584 it loads
`vldr s3,[r6,#0x30]` (the instruction the MH Generations 60 fps patch swaps), derives the frame length
from it, stores the delta at +0x5c (0x88e58c), and sleeps the rest (microseconds, svc SleepThread). The
host's UpdateFrameTiming (write the cap into r0+0x30 on entry) therefore works unchanged:
put `fps = 60` in mh4u.ini in the user folder.

## Not found
- `getProjMatrix` of the cameras (MtMatrix::perspective caller) and `nDraw::Scene::Scene`: the cheap
  fingerprints (floats at +0x30, 240/400 immediates, field-store patterns) match too many places.
  Needs a run with the trace options on, or a disassembler with cross-references.
- MH4U's touch-panel hooks (PanelManager, sMhPad, QuestFlow, QuestHud): those classes do not exist in
  MHXX (its UI is cUIO*/uUIHud); that is new research, not a port.
