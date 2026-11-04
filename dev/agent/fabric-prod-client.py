"""Starts the user's Fabric pack in production, as CurseForge launches it,
with the shippable Farfield jar, in fabric/runs/prod (ADR-097).

    python dev/agent/fabric-prod-client.py <shader pack zip | none> [log file]

The dev Fabric pack run (fabric runPackClient) cannot start a pack with
Iris 1.8.14: its mixins fail after Fabric Loader's dev remapping. This runs
the real loader on the vanilla jar instead: the version files and libraries
of the CurseForge install beside <farfield.fabricPack.dir>, the pack's mods
with Farfield's own jar replaced by fabric/build/libs' newest one, the
configs, shader packs and options of fabric/runs/pack (fill it once with
`gradlew :fabric:syncPackInstance`), and a fresh copy of the test world.
The pack instance is only read. Java 21 comes from FARFIELD_JAVA21 or the
first Adoptium JDK 21 found. Pair it with run-commands.sh on fabric/runs/prod.
"""
import glob
import json
import os
import shutil
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
RUN = os.path.join(ROOT, 'fabric', 'runs', 'prod')
PREPARED = os.path.join(ROOT, 'fabric', 'runs', 'pack')


def local_property(key):
    with open(os.path.join(ROOT, 'local.properties'), encoding='utf-8') as f:
        for line in f:
            if line.strip().startswith(key + '='):
                return line.split('=', 1)[1].strip()
    sys.exit(f'{key} missing from local.properties')


PACK = local_property('farfield.fabricPack.dir')
# <curseforge>/minecraft/Instances/<pack> -> <curseforge>/minecraft/Install
INSTALL = os.path.join(os.path.dirname(os.path.dirname(PACK)), 'Install')
JAVA = os.environ.get('FARFIELD_JAVA21') or next(iter(sorted(
    glob.glob(r'C:/Program Files/Eclipse Adoptium/jdk-21*/bin/java.exe'))), None)
if JAVA is None:
    sys.exit('no Java 21: set FARFIELD_JAVA21 to its java.exe')


def loader_version():
    with open(os.path.join(PACK, 'minecraftinstance.json'), encoding='utf-8') as f:
        name = json.load(f)['baseModLoader']['name']  # fabric-0.19.5-1.21.1
    loader, game = name.split('-')[1], name.split('-')[2]
    return f'fabric-loader-{loader}-{game}', game


def allowed(lib):
    ok = not lib.get('rules')
    for r in lib.get('rules', []):
        if r.get('os') is None or r['os'].get('name') == 'windows':
            ok = r['action'] == 'allow'
    return ok


def lib_path(lib):
    art = lib.get('downloads', {}).get('artifact')
    if art and art.get('path'):
        return art['path']
    group, name, version = lib['name'].split(':')[:3]
    return f"{group.replace('.', '/')}/{name}/{version}/{name}-{version}.jar"


def prepare(shader):
    mods = os.path.join(RUN, 'mods')
    os.makedirs(mods, exist_ok=True)
    for f in os.listdir(mods):
        os.remove(os.path.join(mods, f))
    for f in os.listdir(os.path.join(PACK, 'mods')):
        if f.endswith('.jar') and not f.startswith(('farfield-', 'CrashAssistant-')):
            shutil.copy2(os.path.join(PACK, 'mods', f), mods)
    libs = os.path.join(ROOT, 'fabric', 'build', 'libs')
    jars = [f for f in os.listdir(libs) if f.startswith('farfield-fabric-') and f.endswith('.jar')
            and 'sources' not in f]
    jar = max(jars, key=lambda f: os.path.getmtime(os.path.join(libs, f)))
    shutil.copy2(os.path.join(libs, jar), mods)
    for folder in ('config', 'shaderpacks', 'resourcepacks'):
        if os.path.isdir(os.path.join(PREPARED, folder)):
            shutil.copytree(os.path.join(PREPARED, folder), os.path.join(RUN, folder), dirs_exist_ok=True)
    if not os.path.exists(os.path.join(RUN, 'options.txt')):
        shutil.copy2(os.path.join(PREPARED, 'options.txt'), os.path.join(RUN, 'options.txt'))
    props = os.path.join(RUN, 'config', 'iris.properties')
    if os.path.exists(props):
        lines = open(props, encoding='utf-8').read().splitlines()
        on = 'false' if shader == 'none' else 'true'
        lines = [('shaderPack=' + shader) if l.startswith('shaderPack=') and shader != 'none'
                 else ('enableShaders=' + on) if l.startswith('enableShaders=') else l for l in lines]
        open(props, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    world = os.path.join(RUN, 'saves', 'farfield-test')
    shutil.rmtree(world, ignore_errors=True)
    shutil.copytree(os.path.join(ROOT, 'dev', 'client', 'saves', 'farfield-test'), world)
    return jar


def command(version, game):
    fabric = json.load(open(os.path.join(INSTALL, 'versions', version, version + '.json')))
    vanilla = json.load(open(os.path.join(INSTALL, 'versions', game, game + '.json')))
    cp = []
    for lib in fabric['libraries'] + vanilla['libraries']:
        p = os.path.join(INSTALL, 'libraries', lib_path(lib))
        if allowed(lib) and os.path.exists(p) and p not in cp:
            cp.append(p)
    cp.append(os.path.join(INSTALL, 'versions', game, game + '.jar'))
    natives = os.path.join(RUN, 'natives')
    os.makedirs(natives, exist_ok=True)
    return [JAVA, '-Xmx8G',
            f'-Djava.library.path={natives}', f'-Djna.tmpdir={natives}',
            f'-Dorg.lwjgl.system.SharedLibraryExtractPath={natives}', f'-Dio.netty.native.workdir={natives}',
            '-DFabricMcEmu= net.minecraft.client.main.Main ',
            '-Dfarfield.agent=true', '-Dfarfield.agent.window=minimized', '-Dfarfield.perfLogs=true',
            '-cp', os.pathsep.join(cp), fabric['mainClass'],
            '--username', 'Dev', '--version', version, '--gameDir', RUN,
            '--assetsDir', os.path.join(INSTALL, 'assets'), '--assetIndex', vanilla['assetIndex']['id'],
            '--uuid', '380df991f603344ca090369bad2a924a', '--accessToken', '0', '--userType', 'legacy',
            '--versionType', 'release', '--quickPlaySingleplayer', 'farfield-test']


def main():
    shader = sys.argv[1]
    log = sys.argv[2] if len(sys.argv) > 2 else os.path.join(RUN, 'launch.log')
    version, game = loader_version()
    jar = prepare(shader)
    with open(log, 'w', encoding='utf-8') as out:
        out.write(f'{version}, {jar}, shader pack {shader}\n')
        out.flush()
        code = subprocess.call(command(version, game), cwd=RUN, stdout=out, stderr=subprocess.STDOUT)
        out.write(f'\nexit {code}\n')
    sys.exit(code)


if __name__ == '__main__':
    main()
