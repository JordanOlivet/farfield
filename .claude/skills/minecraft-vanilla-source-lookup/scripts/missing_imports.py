"""List Minecraft imports of Java sources that do not resolve in a target version.

Usage: python -I missing_imports.py <java source dir> <target jar or source dir>
"""
import os
import re
import sys
import zipfile

IMPORT = re.compile(r'^import\s+((?:net\.minecraft|com\.mojang)[\w.]*);', re.M)


def classes_in(target):
    found = set()
    if os.path.isdir(target):
        for root, _, files in os.walk(target):
            for f in files:
                if f.endswith('.java') or f.endswith('.class'):
                    rel = os.path.relpath(os.path.join(root, f), target)
                    found.add(rel.replace(os.sep, '/').rsplit('.', 1)[0])
    else:
        with zipfile.ZipFile(target) as z:
            for name in z.namelist():
                if name.endswith('.java') or name.endswith('.class'):
                    found.add(name.rsplit('.', 1)[0])
    return found


def resolves(dotted, found):
    # Nested classes and static members: accept when a prefix is a class.
    parts = dotted.split('.')
    for n in range(len(parts), 1, -1):
        if '/'.join(parts[:n]) in found:
            return True
    return False


def main():
    java_dir, target = sys.argv[1], sys.argv[2]
    found = classes_in(target)
    missing = {}
    for root, _, files in os.walk(java_dir):
        for f in files:
            if not f.endswith('.java'):
                continue
            with open(os.path.join(root, f), encoding='utf-8', errors='replace') as fh:
                for m in IMPORT.finditer(fh.read()):
                    if not resolves(m.group(1), found):
                        missing.setdefault(m.group(1), []).append(f)
    for dotted in sorted(missing):
        print(f'{dotted}  <- {", ".join(sorted(set(missing[dotted])))}')
    print(f'{len(missing)} missing imports')


if __name__ == '__main__':
    main()
