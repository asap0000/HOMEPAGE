import sys
import os
import importlib.util
import json
from pathlib import Path
import sqlite3
import tarfile
import tempfile
import unittest
import zipfile

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


if __name__ == '__main__':
    unittest.main()
