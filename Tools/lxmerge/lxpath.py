"""Build the LXPath index for a loaded .lxp: componentId -> canonical 1-based path."""


def build_paths(doc):
    """Return {componentId: path}. Mirrors LXPath.getCanonicalPath conventions."""
    out = {}

    def dev(node, base):
        """A pattern / effect / channel: recurse into its sub-components."""
        out[node['id']] = base
        for j, e in enumerate(node.get('effects', []) or []):
            dev(e, f'{base}/effect/{j + 1}')
        for j, p in enumerate(node.get('planes', []) or []):
            out[p['id']] = f'{base}/layer/{j + 1}'
        ch = node.get('children') or {}
        img = ch.get('image')
        if isinstance(img, dict) and 'id' in img:
            out[img['id']] = f'{base}/image'
        mod = ch.get('modulation')
        if isinstance(mod, dict):
            modulation(mod, base + '/modulation')

    def modulation(mod, base):
        if 'id' in mod:
            out[mod['id']] = base
        for i, m in enumerate(mod.get('modulators', []) or []):
            dev(m, f'{base}/modulator/{i + 1}')
        for i, m in enumerate(mod.get('modulations', []) or []):
            out[m['id']] = f'{base}/modulation/{i + 1}'
        for i, m in enumerate(mod.get('triggers', []) or []):
            out[m['id']] = f'{base}/trigger/{i + 1}'

    eng = doc['engine']['children']
    for i, c in enumerate(eng['mixer']['channels']):
        base = f'/mixer/channel/{i + 1}'
        dev(c, base)
        for j, p in enumerate(c.get('patterns', []) or []):
            dev(p, f'{base}/pattern/{j + 1}')
    modulation(eng['modulation'], '/modulation')
    return out
