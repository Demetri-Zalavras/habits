import json, os, re

D = 'src/main/java/com/habits/perk/movement/'
code = ''.join(open(D + f).read() for f in os.listdir(D))
fired = set(re.findall(r'fire(?:Units)?\(p, "([a-z_0-9]+)"', code))
fired |= {f'far_{n}' for n in (1000, 5000, 10000, 30000)} | {f'biomes_{n}' for n in (20, 35, 50, 64)}
used = set()
S = 'src/main/resources/data/habits/habits_skills/'
for f in os.listdir(S):
    t = json.load(open(S + f))['trigger']
    if t['type'] in ('custom', 'custom_units'):
        for x in (t['match'] if isinstance(t['match'], list) else [t['match']]):
            used.add(x.split(':')[1])
print('used not fired', used - fired)
print('fired not used', fired - used)
