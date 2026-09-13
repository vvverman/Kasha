#!/usr/bin/env python3
"""Воспроизводимый экспорт единого реестра Kasha Icons. Только стандартная библиотека."""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent
NS = 'http://www.w3.org/2000/svg'
ET.register_namespace('', NS)
ALLOWED = {'path': {'d'}, 'line': {'x1', 'y1', 'x2', 'y2'}, 'circle': {'cx', 'cy', 'r'},
           'rect': {'x', 'y', 'width', 'height', 'rx'}}
COMMON = {'fill', 'stroke', 'fill-rule', 'opacity'}


def validate(data: dict) -> dict[str, str]:
    assert data['schemaVersion'] == 1 and data['grid'] == 24
    assert data['strokeWidth'] == 1.8 and data['linecap'] == data['linejoin'] == 'round'
    cats = {c['id'] for c in data['categories']}
    ids, aliases = set(), {}
    for icon in data['icons']:
        assert re.fullmatch(r'[a-z][a-z0-9-]*', icon['id']) and icon['id'] not in ids
        ids.add(icon['id'])
        assert icon['category'] in cats and icon['label'].strip() and icon['usage'].strip()
        assert icon['staticMeaning'] is True and icon['geometry']
        for alias in icon['aliases']:
            assert re.fullmatch(r'[A-Z][A-Z0-9_]*', alias) and alias not in aliases, alias
            aliases[alias] = icon['id']
        for geometry in [icon['geometry'], *icon.get('variants', {}).values()]:
            for shape in geometry:
                tag = shape['type']
                assert tag in ALLOWED and shape['part']
                assert set(shape) <= ALLOWED[tag] | COMMON | {'type', 'part'}
                assert ALLOWED[tag] <= set(shape)
                for key, value in shape.items():
                    if key in ALLOWED[tag] - {'d'}:
                        assert isinstance(value, (int, float)) and 0 <= value <= 24
                if tag == 'path':
                    assert re.fullmatch(r'[MmLlHhVvCcSsQqTtAaZz0-9., +\-]+', shape['d'])
        parts = {s['part'] for s in icon['geometry']}
        for motion in icon['motion']:
            assert motion['part'] in parts and 180 <= motion['durationMs'] <= 320
            assert motion['frames'][0]['offset'] == 0 and motion['frames'][-1]['offset'] == 1
    assert len(data['currentGlyphs']) == len(set(data['currentGlyphs'])) == 22
    assert set(data['currentGlyphs']) <= aliases.keys()
    assert aliases['MAGIC'] == aliases['TEXT_PROCESSING'] == 'text-processing'
    assert 'magic' not in ids
    return aliases


def svg(data: dict, icon: dict, geometry: list | None = None, standalone: bool = True) -> str:
    attrs = {'viewBox': '0 0 24 24', 'width': '24', 'height': '24', 'fill': 'none',
             'stroke': 'currentColor', 'stroke-width': str(data['strokeWidth']),
             'stroke-linecap': 'round', 'stroke-linejoin': 'round'}
    if standalone:
        attrs.update({'role': 'img', 'aria-labelledby': 'title'})
    else:
        attrs.update({'aria-hidden': 'true', 'focusable': 'false'})
    root = ET.Element(f'{{{NS}}}svg', attrs)
    if standalone:
        ET.SubElement(root, f'{{{NS}}}title', {'id': 'title'}).text = icon['label']
    groups = {}
    for shape in icon['geometry'] if geometry is None else geometry:
        part = shape['part']
        if part not in groups:
            groups[part] = ET.SubElement(root, f'{{{NS}}}g', {'data-part': part})
        attrs = {k: str(v) for k, v in shape.items() if k not in {'type', 'part'}}
        ET.SubElement(groups[part], f'{{{NS}}}{shape["type"]}', attrs)
    return ET.tostring(root, encoding='unicode')


def logo(path: Path) -> str:
    root = ET.fromstring(path.read_text())
    original_paths = [x.attrib.get('d') for x in root.iter() if x.tag.endswith('path')]
    for element in root.iter():
        if element.attrib.get('fill') == 'black':
            element.set('fill', 'currentColor')
    root.attrib.pop('width', None)
    root.attrib.pop('height', None)
    root.set('role', 'img')
    root.set('aria-label', 'Фирменный знак Kasha')
    root.set('focusable', 'false')
    result = ET.tostring(root, encoding='unicode')
    assert original_paths == [x.attrib.get('d') for x in ET.fromstring(result).iter() if x.tag.endswith('path')]
    return result


