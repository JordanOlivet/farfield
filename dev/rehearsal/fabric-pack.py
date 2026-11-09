"""Starts the user's Fabric pack in production with the shippable Farfield
jar and Rehearsal (ADR-097, ADR-122), in fabric/runs/prod, on a fresh copy of
the test world: Rehearsal's scripts/fabric_pack.py with Farfield's settings.

    python -I dev/rehearsal/fabric-pack.py <shader pack zip | none> [log file]

The pack instance is <farfield.fabricPack.dir>, only read; configs, shader
packs and options come from fabric/runs/pack (fill it once with
`gradlew :fabric:syncPackInstance`). Pair it with dev/rehearsal/run-commands.sh
on fabric/runs/prod. Environment switches:

  FARFIELD_WITHOUT=1          the pack without Farfield, the baseline of a memory or cost A/B
  FARFIELD_EXTRA_MODS=<dir>   that folder's jars join the run (Chunky, ...)
  FARFIELD_WORLD=<save copy>  that world instead of the fresh farfield-test
  FARFIELD_JVM_ARGS=<args>    extra JVM arguments, space separated (temporary -D switches)
"""
import glob
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))


def local_property(key):
    with open(os.path.join(ROOT, 'local.properties'), encoding='utf-8') as f:
        for line in f:
            if line.strip().startswith(key + '='):
                return line.split('=', 1)[1].strip()
    sys.exit(f'{key} missing from local.properties')


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    shader = sys.argv[1]
    run = os.path.join(ROOT, 'fabric', 'runs', 'prod')
    args = [sys.executable, '-I', os.path.join(local_property('rehearsal.dir'), 'scripts', 'fabric_pack.py'),
            '--instance', local_property('farfield.fabricPack.dir'),
            '--run-dir', run,
            '--prepared', os.path.join(ROOT, 'fabric', 'runs', 'pack'),
            '--world', os.environ.get('FARFIELD_WORLD') or os.path.join(ROOT, 'dev', 'client', 'saves', 'farfield-test'),
            '--quickplay', 'farfield-test',
            '--shader', shader,
            '--extension', 'io.lakio.farfield.client.FarfieldRehearsal',
            '--jvm-arg=-Dfarfield.perfLogs=true',  # = form: argparse takes '-D...' for an option
            '--log', sys.argv[2] if len(sys.argv) > 2 else os.path.join(run, 'launch.log')]
    if os.environ.get('FARFIELD_WITHOUT'):
        args += ['--without', 'farfield-']
    else:
        libs = os.path.join(ROOT, 'fabric', 'build', 'libs')
        jars = [j for j in glob.glob(os.path.join(libs, 'farfield-fabric-*.jar')) if 'sources' not in j]
        if not jars:
            sys.exit('no Farfield jar in fabric/build/libs: gradlew build first')
        args += ['--mod', max(jars, key=os.path.getmtime)]
    if os.environ.get('FARFIELD_EXTRA_MODS'):
        args += ['--extra-mods', os.environ['FARFIELD_EXTRA_MODS']]
    for arg in os.environ.get('FARFIELD_JVM_ARGS', '').split():
        args.append('--jvm-arg=' + arg)
    sys.exit(subprocess.call(args))


if __name__ == '__main__':
    main()
