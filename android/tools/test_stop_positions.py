import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('stop_positions', Path(__file__).with_name('stop_positions.py'))
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)

# 架空の停留所マスタ。名前・番号・人数・出自など、位置以外の欄を多めに持たせる（出力に混ざらないことを見る）。
NAMES = ['架空ひまわり園前', '架空つくし台', 'Synthetic Gate']
IDS = ['M-90001', 'M-90002', 'M-90003']


def synthetic_master():
    return [
        {'master_id': IDS[0], 'name': NAMES[0], 'lat': 1.25, 'lon': 2.5, 'riders_g1': 7, 'riders_g2': 3,
         'depot_role': 'none', 'sources': [{'source_id': 'synthetic::a', 'year': 2026}]},
        {'master_id': IDS[1], 'name': NAMES[1], 'lat': -3.5, 'lon': 120.75, 'riders_g1': 0, 'riders_g2': 0},
        {'master_id': IDS[2], 'name': NAMES[2], 'lat': 0, 'lon': -179.5},
    ]


class StopPositionsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)

    def run_main(self, argv):
        """main を、画面に出る文言を捕まえて走らせる。(終了コード, 標準出力, 標準エラー)。"""
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = tool.main(argv)
        return code, out.getvalue(), err.getvalue()

    def master_file(self, data):
        path = self.base / 'stop_master.json'
        path.write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
        return path

    def test_書き出しに名前や番号が混ざらない(self):
        master = self.master_file(synthetic_master())
        out = self.base / 'out' / 'stop_positions.json'

        code, _, _ = self.run_main([str(master), '-o', str(out)])

        self.assertEqual(code, 0)
        text = out.read_text(encoding='utf-8')
        for secret in NAMES + IDS + ['name', 'master_id', 'riders', 'depot_role', 'sources', 'synthetic::a']:
            self.assertNotIn(secret, text)
        document = json.loads(text)
        self.assertEqual(set(document), {'schema', 'generatedAt', 'points'})
        self.assertEqual(document['schema'], 'stop-positions/1')
        self.assertIsInstance(document['generatedAt'], int)
        self.assertEqual(document['points'], [{'lat': 1.25, 'lon': 2.5}, {'lat': -3.5, 'lon': 120.75}, {'lat': 0.0, 'lon': -179.5}])
        for point in document['points']:
            self.assertEqual(set(point), {'lat', 'lon'})

    def test_マスタは書き換えない(self):
        master = self.master_file(synthetic_master())
        before = master.read_bytes()

        self.run_main([str(master), '-o', str(self.base / 'o.json')])

        self.assertEqual(master.read_bytes(), before)

    def test_マスタの形が違っても位置だけを取り出す(self):
        records = synthetic_master()
        shapes = {
            'list': records,
            'wrapped': {'schema': 'x', 'stops': records, 'meta': {'name': NAMES[0]}},
            'keyed': {record['master_id']: record for record in records},
        }
        for label, shape in shapes.items():
            with self.subTest(label):
                points, skipped = tool.extract_points(shape)
                self.assertEqual(len(points), 3)
                self.assertEqual(skipped, 0)

    def test_数でない・範囲外・欠けた位置は読み飛ばして数える(self):
        master = [
            {'lat': 1.0, 'lon': 2.0, 'name': NAMES[0]},
            {'lat': '1.0', 'lon': 2.0},
            {'lat': True, 'lon': 2.0},
            {'lat': 91.0, 'lon': 2.0},
            {'lat': 1.0, 'lon': 181.0},
            {'lat': float('nan'), 'lon': 1.0},
            {'lon': 3.0},
            {'name': NAMES[1]},
            'not a record',
        ]

        points, skipped = tool.extract_points(master)

        self.assertEqual(points, [{'lat': 1.0, 'lon': 2.0}])
        self.assertEqual(skipped, 7)

    def test_位置が1つも無いときは書き出さず失敗する_エラーに中身を出さない(self):
        master = self.master_file([{'name': NAMES[0], 'master_id': IDS[0]}])
        out = self.base / 'never.json'
        code, _, message = self.run_main([str(master), '-o', str(out)])

        self.assertEqual(code, 2)
        self.assertFalse(out.exists())
        self.assertNotIn(NAMES[0], message)
        self.assertNotIn(IDS[0], message)

    def test_壊れたマスタは失敗する(self):
        bad = self.base / 'bad.json'
        bad.write_text('{', encoding='utf-8')

        self.assertEqual(self.run_main([str(bad), '-o', str(self.base / 'o.json')])[0], 2)
        self.assertEqual(self.run_main([str(self.base / 'missing.json'), '-o', str(self.base / 'o.json')])[0], 2)

    def test_pushは端末のfilesフォルダへ置き_置けたことを照合する(self):
        out = tool.write_document(self.base / 'stop_positions.json', tool.build_document([{'lat': 1.0, 'lon': 2.0}], now_ms=1))
        digest = hashlib.sha256(out.read_bytes()).hexdigest()
        calls = []

        def fake_run(command, **kwargs):
            calls.append(command)
            if command[3:5] == ['shell', 'sha256sum']:
                return subprocess.CompletedProcess(command, 0, stdout=digest + '  ' + command[5] + '\n', stderr='')
            return subprocess.CompletedProcess(command, 0, stdout='', stderr='')

        with mock.patch.object(tool.subprocess, 'run', side_effect=fake_run):
            target = tool.push('adb', 'SER1', out, 'com.istech.buscourse.debug')

        self.assertEqual(target, '/sdcard/Android/data/com.istech.buscourse.debug/files/stop_positions.json')
        self.assertEqual(calls[0], ['adb', '-s', 'SER1', 'shell', 'mkdir', '-p', '/sdcard/Android/data/com.istech.buscourse.debug/files'])
        self.assertEqual(calls[1], ['adb', '-s', 'SER1', 'push', str(out), target])
        self.assertEqual(calls[2], ['adb', '-s', 'SER1', 'shell', 'sha256sum', target])

    def test_pushは既定で実データ側のアプリIDへ置く(self):
        out = self.base / 'stop_positions.json'
        pushed = {}
        calls = []

        def fake_run(command, **kwargs):
            calls.append(command)
            if command[3] == 'push':
                pushed['local'] = command[4]
            if command[3:5] == ['shell', 'sha256sum']:
                digest = hashlib.sha256(Path(pushed['local']).read_bytes()).hexdigest()
                return subprocess.CompletedProcess(command, 0, stdout=digest + '  x\n', stderr='')
            return subprocess.CompletedProcess(command, 0, stdout='', stderr='')

        master = self.master_file(synthetic_master())
        with mock.patch.object(tool, 'choose_adb', return_value='adb'), mock.patch.object(tool.subprocess, 'run', side_effect=fake_run):
            code, _, _ = self.run_main([str(master), '-o', str(out), '--push', 'SER1'])

        self.assertEqual(code, 0)
        self.assertEqual(calls[1], ['adb', '-s', 'SER1', 'push', str(out), '/sdcard/Android/data/com.istech.buscourse/files/stop_positions.json'])

    def test_pushの照合が合わなければ失敗する(self):
        out = tool.write_document(self.base / 'stop_positions.json', tool.build_document([{'lat': 1.0, 'lon': 2.0}], now_ms=1))

        def fake_run(command, **kwargs):
            return subprocess.CompletedProcess(command, 0, stdout='0' * 64 + '  x\n', stderr='')

        with mock.patch.object(tool.subprocess, 'run', side_effect=fake_run):
            with self.assertRaises(RuntimeError):
                tool.push('adb', 'SER1', out, 'com.istech.buscourse')

    def test_pushのadbが失敗したら失敗する(self):
        out = tool.write_document(self.base / 'stop_positions.json', tool.build_document([{'lat': 1.0, 'lon': 2.0}], now_ms=1))

        def fake_run(command, **kwargs):
            failing = command[3] == 'push'
            return subprocess.CompletedProcess(command, 1 if failing else 0, stdout='', stderr='device offline' if failing else '')

        with mock.patch.object(tool.subprocess, 'run', side_effect=fake_run):
            with self.assertRaises(RuntimeError):
                tool.push('adb', 'SER1', out, 'com.istech.buscourse')


if __name__ == '__main__':
    unittest.main()
