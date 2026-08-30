"""Adopt WATERFALL/Background + WATERFALL/Subject from v9001 into the Playa project.

Declared truth: v9001 owns channels 478551 (Background) and 677968 (Subject),
including their inbound wiring from the global modulation engine.
Everything else in Playa is untouched.

Per what-the-merge.md: replace by component id, regenerate every path from
identity, drop references to doomed components, then audit.
"""
import copy
import sys

sys.path.insert(0, '/Users/arielwexler/Chromatik/Tools/lxmerge')
import lxio
import lxpath
import lxaudit

SRC_PLAYA = '/Users/arielwexler/Chromatik/Projects/FullCampLayout2026_Playa.lxp'
SRC_V9001 = '/Users/arielwexler/Chromatik/Projects/FullCampLayout2026_v9001.lxp'
OUT = '/Users/arielwexler/Chromatik/Projects/FullCampLayout2026_Playa_merged.lxp'

ADOPT = [(478551, 'Background'), (677968, 'Subject')]


def channel(doc, cid):
    for i, c in enumerate(doc['engine']['children']['mixer']['channels']):
        if c['id'] == cid:
            return i, c
    raise KeyError(cid)


def owned(node):
    return set(lxaudit.owned_ids(node, []))


def log(*a):
    print(*a)


P = lxio.load(SRC_PLAYA)
V = lxio.load(SRC_V9001)

# --- Phase 1: writer proof -------------------------------------------------
assert lxio.dumps(P) == open(SRC_PLAYA).read(), 'Playa round-trip failed'
assert lxio.dumps(V) == open(SRC_V9001).read(), 'v9001 round-trip failed'
log('round-trip: both inputs byte-identical')

# --- Phase 2: path generator proof ----------------------------------------
def verify_paths(doc, label):
    pm = lxpath.build_paths(doc)
    ok = skipped = 0
    m = doc['engine']['children']['modulation']
    for coll in ('modulations', 'triggers'):
        for x in m[coll]:
            for side in ('source', 'target'):
                s = x[side]
                cid = s.get('componentId', s.get('id'))
                if cid not in pm:            # /palette/* engine params
                    skipped += 1
                    continue
                want = pm[cid] + ('/' + s['parameterPath'] if 'parameterPath' in s else '')
                assert s['path'] == want, (label, s['path'], want)
                ok += 1
    log(f'{label}: path generator reproduces {ok} stored paths ({skipped} non-mixer skipped)')
    return pm

verify_paths(P, 'playa')
verify_paths(V, 'v9001')

# --- Phase 3: capture the before-state ------------------------------------
old_ids = {}
for cid, name in ADOPT:
    _, c = channel(P, cid)
    old_ids[cid] = owned(c)

new_ids = {}
for cid, name in ADOPT:
    _, c = channel(V, cid)
    new_ids[cid] = owned(c)

surviving = set().union(*new_ids.values())
doomed = set().union(*old_ids.values()) - surviving
log(f'components removed by the swap: {len(doomed)}')

# id-collision guard: v9001's brand-new ids must not clash with the rest of Playa
rest = owned(P) - set().union(*old_ids.values())
clash = sorted((surviving - set().union(*old_ids.values())) & rest)
assert not clash, f'id collision: {clash}'
log('id-collision check: clean')

# --- Phase 5/6: swap the channels -----------------------------------------
for cid, name in ADOPT:
    pi, pc = channel(P, cid)
    vi, vc = channel(V, cid)
    assert pi == vi, f'{name} sits at a different index ({pi} vs {vi})'
    assert pc.get('group') == vc.get('group'), f'{name} group differs'
    P['engine']['children']['mixer']['channels'][pi] = copy.deepcopy(vc)
    log(f'adopted {name} (id {cid}) at index {pi}: '
        f'{len(pc.get("patterns") or [])} -> {len(vc.get("patterns") or [])} patterns, '
        f'{len(pc.get("effects") or [])} -> {len(vc.get("effects") or [])} channel effects')

# --- global modulation engine ---------------------------------------------
PM = P['engine']['children']['modulation']
VM = V['engine']['children']['modulation']
p_mod_ids = {x['id'] for coll in ('modulations', 'triggers') for x in PM[coll]}

# (a) drop references to components v9001 removed
for coll in ('modulations', 'triggers'):
    keep, dropped = [], []
    for x in PM[coll]:
        if any(x[s].get('componentId', x[s].get('id')) in doomed for s in ('source', 'target')):
            dropped.append(x)
        else:
            keep.append(x)
    PM[coll] = keep
    for x in dropped:
        log(f'  drop {coll[:-1]} {x["id"]} -> {x["target"]["path"]} (target removed)')
    log(f'{coll}: dropped {len(dropped)}')

# (b) adopt v9001 wiring that targets the new content
added = 0
for coll in ('modulations', 'triggers'):
    for x in VM[coll]:
        if x['id'] in p_mod_ids:
            continue
        if any(x[s].get('componentId', x[s].get('id')) in surviving for s in ('source', 'target')):
            PM[coll].append(copy.deepcopy(x))
            log(f'  add {coll[:-1]} {x["id"]} -> {x["target"]["path"]}')
            added += 1
log(f'adopted {added} new modulations/triggers')

# (c) v9001 wins on retuned modulations that target the adopted channels
v_by_id = {x['id']: x for coll in ('modulations', 'triggers') for x in VM[coll]}
retuned = 0
for coll in ('modulations', 'triggers'):
    for x in PM[coll]:
        v = v_by_id.get(x['id'])
        if not v:
            continue
        if not any(x[s].get('componentId', x[s].get('id')) in surviving for s in ('source', 'target')):
            continue
        for k, nv in v['parameters'].items():
            if x['parameters'].get(k) != nv:
                log(f'  retune {x["id"]} {x["target"]["path"]} {k}: '
                    f'{x["parameters"].get(k)} -> {nv}')
                x['parameters'][k] = nv
                retuned += 1
log(f'retuned {retuned} modulation parameters')

# --- regenerate every path from identity ----------------------------------
pm = lxpath.build_paths(P)
regen = 0
for coll in ('modulations', 'triggers'):
    for x in PM[coll]:
        for side in ('source', 'target'):
            s = x[side]
            cid = s.get('componentId', s.get('id'))
            if cid not in pm:
                continue
            want = pm[cid] + ('/' + s['parameterPath'] if 'parameterPath' in s else '')
            if s['path'] != want:
                log(f'  path {x["id"]}.{side}: {s["path"]} -> {want}')
                s['path'] = want
                regen += 1
log(f'regenerated {regen} paths')

# --- clamp focus ----------------------------------------------------------
mixer = P['engine']['children']['mixer']
nch = len(mixer['channels'])
for k in ('focusedChannel', 'focusedChannelAux'):
    if k in mixer['parameters'] and mixer['parameters'][k] >= nch:
        log(f'  clamp {k}: {mixer["parameters"][k]} -> {nch - 1}')
        mixer['parameters'][k] = nch - 1

# --- write ----------------------------------------------------------------
with open(OUT, 'w') as f:
    f.write(lxio.dumps(P))
log(f'wrote {OUT}')
