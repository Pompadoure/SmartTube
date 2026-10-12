#!/usr/bin/env python3
"""JoTube M3: check that libraries compiled against newer versions only call members that exist in the
versions the root build.gradle forces (cronet-api 105, okhttp 3.12, androidx.core ...).

Usage (CI, after the build filled the Gradle cache):
  api_check.py <gradle files-2.1 dir> <consumer group:artifact:version>... -- <provider group:artifact:version>...

For every class of the consumers that references a provider package, every method/field reference into the
providers' classes is looked up (in the class and its superclasses/interfaces inside the providers).
Prints one line per missing member: "MISSING <consumer class> -> <owner>.<name><descriptor>".
Needs javap (JDK) on PATH. Exit code 0 always (it's a report).
"""
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile


def artifact_jars(cache, coord, tmp):
    group, name, version = coord.split(':')
    files = glob.glob(os.path.join(cache, group, name, version, '*', '*'))
    jars = []
    for f in files:
        if f.endswith('-sources.jar') or f.endswith('.pom') or f.endswith('.module'):
            continue
        if f.endswith('.jar'):
            jars.append(f)
        elif f.endswith('.aar'):
            out = os.path.join(tmp, '%s-%s-%s.jar' % (group, name, version))
            with zipfile.ZipFile(f) as z:
                if 'classes.jar' in z.namelist():
                    with open(out, 'wb') as w:
                        w.write(z.read('classes.jar'))
                    jars.append(out)
    if not jars:
        print('NOTFOUND %s' % coord)
    return jars


def class_names(jar):
    with zipfile.ZipFile(jar) as z:
        return [n[:-6] for n in z.namelist() if n.endswith('.class') and not n.startswith('META-INF')]


def javap(jar, classes, flags):
    out = []
    for i in range(0, len(classes), 200):
        chunk = [c.replace('/', '.') for c in classes[i:i + 200]]
        r = subprocess.run(['javap'] + flags + ['-cp', jar] + chunk, capture_output=True, text=True)
        out.append(r.stdout)
    return '\n'.join(out)


def provider_members(jars):
    """class -> (super/interfaces, set of 'name:descriptor')"""
    members = {}
    for jar in jars:
        classes = class_names(jar)
        text = javap(jar, classes, ['-p', '-s'])
        cur = None
        last_name = None
        for line in text.splitlines():
            m = re.match(r'^(?:[\w ]*?)(?:class|interface|enum) ([\w.$]+)(?:<[^{]*?>)?(?: extends ([\w.$<>, ?]+?))?(?: implements ([\w.$<>, ?]+?))? ?\{', line.strip())
            if m and not line.startswith(' '):
                cur = m.group(1).replace('.', '/')
                supers = []
                for g in (m.group(2), m.group(3)):
                    if g:
                        supers += [re.sub(r'<.*', '', s.strip()).replace('.', '/') for s in g.split(',')]
                members[cur] = (supers, set())
                continue
            s = line.strip()
            if cur and s.startswith('descriptor:'):
                desc = s.split(':', 1)[1].strip()
                if last_name:
                    members[cur][1].add(last_name + ':' + desc)
                continue
            if cur and s.endswith(';') and not s.startswith('descriptor'):
                # method: "... name(args);" , ctor: "... pkg.Cls(args);", field: "... type name;"
                mm = re.search(r'([\w$.]+)\((.*)\)(?: throws [\w.$, ]+)?;$', s)
                if mm:
                    nm = mm.group(1)
                    nm = '"<init>"' if nm.replace('.', '/') == cur else nm.split('.')[-1]
                    last_name = nm
                else:
                    mf = re.search(r'([\w$]+);$', s)
                    last_name = mf.group(1) if mf else None
    return members


def has_member(members, owner, key, seen=None):
    seen = seen or set()
    if owner in seen or owner not in members:
        return owner not in members and owner.startswith('java/')
    seen.add(owner)
    supers, mem = members[owner]
    if key in mem:
        return True
    return any(has_member(members, s, key, seen) for s in supers)


def main():
    cache = sys.argv[1]
    args = sys.argv[2:]
    sep = args.index('--')
    consumers, providers = args[:sep], args[sep + 1:]
    tmp = tempfile.mkdtemp()
    pjars = [j for c in providers for j in artifact_jars(cache, c, tmp)]
    members = provider_members(pjars)
    packages = sorted({c.rsplit('/', 1)[0] for c in members})
    print('providers: %d classes, %d packages; jars: %s' % (len(members), len(packages), ', '.join(os.path.basename(j) for j in pjars)))
    missing = set()
    checked = 0
    per_package = {}
    for coord in consumers:
        for jar in artifact_jars(cache, coord, tmp):
            text = javap(jar, class_names(jar), ['-c', '-p'])
            cls = None
            for line in text.splitlines():
                if not line.startswith(' ') and ('class ' in line or 'interface ' in line) and line.rstrip().endswith('{'):
                    cls = line.strip()
                    continue
                m = re.search(r'// (Method|InterfaceMethod|Field) ([\w/$]+)\.("?[\w$<>]+"?):(\S+)', line)
                if not m:
                    continue
                owner, name, desc = m.group(2), m.group(3), m.group(4)
                if not any(owner.startswith(p + '/') and owner.count('/') == p.count('/') + 1 for p in packages):
                    continue
                checked += 1
                pkg = owner.rsplit('/', 1)[0]
                per_package[pkg] = per_package.get(pkg, 0) + 1
                if owner not in members:
                    missing.add('MISSING CLASS %s (used by %s)' % (owner, coord))
                elif not has_member(members, owner, name + ':' + desc):
                    missing.add('MISSING %s.%s%s (used by %s)' % (owner, name, desc, coord))
    print('checked %d references: %s' % (checked, ', '.join('%s %d' % kv for kv in sorted(per_package.items()))))
    for m in sorted(missing):
        print(m)


if __name__ == '__main__':
    main()
