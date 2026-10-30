"""Set every skill's drop chance from the rate tables, so a card drops once per TARGET_MIN[rarity] minutes of doing
the skill's own action.

    python tools/droprates/calibrate.py          # write chances.json, the skill JSON, then re-run the 3 generators
    python tools/droprates/calibrate.py --dry    # print the table only

To re-tune: edit TARGET_MIN or a rate table in droprates.py (or FEATS / FEAT_CHANCE below) and re-run.

chance = 1 / (events_or_units_per_minute * target_minutes), per unit for per-unit triggers. Skills whose action is
too rare to reach the target (sleeping, structures, one-off milestones...) are capped at 1.0 and listed as CAPPED.
Boss kills and other big feats get FEAT_CHANCE per feat instead.

The melee, survival and movement generators read chances.json when they write their JSON, so their output keeps these
chances; every other skill JSON is patched in place (only the trigger's "chance" number changes).
"""
import glob
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.dont_write_bytecode = True  # no __pycache__ next to the tables
import droprates as d  # noqa: E402

ROOT = d.ROOT
SKILLS = os.path.join(ROOT, "src", "main", "resources", "data", "habits", "habits_skills")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "chances.json")
GENERATORS = ["docs/mechanics/melee_gen.py", "docs/mechanics/survival_gen.py", "tools/movgen/movgen.py"]

# boss kills and big once-in-a-while feats: about 1 in 3 per feat (user decision 2026-10-08)
FEAT_CHANCE = 0.33
# skill -> its trigger events (or units) in one feat; the chance per event is set so one feat wins about FEAT_CHANCE
FEATS = {
    # boss kills
    "wither_bane": 1, "soul_harvest": 1, "abyssal_eye": 1, "sonic_blade": 1,
    "dragonslayer": 230,  # 200 units for the kill plus about 30 for projectile hits in the fight
    # boss fights, per hit: one fight is about this many
    "echo_shot": 30, "sound_proof": 3, "skull_juggler": 10, "breath_bottler": 5,
    # big once-in-a-while feats
    "ancient_echo": 1, "raid_boss": 1, "totem_saint": 1, "notchs_blessing": 1,
}

# stat_increment amounts are raw stat units; the rate tables are in blocks (cm stats) and seconds (tick stats)
def stat_scale(stat):
    if stat.endswith("_one_cm"):
        return 100
    if stat.endswith("_time") or stat.startswith("minecraft:time_since") or stat == "minecraft:play_time":
        return 20
    return 1


def first(m):
    if m is None:
        return ""
    if isinstance(m, list):
        m = m[0] if m else ""
    return m


def plan():
    rows = []
    for path in sorted(glob.glob(os.path.join(SKILLS, "*.json"))):
        skill = os.path.splitext(os.path.basename(path))[0]
        j = json.load(open(path, encoding="utf-8"))
        t = j["trigger"]
        row = {"skill": skill, "rarity": j["rarity"], "trigger": t["type"], "target": first(t.get("match"))}
        rate, src = d.rate_for(row)
        target = d.TARGET_MIN[row["rarity"]]
        old = float(t.get("chance", 1.0))
        if skill in FEATS:
            chance, why = 1 - (1 - FEAT_CHANCE) ** (1 / FEATS[skill]), "feat"
        elif rate is None:
            chance, why = 1.0, "one-off"
        else:
            c = 1 / (rate * target)  # per event, or per unit in the table's unit
            if c >= 1:
                chance, why = 1.0, "CAPPED"
            else:
                k = stat_scale(row["target"]) if row["trigger"] == "stat_increment" else 1
                chance, why = 1 - (1 - c) ** (1 / k), "rate"
        chance = float(f"{chance:.3g}")
        rows.append({**row, "rate": rate, "src": src, "target_min": target, "old": old, "chance": chance, "why": why,
                     "path": path, "min_per_drop_if_capped": (1 / rate if rate else None)})
    return rows


TRIGGER_CHANCE = re.compile(r'("trigger"\s*:\s*\{[^{}]*?"chance"\s*:\s*)(-?[0-9.eE+-]+)')


def patch(path, chance):
    s = open(path, encoding="utf-8", newline="").read()
    n = TRIGGER_CHANCE.subn(lambda m: m.group(1) + json.dumps(chance), s, count=1)
    if n[1] != 1:
        raise SystemExit(f"no trigger chance in {path}")
    if n[0] != s:
        open(path, "w", encoding="utf-8", newline="").write(n[0])


def main():
    rows = plan()
    if "--dry" in sys.argv:
        for r in sorted(rows, key=lambda r: (r["why"], r["rarity"], r["skill"])):
            print(f"{r['why']:8} {r['skill']:24} {r['rarity']:9} {r['trigger']:16} {r['target']:40} rate={r['rate']} "
                  f"old={r['old']:.4g} new={r['chance']:.4g}")
        return
    json.dump({r["skill"]: r["chance"] for r in rows}, open(OUT, "w", encoding="utf-8", newline="\n"), indent=1, sort_keys=True)
    for r in rows:
        patch(r["path"], r["chance"])
    for g in GENERATORS:
        subprocess.run([sys.executable, os.path.join(ROOT, g)], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
    changed = sum(1 for r in rows if r["old"] != r["chance"])
    print(f"{len(rows)} skills, {changed} chances changed, {sum(r['why'] == 'CAPPED' for r in rows)} capped at 1.0, "
          f"{sum(r['why'] == 'feat' for r in rows)} feats at {FEAT_CHANCE}")


if __name__ == "__main__":
    main()
