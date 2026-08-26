"""Byte-exact Gson round-trip for Chromatik .lxp files."""
import json, math, re
from collections import OrderedDict

_ESC = {
    '"': '\\"', '\\': '\\\\', '\n': '\\n', '\r': '\\r', '\t': '\\t',
    '\b': '\\b', '\f': '\\f',
    '<': '\\u003c', '>': '\\u003e', '&': '\\u0026', '=': '\\u003d', "'": '\\u0027',
}


def _jstr(s):
    out = ['"']
    for ch in s:
        e = _ESC.get(ch)
        if e is not None:
            out.append(e)
        elif ch < ' ' or ch == ' ' or ch == ' ':
            out.append('\\u%04x' % ord(ch))
        else:
            out.append(ch)
    out.append('"')
    return ''.join(out)


def _jdouble(d):
    """Java Double.toString."""
    if d != d:
        return 'NaN'
    if d == math.inf:
        return 'Infinity'
    if d == -math.inf:
        return '-Infinity'
    if d == 0.0:
        return '-0.0' if math.copysign(1, d) < 0 else '0.0'
    neg = d < 0
    a = abs(d)
    r = repr(a)                       # shortest round-trip
    if 'e' in r or 'E' in r:
        mant, exp = r.split('e')
        exp = int(exp)
    else:
        # normalise to mantissa/exponent
        if '.' in r:
            ip, fp = r.split('.')
        else:
            ip, fp = r, ''
        digits = (ip + fp).lstrip('0')
        lead = len(ip + fp) - len((ip + fp).lstrip('0'))
        exp = len(ip) - lead - 1
        digits = digits.rstrip('0') or '0'
        mant = digits[0] + '.' + (digits[1:] or '0')
    if '.' not in mant:
        mant += '.0'
    mdig = mant.replace('.', '').rstrip('0') or '0'

    if 1e-3 <= a < 1e7:
        # plain decimal notation
        if exp >= 0:
            ip = mdig[:exp + 1].ljust(exp + 1, '0')
            fp = mdig[exp + 1:] or '0'
        else:
            ip = '0'
            fp = '0' * (-exp - 1) + mdig
        s = ip + '.' + fp
    else:
        m = mdig[0] + '.' + (mdig[1:] or '0')
        s = m + 'E' + str(exp)
    return ('-' + s) if neg else s


def _jnum(n):
    if isinstance(n, bool):
        return 'true' if n else 'false'
    if isinstance(n, int):
        return str(n)
    return _jdouble(n)


def dumps(obj, indent='  '):
    buf = []

    def w(o, lvl):
        if o is None:
            buf.append('null')
        elif isinstance(o, bool):
            buf.append('true' if o else 'false')
        elif isinstance(o, (int, float)):
            buf.append(_jnum(o))
        elif isinstance(o, str):
            buf.append(_jstr(o))
        elif isinstance(o, dict):
            if not o:
                buf.append('{}')
                return
            buf.append('{\n')
            pad = indent * (lvl + 1)
            items = list(o.items())
            for i, (k, v) in enumerate(items):
                buf.append(pad)
                buf.append(_jstr(k))
                buf.append(': ')
                w(v, lvl + 1)
                buf.append(',\n' if i < len(items) - 1 else '\n')
            buf.append(indent * lvl)
            buf.append('}')
        elif isinstance(o, list):
            if not o:
                buf.append('[]')
                return
            buf.append('[\n')
            pad = indent * (lvl + 1)
            for i, v in enumerate(o):
                buf.append(pad)
                w(v, lvl + 1)
                buf.append(',\n' if i < len(o) - 1 else '\n')
            buf.append(indent * lvl)
            buf.append(']')
        else:
            raise TypeError(type(o))

    w(obj, 0)
    return ''.join(buf)


def load(path):
    with open(path, 'r', encoding='utf-8') as f:
        return json.load(f, object_pairs_hook=OrderedDict)


def save(obj, path):
    with open(path, 'w', encoding='utf-8') as f:
        f.write(dumps(obj))
