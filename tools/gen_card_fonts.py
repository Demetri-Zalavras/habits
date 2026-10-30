# Writes the card font definitions: one TTF provider per (weight, pixel size) at oversample 1, so CardRenderer can
# draw text 1:1 into its offscreen card face (crisp at any GUI scale). Run: python tools/gen_card_fonts.py
# Font: Barlow Condensed, SIL OFL 1.1 (license next to the .ttf files).
import json, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src/main/resources/assets/habits/font/card")
WEIGHTS = {"xb": "extrabold", "sb": "semibold", "md": "medium"}
SIZES = [6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 27, 30, 34, 38, 43, 48, 54, 60]  # keep in sync with CardText.SIZES

os.makedirs(OUT, exist_ok=True)
for key, w in WEIGHTS.items():
    for s in SIZES:
        with open(os.path.join(OUT, f"{key}_{s}.json"), "w") as f:
            json.dump({"providers": [{"type": "ttf", "file": f"habits:barlow_condensed_{w}.ttf", "size": s,
                                      "oversample": 1.0, "shift": [0, 0]}]}, f)
print("ok")
