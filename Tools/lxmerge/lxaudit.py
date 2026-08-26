"""Generic before/after audit for a restructured .lxp.

    python3 lxaudit.py before.lxp after.lxp

The load-bearing checks are the two SEMANTIC INVARIANCE ones: they compare the
*component each reference resolves to*, before vs after. A path that is well-formed
but points at the wrong pattern passes every structural check and fails these.
"""
import collections
import sys

import lxio
import lxpath


def owned_ids(o, acc):
    """Ids a subtree owns. source/target hold refs to other components, not new ids."""
    if isinstance(o, dict):
        if isinstance(o.get('id'), int):
            acc.append(o['id'])
        for k, v in o.items():
            if k not in ('source', 'target'):
                owned_ids(v, acc)
    elif isinstance(o, list):
        for v in o:
            owned_ids(v, acc)
    return acc


def resolve(path, rev):
    """path -> (componentId, remaining param path) via longest known component prefix."""
    for n in range(len(path), 0, -1):
        if path[:n] in rev and (n == len(path) or path[n] == '/'):
            return rev[path[:n]], path[n:]
    return None, path


def modulations(doc):
    m = doc['engine']['children']['modulation']
    return {x['id']: x for coll in ('modulations', 'triggers') for x in m[coll]}


def snapshot_views(doc):
    out = []
    for s in doc['engine']['children']['snapshots']['snapshots']:
        for v in s['views']:
            key = ('channelPath' if 'channelPath' in v else
                   'parameterPath' if 'parameterPath' in v else None)
            out.append((s['parameters']['label'], key, v.get(key) if key else None))
    return out


def audit(before_path, after_path):
    before, after = lxio.load(before_path), lxio.load(after_path)
    PB, PA = lxpath.build_paths(before), lxpath.build_paths(after)
    RB = {p: i for i, p in PB.items()}
    RA = {p: i for i, p in PA.items()}
    fails = []

    def check(name, ok, detail=''):
        print(f'  [{"OK " if ok else "FAIL"}] {name}{"  " + detail if detail else ""}')
        if not ok:
            fails.append(name)

    # -- file sanity -------------------------------------------------------
    raw = open(after_path, encoding='utf-8').read()
    check('gson round-trip byte-identical', lxio.dumps(after) == raw)

    ids = owned_ids(after, [])
    dupes = [i for i, n in collections.Counter(ids).items() if n > 1]
    check('all component ids unique', not dupes, f'{len(ids)} ids, dupes={dupes[:8]}')

    removed = sorted(set(PB) - set(PA))
    added = sorted(set(PA) - set(PB))
    print(f'         {len(removed)} components removed, {len(added)} added')

    # -- path <-> identity consistency -------------------------------------
    bad, unres = [], []
    for x in modulations(after).values():
        for s in ('source', 'target'):
            side = x[s]
            if not side.get('path'):
                continue
            cid = side.get('componentId', side.get('id'))
            base = PA.get(cid)
            if base is None:
                unres.append(cid)
                continue
            pp = side.get('parameterPath')
            if base + ('/' + pp if pp else '') != side['path']:
                bad.append((x['id'], s, side['path'], base))
    check('modulation paths match componentId', not bad, f'{len(bad)} mismatched')
    for b in bad[:8]:
        print('         ', b)
    if unres:
        print(f'         {len(unres)} refs outside the mixer tree (ids {sorted(set(unres))}) '
              f'- expected for /palette, /output')

    # -- SEMANTIC INVARIANCE: modulations ----------------------------------
    mb, ma = modulations(before), modulations(after)
    drift = []
    for mid, x in ma.items():
        if mid not in mb:
            continue
        for s in ('source', 'target'):
            pa, pb = x[s].get('path'), mb[mid][s].get('path')
            if not pa or not pb:
                continue
            if resolve(pa, RA) != resolve(pb, RB):
                drift.append((mid, s, pb, pa))
    check('every surviving modulation resolves to the SAME component',
          not drift, f'{len(ma)} checked, {len(drift)} drifted')
    for d in drift[:8]:
        print('         ', d)

    # -- SEMANTIC INVARIANCE: snapshots ------------------------------------
    vb, va = snapshot_views(before), snapshot_views(after)
    sdrift = []
    if len(vb) == len(va):
        for (lb, kb, pb), (la, ka, pa) in zip(vb, va):
            if kb is None or ka is None or lb != la:
                continue
            if pb.startswith('/lx/mixer/channel/'):
                if resolve(pa[3:], RA) != resolve(pb[3:], RB):
                    sdrift.append((lb, pb, pa))
            elif pb != pa:
                sdrift.append((lb, pb, pa))
    check('snapshot view count unchanged', len(vb) == len(va), f'{len(vb)} -> {len(va)}')
    check('every snapshot view resolves to the SAME component',
          not sdrift, f'{len(va)} checked, {len(sdrift)} drifted')
    for d in sdrift[:8]:
        print('         ', d)

    # /lx/mixer/crossfader and /lx/mixer/master/* are engine params, not channels
    dang = [p for _, k, p in va
            if p and p.startswith('/lx/mixer/channel/') and resolve(p[3:], RA)[0] is None]
    check('no dangling snapshot channel paths', not dang, f'{len(dang)} dangling')

    # -- structure ---------------------------------------------------------
    chans = after['engine']['children']['mixer']['channels']
    cur, okg = None, True
    for c in chans:
        if c['class'].endswith('LXGroup'):
            cur = c['id']
        elif c.get('group') is not None:
            okg &= c['group'] == cur
        else:
            cur = None
    check('group members directly follow their group', okg)

    mx = after['engine']['children']['mixer']['parameters']
    check('focusedChannel in range',
          all(0 <= mx[k] < len(chans) for k in ('focusedChannel', 'focusedChannelAux')))

    print()
    m = after['engine']['children']['modulation']
    print(f'  channels={len(chans)} modulators={len(m["modulators"])} '
          f'modulations={len(m["modulations"])} triggers={len(m["triggers"])} '
          f'snapshot views={len(va)}')
    print('\n' + ('ALL CHECKS PASSED' if not fails else f'FAILURES: {fails}'))
    return 0 if not fails else 1


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(audit(sys.argv[1], sys.argv[2]))
