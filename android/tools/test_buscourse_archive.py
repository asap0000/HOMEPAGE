import sys
import os
import importlib.util
import json
from pathlib import Path
import sqlite3
import tarfile
import tempfile
import threading
import unittest
from unittest import mock
import zipfile
import hashlib
import shutil

spec = importlib.util.spec_from_file_location('buscourse_archive', Path(__file__).with_name('buscourse_archive.py'))
archive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(archive)


def make_db(path, version, started, model='SyntheticDevice', distance=500, frame=True):
    con = sqlite3.connect(path)
    con.execute('CREATE TABLE recording_session (id INTEGER PRIMARY KEY, started_at INTEGER, device_model TEXT, type TEXT, status TEXT, total_distance_m REAL' +
                (', run_uid TEXT, memo TEXT' if version == 24 else '') + ')')
    values = [1, started, model, 'FULL_RUN', 'COMPLETED', distance]
    if version == 24:
        values += ['synthetic-uid', 'synthetic memo']
    con.execute('INSERT INTO recording_session VALUES (' + ','.join('?' for _ in values) + ')', values)
    con.execute('CREATE TABLE gps_point (id INTEGER, session_id INTEGER, seq INTEGER, lat REAL, lon REAL)')
    con.execute('INSERT INTO gps_point VALUES (1,1,0,1.25,2.5)')
    con.execute('CREATE TABLE timelapse_frame (id INTEGER, session_id INTEGER, seq INTEGER, file_rel_path TEXT)')
    if frame:
        con.execute("INSERT INTO timelapse_frame VALUES (1,1,0,'sessions/1/frames/synthetic.jpg')")
    con.execute(f'PRAGMA user_version={version}')
    con.commit(); con.close()


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.root = self.base / 'vault'
        self.root.mkdir()
        self.old = archive.CUTOFF - 1000
        self.new = archive.CUTOFF + 1000

    def folder(self):
        return next(folder for folder, _ in archive.archive_runs(self.root))

    def export(self, device, started):
        base = device / 'archive_out'
        base.mkdir(parents=True, exist_ok=True)
        key = archive.run_key(started)
        folder = base / key
        folder.mkdir()
        run = {'schema': archive.SCHEMA, 'startedAt': started, 'endedAt': started + 1000,
               'deviceModel': 'SyntheticDevice', 'runUid': None, 'type': 'FULL_RUN',
               'status': 'COMPLETED', 'totalDistanceM': 400, 'appVersion': 'test',
               'dbVersion': 24, 'session': {'id': 1}, 'gps': [], 'frames': [],
               'stopVisits': [], 'shocks': [], 'missingFiles': []}
        archive.dump(folder / 'run.json', run)
        (folder / 'frames').mkdir()
        (folder / 'frames' / 'synthetic.bin').write_bytes(b'synthetic bytes')
        archive.write_manifest(folder)
        (folder / 'DONE').touch()
        return folder

    def fake_adb(self, devices, stalled=()):
        script = self.base / ('fake_' + str(len(list(self.base.glob('fake_*.py')))) + '.py')
        script.write_text('''#!/usr/bin/env python3
import pathlib, shutil, sys, time
devices = {devices!r}
stalled = {stalled!r}
counter = pathlib.Path({counter!r})
argv = sys.argv[1:]
if argv == ['devices']:
    print('List of devices attached')
    for serial in devices: print(serial + '\\tdevice')
    sys.exit(0)
serial = argv[1]
args = argv[2:]
base = pathlib.Path(devices[serial])
def local(remote):
    return base / pathlib.Path(remote).relative_to('/sdcard/Android/data/com.istech.buscourse/files')
if args[:2] == ['shell', 'ls']:
    folder = local(args[-1])
    if not folder.is_dir(): sys.exit(1)
    print('\\n'.join(p.name for p in folder.iterdir()))
elif args[:2] == ['shell', 'test']:
    sys.exit(0 if local(args[3]).is_file() else 1)
elif args[:2] == ['shell', 'getprop']:
    print('SyntheticDevice')
elif args[:2] == ['shell', 'pm']:
    if base.name == 'empty': print('package:synthetic')
elif args[:2] == ['shell', 'du']:
    size = sum(p.stat().st_size for p in local(args[-1]).rglob('*') if p.is_file())
    print(str((size + 1023) // 1024) + '\\t' + args[-1])
elif args[:2] == ['shell', 'cat']:
    print(local(args[-1]).read_text())
elif args[0] == 'pull':
    source = local(args[1])
    target = pathlib.Path(args[2]) / source.name
    if source.name in stalled:
        counter.write_text(str(int(counter.read_text()) + 1 if counter.exists() else 1))
        target.mkdir()
        (target / 'partial').write_bytes(b'x')
        time.sleep(3)
    else:
        shutil.copytree(source, target)
elif args[0] == 'push':
    shutil.copy2(args[1], local(args[2]))
elif args[:2] == ['shell', 'rm']:
    shutil.rmtree(local(args[3]))
'''.format(devices={k: str(v) for k, v in devices.items()}, stalled=list(stalled),
           counter=str(self.base / 'pull_count.txt')))
        script.chmod(0o755)
        if os.name == 'nt':
            wrapper = script.with_suffix('.cmd')
            wrapper.write_text(f'@"{sys.executable}" "{script}" %*\n')
            return str(wrapper)
        return str(script)

    def navi_package(self, parent, source='app', run_id='SyntheticDevice_1000', payload=b'{"kind":"header"}\n'):
        folder = parent / (source + '-package')
        folder.mkdir(parents=True, exist_ok=True)
        raw = folder / '1000.jsonl'
        raw.write_bytes(payload)
        archive.dump(folder / 'candidate-meta.json', {'source': source, 'sourceRunId': run_id,
                     'rawFile': raw.name, 'rawBytes': len(payload), 'rawSha256': hashlib.sha256(payload).hexdigest(),
                     'endedEarly': True})
        manifest = ''.join(f'{archive.sha(p)}  {p.name}\n' for p in sorted(folder.iterdir()))
        (folder / 'manifest.sha256').write_text(manifest, encoding='utf-8', newline='\n')
        (folder / 'DONE').touch()
        return folder

    def test_navi_samples_versions_receipts_and_origin_unchanged(self):
        source = self.base / 'source'; source.mkdir()
        # 契約の合成見本（web-lab の r3）がある環境では、その包みをそのまま受け入れられることも確かめる。
        r3 = Path(os.environ.get('BUSCOURSE_NAVI_SAMPLES', '') or '/nonexistent')
        if (r3 / 'sample-app').is_dir() and (r3 / 'sample-browser').is_dir():
            for name in ('sample-app', 'sample-browser'):
                shutil.copytree(r3 / name, source / name)
        else:
            self.navi_package(source, 'app', 'SyntheticDevice_1000').rename(source / 'sample-app')
            self.navi_package(source, 'browser', 'synthetic-browser').rename(source / 'sample-browser')
        before = {p.relative_to(source).as_posix(): archive.sha(p) for p in source.rglob('*') if p.is_file()}
        counts = archive.ingest_folder(self.root, source)
        self.assertEqual(counts['navi_archived'], 2)
        self.assertEqual((counts['navi_app_archived'], counts['navi_browser_archived']), (1, 1))
        for folder in source.iterdir():
            meta = archive.load(folder / 'candidate-meta.json')
            version = self.root / 'ナビの走った跡' / archive.navi_source(meta['source']) / archive.safe_navi(meta['sourceRunId']) / 'v1'
            for original in folder.rglob('*'):
                if original.is_file():
                    self.assertEqual((version / original.relative_to(folder)).read_bytes(), original.read_bytes())
            self.assertTrue((version / '_vault/provenance.json').is_file())
            self.assertTrue((version / '_vault/vault.sha256').is_file())
            receipt = archive.load(archive._navi_receipt(self.root, meta['source'], meta['sourceRunId'], 1))
            self.assertEqual((receipt['schema'], receipt['stage'], receipt['phoneDeletionAllowed']),
                             (archive.NAVI_SCHEMA, 'VAULT_ARCHIVED', False))
        self.assertEqual(before, {p.relative_to(source).as_posix(): archive.sha(p) for p in source.rglob('*') if p.is_file()})
        self.assertEqual(archive.ingest_folder(self.root, source)['navi_duplicate'], 2)
        self.assertEqual(before, {p.relative_to(source).as_posix(): archive.sha(p) for p in source.rglob('*') if p.is_file()})

    def test_navi_changed_same_id_creates_v2_without_touching_v1(self):
        source = self.base / 'source'; source.mkdir()
        one = self.navi_package(source)
        self.assertEqual(archive.ingest_navi_package(self.root, one, 'synthetic'), 'archived')
        base, versions = archive._navi_versions(self.root, 'app', 'SyntheticDevice_1000')
        first = {p.relative_to(versions[0]).as_posix(): archive.sha(p) for p in versions[0].rglob('*') if p.is_file()}
        two_parent = self.base / 'other'; two_parent.mkdir()
        two = self.navi_package(two_parent, payload=b'{"kind":"header"}\n{"kind":"fix"}\n')
        self.assertEqual(archive.ingest_navi_package(self.root, two, 'synthetic'), 'archived')
        self.assertTrue((base / 'v2' / 'DONE').is_file())
        self.assertEqual(first, {p.relative_to(versions[0]).as_posix(): archive.sha(p) for p in versions[0].rglob('*') if p.is_file()})

    def test_navi_invalid_packages_are_held_without_receipts(self):
        for case in ('no_done', 'bad_sha', 'extra', 'traversal', 'bad_source'):
            with self.subTest(case=case):
                parent = self.base / case; parent.mkdir()
                folder = self.navi_package(parent, run_id=case)
                if case == 'no_done': (folder / 'DONE').unlink()
                if case == 'bad_sha': (folder / '1000.jsonl').write_bytes(b'changed')
                if case == 'extra': (folder / 'extra.txt').write_text('extra')
                if case == 'traversal':
                    manifest = folder / 'manifest.sha256'
                    manifest.write_text(manifest.read_text() + '0' * 64 + '  ../outside\n', newline='\n')
                if case == 'bad_source':
                    meta = archive.load(folder / 'candidate-meta.json'); meta['source'] = 'invalid'
                    archive.dump(folder / 'candidate-meta.json', meta)
                    (folder / 'manifest.sha256').write_text(''.join(f'{archive.sha(p)}  {p.name}\n' for p in sorted(folder.iterdir())
                        if p.name not in ('manifest.sha256', 'DONE')), newline='\n')
                before = {p.name: archive.sha(p) for p in folder.iterdir() if p.is_file()}
                self.assertEqual(archive.ingest_navi_package(self.root, folder, 'synthetic'), 'held')
                self.assertEqual(before, {p.name: archive.sha(p) for p in folder.iterdir() if p.is_file()})
                self.assertFalse(archive._navi_receipt(self.root, 'app', case, 1).exists())
        self.assertEqual(len(list((self.root / 'ナビの走った跡/受け入れ保留').glob('*.reason.txt'))), 5)

    def test_navi_only_fake_adb_pull_and_receipt(self):
        device = self.base / 'device'; out = device / 'navirun_out'; out.mkdir(parents=True)
        good = self.navi_package(out, run_id='SyntheticDevice_1000')
        bad_parent = self.base / 'bad'; bad_parent.mkdir()
        bad = self.navi_package(bad_parent, run_id='SyntheticDevice_2000')
        bad.rename(out / 'bad-package')
        (out / 'bad-package' / 'DONE').unlink()
        # 保留の試験では DONE が必要なので、SHA を壊す。
        (out / 'bad-package' / 'DONE').touch()
        (out / 'bad-package' / '1000.jsonl').write_bytes(b'bad')
        fake = self.fake_adb({'SYNTHETIC_SERIAL': device})
        selected = archive.discover(fake, ['SYNTHETIC_SERIAL'])
        self.assertEqual(len(selected['navi_ready']), 2)
        counts = archive.pull(self.root, 'SYNTHETIC_SERIAL', fake, model='SyntheticDevice')
        self.assertEqual((counts['navi_archived'], counts['navi_held']), (1, 1))
        self.assertFalse(good.exists())
        self.assertTrue((out / 'bad-package').exists())
        rows = archive.load(device / 'navirun_receipt.json')['rows']
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['sourceRunId'], 'SyntheticDevice_1000')

    def test_versions_eras_and_source_unchanged(self):
        a = self.base / 'old.db'; b = self.base / 'new.db'
        make_db(a, 1, self.old); make_db(b, 24, self.new)
        before = a.read_bytes()
        result = archive.ingest_legacy(self.root, [a, b])
        self.assertEqual(result['ingested'], 2)
        self.assertEqual(a.read_bytes(), before)
        runs = list(archive.archive_runs(self.root))
        self.assertEqual({x.parent.name for x, _ in runs}, {'安定前', '安定後'})
        self.assertIn('memo', next(r for _, r in runs if r['dbVersion'] == 24)['session'])
        self.assertNotIn('memo', next(r for _, r in runs if r['dbVersion'] == 1)['session'])
        self.assertEqual(len(next(r for _, r in runs)['gps']), 1)

    def test_legacy_sensor_file_is_copied(self):
        path = self.base / 'source.db'
        make_db(path, 24, self.new, frame=False)
        sensor = self.base / 'sessions' / '1' / 'sensors.jsonl.gz'
        sensor.parent.mkdir(parents=True)
        sensor.write_bytes(b'synthetic gzip bytes')
        archive.ingest_legacy(self.root, [path])
        self.assertEqual((self.folder() / sensor.name).read_bytes(), sensor.read_bytes())

    def test_db_frames_idempotent_increment_and_conflict(self):
        path = self.base / 'run.db'; make_db(path, 24, self.new)
        frames = self.base / 'sessions' / '1' / 'frames'; frames.mkdir(parents=True)
        image = frames / 'synthetic.jpg'; image.write_bytes(b'first')
        archive.ingest_legacy(self.root, [path])
        folder = self.folder()
        self.assertEqual((folder / 'frames/synthetic.jpg').read_bytes(), b'first')
        self.assertEqual(archive.ingest_legacy(self.root, [path])['skipped'], 1)
        self.assertEqual(len(archive.load(folder / 'provenance.json')['sources']), 1)
        image.write_bytes(b'second')
        archive.ingest_legacy(self.root, [path])
        self.assertEqual((folder / 'frames/synthetic.jpg').read_bytes(), b'first')
        self.assertEqual(len(list((folder / 'frames').glob('synthetic.jpg.alt-*'))), 1)
        self.assertEqual(len(archive.load(folder / 'provenance.json')['conflicts']), 1)
        con = sqlite3.connect(path)
        con.execute("INSERT INTO timelapse_frame VALUES (2,1,1,'sessions/1/frames/next.jpg')")
        con.commit(); con.close()
        (frames / 'next.jpg').write_bytes(b'next')
        archive.ingest_legacy(self.root, [path])
        self.assertTrue((folder / 'frames/next.jpg').is_file())
        self.assertEqual(len(archive.load(folder / 'run.json')['frames']), 2)

    def test_zip_and_tar(self):
        path = self.base / 'source.db'; make_db(path, 1, self.old)
        image = self.base / 'synthetic.jpg'; image.write_bytes(b'image')
        z = self.base / 'backup.zip'; t = self.base / 'backup.tar'
        with zipfile.ZipFile(z, 'w') as out:
            out.write(path, 'db/buscourse.db')
            out.write(image, 'files/buscourse/sessions/1/frames/synthetic.jpg')
        with tarfile.open(t, 'w') as out:
            out.add(path, 'db/buscourse.db')
            out.add(image, 'buscourse/sessions/1/frames/synthetic.jpg')
        self.assertEqual(archive.ingest_legacy(self.root, [z])['ingested'], 1)
        self.assertEqual(archive.ingest_legacy(self.root, [t])['skipped'], 1)
        self.assertEqual((self.folder() / 'frames/synthetic.jpg').read_bytes(), b'image')

    def test_frames_only_tar_joins_db(self):
        path = self.base / 'source.db'; make_db(path, 1, self.old)
        image = self.base / 'synthetic.jpg'; image.write_bytes(b'from-tar')
        tar = self.base / 'frames.tar'
        with tarfile.open(tar, 'w') as out:
            out.add(image, 'buscourse/sessions/1/frames/synthetic.jpg')
        self.assertEqual(archive.ingest_legacy(self.root, [path, tar])['ingested'], 1)
        self.assertEqual((self.folder() / 'frames/synthetic.jpg').read_bytes(), b'from-tar')

    def test_receipt_startup(self):
        a = self.base / 'a.db'; b = self.base / 'b.db'
        make_db(a, 1, self.old, distance=299, frame=False)
        make_db(b, 24, self.new, distance=300, frame=False)
        archive.ingest_legacy(self.root, [a, b])
        output = self.base / 'receipt.json'
        receipt = archive.make_receipt(self.root, 'SyntheticDevice', output)
        self.assertEqual([r['startupTestDeletable'] for r in receipt['runs']], [True, False])
        self.assertEqual(receipt['schema'], archive.RECEIPT_SCHEMA)

    def test_startup_test_on_newest_recorded_version_is_kept(self):
        # 「最新の版」は走行が記録された版のうち一番新しいもの。その版の起動テストは消してよい印を付けない。
        a = self.base / 'a.db'; c = self.base / 'c.db'
        make_db(a, 19, self.new, distance=10, frame=False)
        make_db(c, 23, self.new + 5000, distance=10, frame=False)
        archive.ingest_legacy(self.root, [a, c])
        receipt = archive.make_receipt(self.root, 'SyntheticDevice', self.base / 'r.json')
        self.assertEqual([r['startupTestDeletable'] for r in receipt['runs']], [True, False])

    def test_blank_fields_and_recording_status_are_filled_by_later_source(self):
        # 撮影中に取られたバックアップでは距離が空欄。後のバックアップの値で埋め、前の目録は版付きで残す。
        a = self.base / 'a.db'; b = self.base / 'b.db'
        make_db(a, 23, self.new, frame=False)
        con = sqlite3.connect(a); con.execute("UPDATE recording_session SET total_distance_m=NULL, status='RECORDING'"); con.commit(); con.close()
        make_db(b, 23, self.new, distance=18000, frame=False)
        archive.ingest_legacy(self.root, [a])
        archive.ingest_legacy(self.root, [b])
        run = archive.load(self.folder() / 'run.json')
        self.assertEqual(run['totalDistanceM'], 18000)
        self.assertEqual(run['session']['status'], 'COMPLETED')
        self.assertTrue(list(self.folder().glob('run.json.rev-*')))

    def test_long_gps_track_is_not_a_startup_test_even_without_distance(self):
        run = {'totalDistanceM': None, 'gps': [{'seq': i, 'lat': 0.0, 'lon': i * 0.001} for i in range(5)]}
        self.assertFalse(archive.is_startup_test(run))  # 約445m
        self.assertTrue(archive.is_startup_test({'totalDistanceM': None, 'gps': []}))

    def test_legacy_run_with_missing_frames_is_not_receipted(self):
        # 古いバックアップに映像の実物が無い走行を「保管済み」と言うと、端末の映像を消させてしまう。
        a = self.base / 'a.db'
        make_db(a, 23, self.new, frame=True)
        archive.ingest_legacy(self.root, [a])
        receipt = archive.make_receipt(self.root, 'SyntheticDevice', self.base / 'r.json')
        self.assertEqual(receipt['runs'], [])

    def test_pull_with_fake_adb(self):
        device = self.base / 'device'; exports = device / 'archive_out'; exports.mkdir(parents=True)
        run = {'schema': archive.SCHEMA, 'startedAt': self.new, 'endedAt': self.new + 1000,
               'deviceModel': 'SyntheticDevice', 'runUid': None, 'type': 'FULL_RUN',
               'status': 'COMPLETED', 'totalDistanceM': 400, 'appVersion': 'test',
               'dbVersion': 24, 'session': {'id': 1}, 'gps': [], 'frames': [],
               'stopVisits': [], 'shocks': [], 'missingFiles': []}
        key = archive.run_key(self.new)
        good = exports / key; good.mkdir()
        archive.dump(good / 'run.json', run)
        archive.write_manifest(good); (good / 'DONE').touch()
        bad = exports / archive.run_key(self.new + 2000); bad.mkdir()
        archive.dump(bad / 'run.json', dict(run, startedAt=self.new + 2000))
        archive.write_manifest(bad); (bad / 'DONE').touch()
        (bad / 'run.json').write_text('broken')
        pending = exports / archive.run_key(self.new + 3000); pending.mkdir()
        archive.dump(pending / 'run.json', run)
        fake = self.base / 'fake_adb.py'
        fake.write_text('''#!/usr/bin/env python3
import pathlib, shutil, sys
base = pathlib.Path({device!r})
args = sys.argv[3:]
def local(remote):
    return base / pathlib.Path(remote).relative_to('/sdcard/Android/data/com.istech.buscourse/files')
if args[:2] == ['shell', 'ls']:
    print('\\n'.join(p.name for p in local(args[-1]).iterdir()))
elif args[:2] == ['shell', 'test']:
    sys.exit(0 if local(args[3]).is_file() else 1)
elif args[0] == 'pull':
    shutil.copytree(local(args[1]), pathlib.Path(args[2]) / pathlib.Path(args[1]).name)
elif args[0] == 'push':
    shutil.copy2(args[1], local(args[2]))
elif args[:2] == ['shell', 'rm']:
    shutil.rmtree(local(args[3]))
'''.format(device=str(device)))
        fake.chmod(0o755)
        if os.name == 'nt':  # Windows は .py を直接起動できないので .cmd でくるむ
            wrapper = self.base / 'fake_adb.cmd'
            wrapper.write_text(f'@"{sys.executable}" "{fake}" %*\n')
            fake = wrapper
        result = archive.pull(self.root, 'SYNTHETIC_SERIAL', str(fake))
        self.assertEqual(result['ingested'], 1)
        self.assertEqual(result['mismatch'], 1)
        self.assertFalse(good.exists())
        self.assertTrue(bad.exists())
        self.assertTrue(pending.exists())
        receipt = archive.load(device / 'archive_receipt.json')
        self.assertEqual(receipt['runs'][0]['frames'], 0)
        self.assertEqual(archive.load(self.root / 'index.json')[0]['runKey'], key)

    def test_manifest_validation(self):
        folder = self.base / 'export'; folder.mkdir()
        (folder / 'run.json').write_text('{}')
        archive.write_manifest(folder)
        (folder / 'DONE').touch()
        self.assertTrue(archive.verify_manifest(folder))
        (folder / 'run.json').write_text('bad')
        self.assertFalse(archive.verify_manifest(folder))

    def test_receipt_per_run_and_cancel_before_second(self):
        device = self.base / 'device'
        folders = [self.export(device, self.new + i * 2000) for i in range(3)]
        adb = self.fake_adb({'SER_A': device})
        stop = threading.Event()
        def progress(info):
            if info['number'] == 2:
                stop.set()
        result = archive.pull(self.root, 'SER_A', adb, cancel=stop, progress=progress)
        self.assertEqual(result['ingested'], 1)
        self.assertTrue(result['cancelled'])
        self.assertFalse(folders[0].exists())
        self.assertTrue(all(folder.exists() for folder in folders[1:]))
        self.assertEqual(len(archive.load(device / 'archive_receipt.json')['runs']), 1)

    def test_stall_retries_three_times_then_next_run(self):
        device = self.base / 'device'
        stuck = self.export(device, self.new)
        next_run = self.export(device, self.new + 2000)
        adb = self.fake_adb({'SER_A': device}, stalled=[stuck.name])
        result = archive.pull(self.root, 'SER_A', adb, stall_seconds=0.15)
        self.assertEqual(result['not_pulled'], 1)
        self.assertEqual(result['ingested'], 1)
        self.assertEqual((self.base / 'pull_count.txt').read_text(), '3')
        self.assertTrue(stuck.exists())
        self.assertFalse(next_run.exists())

    def test_bad_run_kept_while_next_advances(self):
        device = self.base / 'device'
        bad = self.export(device, self.new)
        (bad / 'frames' / 'synthetic.bin').write_bytes(b'wrong')
        good = self.export(device, self.new + 2000)
        result = archive.pull(self.root, 'SER_A', self.fake_adb({'SER_A': device}))
        self.assertEqual((result['mismatch'], result['ingested']), (1, 1))
        self.assertTrue(bad.exists())
        self.assertFalse(good.exists())

    def test_adb_choice_and_device_filter_and_selection(self):
        first = self.base / 'first'; second = self.base / 'second'; empty = self.base / 'empty'
        self.export(first, self.new)
        self.export(second, self.new + 2000)
        empty.mkdir()
        good_adb = self.fake_adb({'SER_A': first, 'SER_B': second, 'SER_EMPTY': empty})
        mismatch = self.base / 'mismatch.py'
        mismatch.write_text("#!/usr/bin/env python3\nprint('server version doesn\\'t match')\nprint('List of devices attached')\n")
        mismatch.chmod(0o755)
        if os.name == 'nt':
            wrapper = mismatch.with_suffix('.cmd')
            wrapper.write_text(f'@"{sys.executable}" "{mismatch}" %*\n')
            mismatch = wrapper
        with mock.patch.dict(os.environ, {'BUSCOURSE_ADB': good_adb}):
            adb, serials = archive.choose_adb(str(mismatch))
        self.assertEqual(adb, good_adb)
        self.assertEqual(len(serials), 3)
        selected = archive.discover(adb, serials, lambda choices: choices[1])
        self.assertEqual(selected['serial'], 'SER_B')
        self.assertEqual(len(archive.discover(adb, serials, lambda choices: choices)), 2)
        self.assertEqual(archive.discover(adb, ['SER_EMPTY'])['ready'], [])

    def test_second_pull_does_not_hash_existing_vault_file(self):
        device = self.base / 'device'
        self.export(device, self.new)
        adb = self.fake_adb({'SER_A': device})
        archive.pull(self.root, 'SER_A', adb)
        self.export(device, self.new)
        real_sha = archive.sha
        vault_reads = []
        def counted(path):
            if self.root in path.parents and '.work' not in path.parts and path.name == 'synthetic.bin':
                vault_reads.append(path)
            return real_sha(path)
        with mock.patch.object(archive, 'sha', side_effect=counted):
            archive.pull(self.root, 'SER_A', adb)
        self.assertEqual(vault_reads, [])


if __name__ == '__main__':
    unittest.main()