def contact_sheet(data: dict, light: bool) -> str:
    ink, muted, canvas, stroke = ('#211e1b', '#6c645d', '#f7f5ee', '#c9c1b6') if light else ('#f5f0e8', '#c3b9ae', '#191715', '#453c34')
    width, height = 1440, 255 + ((len(data['icons']) + 7) // 8) * 175
    root = ET.Element(f'{{{NS}}}svg', {'viewBox': f'0 0 {width} {height}', 'width': str(width), 'height': str(height), 'color': ink})
    ET.SubElement(root, f'{{{NS}}}rect', {'width': str(width), 'height': str(height), 'fill': canvas})
    def text(x: float, y: float, value: str, size: int = 15, color: str = ink, weight: str = '400'):
        ET.SubElement(root, f'{{{NS}}}text', {'x': str(x), 'y': str(y), 'font-family': 'Geologica,DejaVu Sans,sans-serif', 'font-size': str(size), 'fill': color, 'font-weight': weight}).text = value
    brand = ET.fromstring(logo((ROOT / data['logoSource']).resolve()))
    brand.set('x', '40'); brand.set('y', '34'); brand.set('width', '35'); brand.set('height', '43'); root.append(brand)
    text(94, 57, 'Каталог геометрии Kasha Icons', 27, weight='500')
    text(40, 116, f'{len(data["icons"])} glyphs · сетка 24 × 24 · линия 1,8 · три размера: 20 / 24 / 32', 18, muted)
    text(40, 145, 'Статический контактный лист из реестра; не снимок браузера и не запись аудио.', 13, muted)
    for index, icon in enumerate(data['icons']):
        col, row = index % 8, index // 8
        x, y = 24 + col * 174, 183 + row * 175
        ET.SubElement(root, f'{{{NS}}}path', {'d': f'M{x} {y+154}H{x+156}', 'stroke': stroke, 'fill': 'none'})
        for offset, size in zip((17, 63, 112), (20, 24, 32)):
            element = ET.fromstring(svg(data, icon, standalone=False))
            element.set('x', str(x+offset)); element.set('y', str(y+35-size/2))
            element.set('width', str(size)); element.set('height', str(size)); root.append(element)
            text(x+offset, y+77, str(size), 11, muted)
        words, line = icon['label'].split(), ''
        lines = []
        for word in words:
            if len(line + ' ' + word) > 18 and line:
                lines.append(line); line = word
            else:
                line = (line + ' ' + word).strip()
        lines.append(line)
        for i, value in enumerate(lines):
            text(x+8, y+106+i*18, value, 13)
        text(x+8, y+144, icon['id'], 10, muted)
    text(40, height-27, 'Геометрия следующей редакции существующего Kasha Icons. Официальный знак сохранён.', 13, muted)
    return ET.tostring(root, encoding='unicode') + '\n'


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Проверить совпадение tracked-экспортов с реестром, ничего не записывать')
    args = parser.parse_args()
    source = (ROOT / 'registry.json').read_bytes()
    data = json.loads(source)
    aliases = validate(data)
    outputs = {}
    payload = json.loads(source)
    for icon in payload['icons']:
        outputs[f'svg/{icon["id"]}.svg'] = svg(data, icon) + '\n'
        icon['inlineSvg'] = svg(data, icon, standalone=False)
        icon['variantSvg'] = {}
        for name, geometry in icon.get('variants', {}).items():
            outputs[f'svg/{icon["id"]}-{name}.svg'] = svg(data, icon, geometry) + '\n'
            icon['variantSvg'][name] = svg(data, icon, geometry, standalone=False)
    outputs['contact-dark.svg'] = contact_sheet(data, False)
    outputs['contact-light.svg'] = contact_sheet(data, True)
    payload['aliasMap'] = aliases
    template = (ROOT / 'atlas.template.html').read_text()
    embedded = json.dumps(payload, ensure_ascii=False, separators=(',', ':')).replace('<', '\\u003c')
    original_logo = (ROOT / data['logoSource']).resolve()
    outputs['atlas.html'] = (template.replace('@@REGISTRY@@', embedded)
                            .replace('@@LOGO@@', logo(original_logo))
                            .replace('@@COUNT@@', str(len(data['icons'])))
                            .replace('@@HASH@@', hashlib.sha256(source).hexdigest()[:12]))
    outputs['manifest.json'] = json.dumps({
        'schemaVersion': 1, 'registrySha256': hashlib.sha256(source).hexdigest(),
        'iconCount': len(data['icons']), 'legacyGlyphCount': len(data['currentGlyphs']),
        'svgCount': len([x for x in outputs if x.startswith('svg/')]),
        'aliases': aliases, 'generatedFiles': sorted(outputs),
        'logoSourceSha256': hashlib.sha256(original_logo.read_bytes()).hexdigest(),
        'generator': 'python3 export.py; стандартная библиотека; без сети',
    }, ensure_ascii=False, indent=2) + '\n'
    mismatched = []
    for target, content in outputs.items():
        path = ROOT / target
        if target.endswith('.svg'):
            ET.fromstring(content)
        if args.check:
            if not path.exists() or path.read_text() != content:
                mismatched.append(target)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
    expected_svg = {Path(k).name for k in outputs if k.startswith('svg/')}
    stale_svg = {p.name for p in (ROOT / 'svg').glob('*.svg')} - expected_svg
    if stale_svg:
        raise SystemExit('Лишние SVG-экспорты: ' + ', '.join(sorted(stale_svg)))
    if mismatched:
        raise SystemExit('Экспорт устарел: ' + ', '.join(mismatched))
    print(f'{len(data["icons"])} glyphs; {len(expected_svg)} SVG; 22 legacy aliases; XML/JSON: OK; ' + ('экспорт совпадает' if args.check else 'экспорт готов'))


if __name__ == '__main__':
    main()
