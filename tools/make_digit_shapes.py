"""Makes app/src/main/assets/digit_shapes.bin: the example digits 0-9 of a few open-licence fonts (SolidDigits.kt reads it).

Only needed again when the fonts change. Needs Pillow. The fonts are not in the repo: put each in <fonts>/<family>/ as
v.ttf (variable), or r.ttf and b.ttf (regular and bold), from https://github.com/google/fonts (folder ofl/);
digitalnumbers/r.ttf is Digital Numbers (https://github.com/mcanthony/digital-numbers-font, dist/), a seven-segment look like csTimer's "digital" fonts.

    python tools/make_digit_shapes.py <fonts> app/src/main/assets/digit_shapes.bin

Their licences are in app/src/main/assets/digit_fonts_license.txt. Keep that list and FAMILIES the same.

Format (gzip): "CCDS", version 1, number of styles, SIZE; then per style: name length, name ("lato Bold"), 10 pictures of SIZE x SIZE bytes.
"""
import gzip, os, sys
from PIL import Image, ImageDraw, ImageFont

FAMILIES = ['arimo', 'barlow', 'courierprime', 'digitalnumbers', 'jetbrainsmono', 'lato', 'montserrat', 'oswald', 'ptserif', 'roboto', 'robotomono', 'sharetechmono']
SIZE = 64


def style(path, weight):
    font = ImageFont.truetype(path, 60)
    if weight:
        values = []
        for axis in font.get_variation_axes():
            name = axis['name'].decode() if isinstance(axis['name'], bytes) else axis['name']
            if 'eight' in name:
                values.append(min(max(weight, axis['minimum']), axis['maximum']))
            else:
                values.append(100 if 'idth' in name else axis['default'])
        font.set_variation_by_axes(values)
    return font


def main(fonts, out):
    styles = []
    for family in FAMILIES:
        folder = os.path.join(fonts, family)
        if os.path.exists(os.path.join(folder, 'v.ttf')):
            found = [('Regular', 'v.ttf', 400), ('Bold', 'v.ttf', 700)]
        else:
            found = [(n, f, None) for n, f in (('Regular', 'r.ttf'), ('Bold', 'b.ttf')) if os.path.exists(os.path.join(folder, f))]
        for name, file, weight in found:
            font = style(os.path.join(folder, file), weight)
            data = b''
            for digit in '0123456789':
                picture = Image.new('L', (SIZE, SIZE), 0)
                ImageDraw.Draw(picture).text((SIZE // 2, SIZE // 2), digit, font=font, fill=255, anchor='mm')
                data += picture.tobytes()
            styles.append((f'{family} {name}'.encode(), data))
    body = b'CCDS' + bytes([1, len(styles), SIZE]) + b''.join(bytes([len(n)]) + n + d for n, d in styles)
    with open(out, 'wb') as f:
        f.write(gzip.compress(body, 9, mtime=0))
    print(len(styles), 'styles,', os.path.getsize(out), 'bytes')


if __name__ == '__main__':
    main(*sys.argv[1:])
